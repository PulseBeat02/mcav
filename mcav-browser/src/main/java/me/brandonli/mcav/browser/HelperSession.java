/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.browser;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.ProtocolException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Stream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Os;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * One browser helper process: its private folder, the token-authenticated connection, the frames it streams and the
 * input sent to it. A slow listener skips pictures instead of queuing them; a settled page repeats its last picture, so
 * an encoder that spreads a change over frames can finish it. A helper exits when its standard input ends, so it never
 * outlives the server.
 */
final class HelperSession implements BrowserSession {

  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";
  private static final String WORKING_FOLDER_PROPERTY = "user.dir";

  private static final char LINE_FEED = '\n';
  private static final char CARRIAGE_RETURN = '\r';

  private static final String LOG_THREAD_NAME = "mcav-browser-helper-log";
  private static final String READER_THREAD_NAME = "mcav-browser-reader";
  private static final String FRAMES_THREAD_NAME = "mcav-browser-frames";
  private static final String INPUT_THREAD_NAME = "mcav-browser-input";

  private static final int CONNECTION_BACKLOG = 1;

  private static final Logger LOGGER = LoggerFactory.getLogger(HelperSession.class);

  private static final int MAXIMUM_QUEUED_INPUT = 128;
  private static final int INPUT_THREADS = 1;
  private static final long NO_KEEP_ALIVE_MILLISECONDS = 0L;

  private static final int OUTPUT_TAIL_LINES = 40;
  private static final int OUTPUT_LINE_CHARACTERS = 1024;

  private static final long MINIMUM_START_WAIT_MILLISECONDS = 1L;
  private static final long STOP_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long KILL_TIMEOUT_MILLISECONDS = 5_000L;
  private static final long EXIT_GRACE_MILLISECONDS = 1_000L;
  private static final long EXIT_POLL_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(10L);

  private static final byte[] NO_PIXELS = new byte[0];

  private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute<?>[0];

  private static final String SOCKET_NAME = "s";

  // a socket path has at most 107 characters, and on Linux Chromium binds one 45 characters below the session folder
  private static final int MAXIMUM_SOCKET_PATH = 107;
  private static final int CHROMIUM_SOCKET_TAIL = 45;

  private static final int MAXIMUM_FOLDER_ATTEMPTS = 100;

  private static final Thread NOT_STARTED = new Thread("mcav-browser-not-started");

  private static final String PROFILE_FOLDER = "profile";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String OWNER_ONLY_PERMISSIONS = "rwx------";

  private static final long CONNECT_POLL_MILLISECONDS = 250L;

  private static final int INPUT_BUFFER_BYTES = 1 << 16;
  private static final int OUTPUT_READ_CHARACTERS = 4096;

  static final long REPEAT_DELAY_MILLISECONDS = 50L;
  static final int SETTLED_REPEATS = 40;

  private final Path folder;
  private final Process process;
  private final Writer standardInput;
  private final SocketChannel channel;
  private final DataOutputStream output;
  private final FrameCanvas canvas;
  private final Listener listener;
  private final ThreadPoolExecutor inputSender;
  private final Deque<String> outputTail;
  private final AtomicBoolean closing;
  private final AtomicBoolean ended;
  private final CompletableFuture<@Nullable Void> started;
  private final Object deliveryLock;
  private final Set<StartEvent> startEvents;
  private final LogBudget logBudget;

  private volatile Thread reader;
  private volatile Thread delivery;
  private volatile Thread drain;
  private boolean frameWaiting;

  private HelperSession(
    final Path folder,
    final Process process,
    final Writer standardInput,
    final SocketChannel channel,
    final FrameCanvas canvas,
    final Listener listener
  ) {
    this.folder = folder;
    this.process = process;
    this.standardInput = standardInput;
    this.channel = channel;
    final OutputStream rawOutput = Channels.newOutputStream(channel);
    this.output = new DataOutputStream(new BufferedOutputStream(rawOutput));
    this.canvas = canvas;
    this.listener = listener;
    this.inputSender = new ThreadPoolExecutor(
      INPUT_THREADS,
      INPUT_THREADS,
      NO_KEEP_ALIVE_MILLISECONDS,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(MAXIMUM_QUEUED_INPUT),
      HelperSession::createInputThread
    );
    this.outputTail = new ArrayDeque<>();
    this.closing = new AtomicBoolean();
    this.ended = new AtomicBoolean();
    this.started = new CompletableFuture<>();
    this.deliveryLock = new Object();
    this.startEvents = EnumSet.noneOf(StartEvent.class);
    this.logBudget = new LogBudget(System::nanoTime);
    this.reader = NOT_STARTED;
    this.delivery = NOT_STARTED;
    this.drain = NOT_STARTED;
  }

  static HelperSession open(
    final HelperLauncher launcher,
    final Path natives,
    final BrowserSource source,
    final BrowserOptions options,
    final Listener listener
  ) {
    final Path temporaryFolder = Path.of(requireNonNull(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY))); // checker framework
    return open(launcher, natives, source, options, listener, temporaryFolder);
  }

  @VisibleForTesting
  static HelperSession open(
    final HelperLauncher launcher,
    final Path natives,
    final BrowserSource source,
    final BrowserOptions options,
    final Listener listener,
    final Path temporaryFolder
  ) {
    final long generation = HelperProcesses.requireOpen();
    final Path folder = createFolder(temporaryFolder);
    final Path socket = folder.resolve(SOCKET_NAME);
    final byte[] token = createToken();
    Process process = null;
    HelperSession session = null;
    SocketChannel pendingChannel = null;
    try (final ServerSocketChannel server = bind(socket)) {
      final Os os = launcher.getOs();
      requireShortEnough(os, folder);

      final Path libraryFolder = launcher.linkLibraries(folder);
      final URI uri = source.getUri();
      final HelperConfiguration configuration = configure(token, socket, natives, folder.resolve(PROFILE_FOLDER), uri, source, options);
      process = startProcess(launcher, folder, libraryFolder);
      final Writer standardInput = sendConfiguration(process, configuration);

      final long timeoutMilliseconds = launcher.getStartTimeoutMilliseconds();
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
      final SocketChannel channel = accept(server, process, deadline);
      pendingChannel = channel;
      Files.deleteIfExists(socket);

      final int width = source.getWidth();
      final int height = source.getHeight();
      final FrameCanvas canvas = new FrameCanvas(width, height);
      session = new HelperSession(folder, process, standardInput, channel, canvas, listener);
      pendingChannel = null;

      if (!HelperProcesses.register(session, generation)) {
        final String message = "The browser module was stopped while the browser started!";
        throw new BrowserException(message);
      }

      session.startThreads(token);
      session.awaitStart(deadline, uri);

      return session;
    } catch (final IOException exception) {
      closeAfterFailure(session, process, pendingChannel, folder);

      final @Nullable String reason = exception.getMessage();
      final String message = "Because of \"%s\", the browser helper could not be started!".formatted(reason);
      throw new BrowserException(message, exception);
    } catch (final RuntimeException | Error exception) {
      closeAfterFailure(session, process, pendingChannel, folder);
      throw exception;
    }
  }

  // an operator can act on this reason, unlike on Chromium's abort
  @VisibleForTesting
  static void requireShortEnough(final Os os, final Path folder) {
    final String path = folder.toString();
    if (os == Os.LINUX && path.length() + CHROMIUM_SOCKET_TAIL > MAXIMUM_SOCKET_PATH) {
      final int longest =
        MAXIMUM_SOCKET_PATH - CHROMIUM_SOCKET_TAIL - 1 - SessionFolders.PREFIX.length() - SessionFolders.RANDOM_CHARACTERS;
      final @Nullable Path temporaryFolder = folder.getParent();
      final String message =
        "The temporary folder %s is too long for the browser: on Linux it may have at most %s characters, so start the server with a shorter java.io.tmpdir (such as /tmp)!".formatted(
          temporaryFolder,
          longest
        );
      throw new BrowserException(message);
    }
  }

  private static HelperConfiguration configure(
    final byte[] token,
    final Path socket,
    final Path natives,
    final Path profile,
    final URI uri,
    final BrowserSource source,
    final BrowserOptions options
  ) {
    final int width = source.getWidth();
    final int height = source.getHeight();
    final int frameInterval = source.getFrameInterval();
    final int frameRate = options.getFrameRate();
    final boolean allowsJavaScriptJit = options.isJavaScriptJit();
    final boolean allowsPrivateNetworks = options.isPrivateNetworks();
    final boolean allowsAutoplay = options.isAutoplay();
    final boolean confined = options.isConfined();
    final Path serverFolder = Path.of(requireNonNull(System.getProperty(WORKING_FOLDER_PROPERTY))).toAbsolutePath(); // checker framework
    final List<String> refusedHosts = options.getRefusedHosts();
    return new HelperConfiguration(
      token,
      socket,
      natives,
      profile,
      uri,
      width,
      height,
      frameInterval,
      frameRate,
      allowsJavaScriptJit,
      allowsPrivateNetworks,
      allowsAutoplay,
      confined,
      serverFolder,
      refusedHosts
    );
  }

  private static Writer sendConfiguration(final Process process, final HelperConfiguration configuration) throws IOException {
    final OutputStream processInput = process.getOutputStream();
    final Writer standardInput = new OutputStreamWriter(processInput, StandardCharsets.UTF_8);
    standardInput.write(configuration.toLine());
    standardInput.write(LINE_FEED);
    standardInput.flush();

    return standardInput;
  }

  private static void closeAfterFailure(
    final @Nullable HelperSession session,
    final @Nullable Process process,
    final @Nullable SocketChannel pendingChannel,
    final Path folder
  ) {
    if (pendingChannel != null) {
      closeQuietly(pendingChannel);
    }

    if (session != null) {
      session.close();
      return;
    }

    if (process != null) {
      final OutputStream processInput = process.getOutputStream();
      closeQuietly(processInput);
      stopProcess(process);
    }

    deleteFolder(folder);
  }

  @VisibleForTesting
  static Path createFolder(final Path temporaryFolder) {
    return createFolder(temporaryFolder, SessionFolders::newName);
  }

  @VisibleForTesting
  static Path createFolder(final Path temporaryFolder, final Supplier<String> names) {
    final FileSystem fileSystem = temporaryFolder.getFileSystem();
    final FileAttribute<?>[] attributes = createFolderAttributes(fileSystem);
    try {
      return createFolderUnderDrawnName(temporaryFolder, names, attributes);
    } catch (final IOException exception) {
      final @Nullable String reason = exception.getMessage();
      final String message = "Because of \"%s\", the folder of the browser session cannot be created!".formatted(reason);
      throw new BrowserException(message, exception);
    }
  }

  private static FileAttribute<?>[] createFolderAttributes(final FileSystem fileSystem) {
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean supportsPosix = views.contains(POSIX_ATTRIBUTE_VIEW);
    if (!supportsPosix) {
      return NO_ATTRIBUTES;
    }

    final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(OWNER_ONLY_PERMISSIONS);
    final FileAttribute<Set<PosixFilePermission>> ownerOnly = PosixFilePermissions.asFileAttribute(permissions);
    return new FileAttribute<?>[] { ownerOnly };
  }

  private static Path createFolderUnderDrawnName(
    final Path temporaryFolder,
    final Supplier<String> names,
    final FileAttribute<?>[] attributes
  ) throws IOException {
    for (int attempt = 1; attempt <= MAXIMUM_FOLDER_ATTEMPTS; attempt++) {
      final Path candidate = temporaryFolder.resolve(names.get());
      try {
        final Path folder = Files.createDirectory(candidate, attributes);
        SessionFolders.ofThisServer().record(folder);
        return folder;
      } catch (final FileAlreadyExistsException exception) {
        LOGGER.debug("The name {} of a browser session's folder is taken, another is drawn.", candidate);
      }
    }

    final String message = "Every name drawn was taken!";
    throw new IOException(message);
  }

  @VisibleForTesting
  static byte[] createToken() {
    final SecureRandom random = new SecureRandom();
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    random.nextBytes(token);

    return token;
  }

  @VisibleForTesting
  static ServerSocketChannel bind(final Path socket) throws IOException {
    final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
      server.bind(address, CONNECTION_BACKLOG);
      server.configureBlocking(false);

      return server;
    } catch (final IOException exception) {
      server.close();
      throw exception;
    }
  }

  private static Process startProcess(final HelperLauncher launcher, final Path folder, final @Nullable Path libraryFolder)
    throws IOException {
    final ProcessBuilder builder = createProcessBuilder(launcher, folder, libraryFolder);
    return builder.start();
  }

  @VisibleForTesting
  static ProcessBuilder createProcessBuilder(final HelperLauncher launcher, final Path folder, final @Nullable Path libraryFolder) {
    final List<String> command = launcher.createCommand(folder);
    final ProcessBuilder builder = new ProcessBuilder(command);
    final Map<String, String> environment = builder.environment();
    environment.clear();
    final Map<String, String> kept = launcher.createEnvironment(folder, libraryFolder);
    environment.putAll(kept);
    builder.directory(folder.toFile());
    builder.redirectErrorStream(true);

    return builder;
  }

  @VisibleForTesting
  static SocketChannel accept(final ServerSocketChannel server, final Process process, final long deadline) throws IOException {
    try (final Selector selector = Selector.open()) {
      server.register(selector, SelectionKey.OP_ACCEPT);
      return awaitConnection(server, selector, process, deadline);
    }
  }

  private static SocketChannel awaitConnection(
    final ServerSocketChannel server,
    final Selector selector,
    final Process process,
    final long deadline
  ) throws IOException {
    while (true) {
      // an interrupted select returns silently, so the interrupt of a release is checked here
      final Thread current = Thread.currentThread();
      if (current.isInterrupted()) {
        final String message = "Interrupted while the browser helper connected!";
        throw new BrowserException(message);
      }
      final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remaining <= 0) {
        final String message = "The browser helper did not connect in time!";
        throw new BrowserException(message);
      }
      final boolean alive = process.isAlive();
      if (!alive) {
        final String message = "The browser helper exited with code %s before it connected!".formatted(process.exitValue());
        throw new BrowserException(message);
      }
      selector.select(Math.min(remaining, CONNECT_POLL_MILLISECONDS));
      final SocketChannel channel = server.accept();
      if (channel != null) {
        channel.configureBlocking(true);
        return channel;
      }
    }
  }

  private void startThreads(final byte[] token) {
    final InputStream processOutput = this.process.getInputStream();
    this.drain = this.startDaemon(LOG_THREAD_NAME, () -> this.drainOutput(processOutput));
    final InputStream rawInput = Channels.newInputStream(this.channel);
    final DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput, INPUT_BUFFER_BYTES));
    this.reader = this.startDaemon(READER_THREAD_NAME, () -> this.read(input, token));
    this.delivery = this.startDaemon(FRAMES_THREAD_NAME, this::deliverFrames);
  }

  private Thread startDaemon(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();

    return thread;
  }

  @VisibleForTesting
  static Thread createInputThread(final Runnable task) {
    final Thread thread = new Thread(task, INPUT_THREAD_NAME);
    thread.setDaemon(true);

    return thread;
  }

  private void awaitStart(final long deadline, final URI uri) {
    final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    try {
      this.started.get(Math.max(MINIMUM_START_WAIT_MILLISECONDS, remaining), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while starting the browser!";
      throw new BrowserException(message, exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = requireNonNull(exception.getCause(), "A failed start has a cause!");
      final String tail = this.getOutputTail();
      if (!tail.isEmpty()) {
        LOGGER.warn("The browser helper wrote these lines before its start failed.{}{}", System.lineSeparator(), tail);
      }

      final String address = AddressText.describe(uri.toString());
      final @Nullable String reason = cause.getMessage();
      final String message = "Because of \"%s\", page %s could not be opened!".formatted(reason, address);
      throw new BrowserException(message, cause);
    } catch (final TimeoutException exception) {
      final String tail = this.getOutputTail();
      final String address = AddressText.describe(uri.toString());
      final String message = "The browser did not show %s in time!%n%s".formatted(address, tail);
      throw new BrowserException(message, exception);
    }
  }

  @VisibleForTesting
  void read(final DataInputStream input, final byte[] token) {
    final byte[][] buffer = { new byte[0] };
    final int pageBytes = this.canvas.getWidth() * this.canvas.getHeight() * HelperProtocol.PIXEL_BYTES;
    try {
      final HelperMessage hello = HelperProtocol.read(input, size -> NO_PIXELS);
      checkHello(hello, token);
      while (true) {
        final HelperMessage message = HelperProtocol.read(input, size -> this.choosePixelBuffer(buffer, pageBytes, size));
        this.onMessage(message);
      }
    } catch (final EOFException exception) {
      this.end(this.describeEnd("The browser helper closed the connection!"), null);
    } catch (final IOException exception) {
      final @Nullable String reason = exception.getMessage();
      final String message = "Because of \"%s\", the connection to the browser helper failed!".formatted(reason);
      this.end(this.describeEnd(message), exception);
    }
  }

  private byte[] choosePixelBuffer(final byte[][] buffer, final int pageBytes, final int size) {
    if (size > pageBytes) {
      return NO_PIXELS;
    }

    if (buffer[0].length < size) {
      buffer[0] = new byte[size];
    }

    return buffer[0];
  }

  // Windows reports the end of an exiting helper's connection as a reset, so its exit describes the end
  private String describeEnd(final String connectionEnd) {
    if (this.closing.get()) {
      return connectionEnd;
    }

    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_GRACE_MILLISECONDS);
    while (this.process.isAlive() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(EXIT_POLL_NANOSECONDS);
    }

    if (this.process.isAlive()) {
      return connectionEnd;
    }

    return "The browser helper exited with code %s before it was asked to stop!".formatted(this.process.exitValue());
  }

  @VisibleForTesting
  static void checkHello(final HelperMessage hello, final byte[] token) throws ProtocolException {
    final int type = hello.getType();
    if (type != HelperProtocol.HELLO) {
      final String message = "The browser helper did not introduce itself!";
      throw new ProtocolException(message);
    }

    final byte[] presented = hello.getToken();
    if (!MessageDigest.isEqual(presented, token)) {
      final String message = "The browser helper presented a wrong session token!";
      throw new ProtocolException(message);
    }

    final int version = hello.getNumber();
    if (version != HelperProtocol.VERSION) {
      final String message = "Protocol version %s of the browser helper differs from version %s of the server!".formatted(
        version,
        HelperProtocol.VERSION
      );
      throw new ProtocolException(message);
    }
  }

  private void onMessage(final HelperMessage message) throws ProtocolException {
    final int type = message.getType();
    switch (type) {
      case HelperProtocol.FRAME -> this.onFrame(message);
      case HelperProtocol.AUDIO -> this.onAudio(message);
      case HelperProtocol.READY -> this.onReady(message);
      case HelperProtocol.LOADING -> this.onLoading(message);
      case HelperProtocol.LOAD_ERROR -> this.onLoadError(message);
      case HelperProtocol.NOTICE -> this.onNotice(message);
      case HelperProtocol.FAILURE -> this.onFailure(message);
      default -> {
        final String reason = "The browser helper sent message type %s, which only the server sends!".formatted(type);
        throw new ProtocolException(reason);
      }
    }
  }

  private void onFrame(final HelperMessage message) throws ProtocolException {
    final FrameRegion region = message.getRegion();
    this.canvas.apply(region);
    this.requestFrame();
    this.onStartEvent(StartEvent.FRAME);
  }

  private void onAudio(final HelperMessage message) {
    final byte[] samples = message.getSamples();
    this.listener.onAudio(samples);
  }

  private void onReady(final HelperMessage message) {
    final String version = message.getText();
    LOGGER.debug("CEF {} runs in the browser helper.", version);
    this.onStartEvent(StartEvent.READY);
  }

  private void onLoading(final HelperMessage message) {
    final boolean loading = message.getNumber() == HelperProtocol.LOADING_STARTED;
    if (!loading) {
      this.onStartEvent(StartEvent.LOADED);
    }
  }

  private void onLoadError(final HelperMessage message) {
    final String text = message.getText();
    final String url = message.getUrl();
    // the helper is untrusted, so the address is checked again
    final String address = AddressText.describe(url);
    final int code = message.getNumber();
    this.logBudget.log(
      () -> LOGGER.warn("Page {} failed to load with error {} (code {}) in the browser.", address, text, code),
      this::logSkipped
    );

    final String reason = "The page failed to load with error %s (code %s) in the browser!".formatted(text, code);
    final BrowserException failure = new BrowserException(reason);
    this.started.completeExceptionally(failure);
  }

  private void onNotice(final HelperMessage message) {
    final String text = message.getText();
    this.logBudget.log(() -> LOGGER.debug("The browser sent the notice \"{}\" about the page.", text), this::logSkipped);
  }

  private void onFailure(final HelperMessage message) {
    final String text = message.getText();
    final String reason = "Because of \"%s\", the browser failed!".formatted(text);
    final BrowserException failure = new BrowserException(reason);
    this.started.completeExceptionally(failure);
    this.end(reason, failure);
  }

  private void logSkipped(final long lines) {
    LOGGER.debug("Browser: {} more notices of the page were not logged.", lines);
  }

  @Override
  public void requestFrame() {
    synchronized (this.deliveryLock) {
      this.frameWaiting = true;
      this.deliveryLock.notifyAll();
    }
  }

  private synchronized void onStartEvent(final StartEvent event) {
    this.startEvents.add(event);

    if (this.startEvents.size() == StartEvent.values().length) {
      this.started.complete(null);
    }
  }

  private void deliverFrames() {
    int repeatsLeft = 0;
    while (true) {
      final boolean fresh;
      try {
        final FrameWait wait = repeatsLeft > 0 ? FrameWait.UNTIL_REPEAT_DELAY : FrameWait.UNTIL_FRAME;
        fresh = this.awaitFrame(wait);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();

        return;
      }
      if (this.closing.get()) {
        return;
      }
      repeatsLeft = fresh ? SETTLED_REPEATS : repeatsLeft - 1;
      final ImageBuffer frame = this.canvas.snapshot();
      if (frame == null) {
        return;
      }
      this.listener.onFrame(frame);
    }
  }

  private boolean awaitFrame(final FrameWait wait) throws InterruptedException {
    synchronized (this.deliveryLock) {
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPEAT_DELAY_MILLISECONDS);
      final boolean signalled = this.waitForFrameOrClose(wait, deadline);
      if (!signalled) {
        return false;
      }

      final boolean fresh = this.frameWaiting;
      this.frameWaiting = false;

      return fresh;
    }
  }

  private boolean waitForFrameOrClose(final FrameWait wait, final long deadline) throws InterruptedException {
    while (!this.frameWaiting && !this.closing.get()) {
      final long remaining = deadline - System.nanoTime();
      if (wait == FrameWait.UNTIL_FRAME) {
        this.deliveryLock.wait();
      } else if (remaining > 0) {
        TimeUnit.NANOSECONDS.timedWait(this.deliveryLock, remaining);
      } else {
        return false;
      }
    }

    return true;
  }

  @VisibleForTesting
  void drainOutput(final InputStream stream) {
    final StringBuilder line = new StringBuilder();
    try (final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      final char[] characters = new char[OUTPUT_READ_CHARACTERS];
      int count = reader.read(characters);
      while (count >= 0) {
        this.rememberCompleteLines(characters, count, line);
        count = reader.read(characters);
      }
    } catch (final IOException exception) {
      // the helper is gone
    }

    this.remember(line.toString());
  }

  private void rememberCompleteLines(final char[] characters, final int count, final StringBuilder line) {
    for (int index = 0; index < count; index++) {
      final char character = characters[index];
      if (character == LINE_FEED) {
        this.remember(line.toString());
        line.setLength(0);
      } else if (character != CARRIAGE_RETURN && line.length() < OUTPUT_LINE_CHARACTERS) {
        line.append(character);
      }
    }
  }

  private void remember(final String line) {
    if (line.isBlank()) {
      return;
    }

    LOGGER.debug("The browser helper printed \"{}\" on its output.", line);
    synchronized (this.outputTail) {
      this.outputTail.addLast(line);
      while (this.outputTail.size() > OUTPUT_TAIL_LINES) {
        this.outputTail.removeFirst();
      }
    }
  }

  // the player hears of it as a failure; the browser module does this when it stops
  void endAndClose(final String reason) {
    try {
      this.end(reason, null);
    } finally {
      this.close();
    }
  }

  private void end(final String reason, final @Nullable Throwable cause) {
    final BrowserException failure = this.createEndFailure(reason, cause);
    this.started.completeExceptionally(failure);

    if (this.closing.get() || !this.ended.compareAndSet(false, true)) {
      return;
    }

    this.listener.onEnded(reason, failure);
  }

  private BrowserException createEndFailure(final String reason, final @Nullable Throwable cause) {
    if (cause == null) {
      return new BrowserException(reason);
    }

    return new BrowserException(reason, cause);
  }

  @Override
  public boolean sendMouse(final MouseInput mouse) {
    return this.send(output -> HelperProtocol.writeMouse(output, mouse));
  }

  @Override
  public boolean sendKey(final int action, final String value) {
    final List<String> parts = HelperProtocol.split(value);
    return this.send(output -> this.writeKeys(output, action, parts));
  }

  private void writeKeys(final DataOutputStream output, final int action, final List<String> parts) throws IOException {
    for (final String part : parts) {
      HelperProtocol.writeKey(output, action, part);
    }
  }

  private boolean send(final MessageWriter writer) {
    try {
      this.inputSender.execute(() -> this.write(writer));

      return true;
    } catch (final RejectedExecutionException exception) {
      return this.closing.get();
    }
  }

  private void write(final MessageWriter writer) {
    try {
      synchronized (this.output) {
        writer.write(this.output);
        this.output.flush();
      }
    } catch (final IOException exception) {
      final String message = "Input could not be sent to the browser helper!";
      this.end(message, exception);
    }
  }

  @Override
  public void close() {
    if (!this.closing.compareAndSet(false, true)) {
      return;
    }

    HelperProcesses.unregister(this);
    this.inputSender.shutdownNow();
    synchronized (this.deliveryLock) {
      this.deliveryLock.notifyAll();
    }

    closeQuietly(this.standardInput);
    closeQuietly(this.channel);
    stopProcess(this.process);
    this.join(this.reader);
    this.join(this.delivery);
    this.join(this.drain);
    this.canvas.close();
    deleteFolder(this.folder);
  }

  private static void stopProcess(final Process process) {
    final List<ProcessHandle> descendants = new ArrayList<>();
    try (final Stream<ProcessHandle> currentDescendants = process.descendants()) {
      currentDescendants.forEach(descendants::add);
    }

    try {
      final boolean exited = process.waitFor(STOP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      if (!exited) {
        killAfterTimeout(process, descendants);
      }
    } catch (final InterruptedException exception) {
      process.destroyForcibly();
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    for (final ProcessHandle descendant : descendants) {
      if (descendant.isAlive()) {
        descendant.destroyForcibly();
      }
    }
  }

  private static void killAfterTimeout(final Process process, final List<ProcessHandle> descendants) throws InterruptedException {
    LOGGER.warn("The browser helper did not stop within {} ms and is killed.", STOP_TIMEOUT_MILLISECONDS);
    try (final Stream<ProcessHandle> laterDescendants = process.descendants()) {
      laterDescendants.forEach(descendants::add);
    }

    process.destroyForcibly();
    process.waitFor(KILL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
  }

  private void join(final Thread thread) {
    final Thread caller = Thread.currentThread();
    if (thread.equals(caller)) {
      return;
    }

    try {
      thread.join(STOP_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @VisibleForTesting
  static void closeQuietly(final AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (final Exception exception) {
      LOGGER.debug("{} could not be closed.", closeable, exception);
    }
  }

  @VisibleForTesting
  static void deleteFolder(final Path folder) {
    try {
      IoUtils.deleteRecursively(folder);
    } catch (final FileOperationException exception) {
      LOGGER.warn("The folder {} of the browser session could not be removed.", folder, exception);
    }
  }

  @VisibleForTesting
  List<Thread> getThreads() {
    return List.of(this.reader, this.delivery, this.drain);
  }

  @VisibleForTesting
  boolean isConnected() {
    return this.channel.isOpen();
  }

  String getOutputTail() {
    synchronized (this.outputTail) {
      return String.join(System.lineSeparator(), this.outputTail);
    }
  }

  @VisibleForTesting
  long getCompletedInputCount() {
    return this.inputSender.getCompletedTaskCount();
  }

  boolean isAlive() {
    return this.process.isAlive();
  }

  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  @VisibleForTesting
  Thread getDeliveryThread() {
    return this.delivery;
  }

  @VisibleForTesting
  FrameCanvas getCanvas() {
    return this.canvas;
  }

  @VisibleForTesting
  Process getProcess() {
    return this.process;
  }

  private enum FrameWait {
    UNTIL_FRAME,
    UNTIL_REPEAT_DELAY,
  }

  private enum StartEvent {
    READY,
    LOADED,
    FRAME,
  }

  @FunctionalInterface
  private interface MessageWriter {
    void write(final DataOutputStream output) throws IOException;
  }
}

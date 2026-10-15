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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

// One browser helper process, from its start to its end: the private folder of the session, the helper and the
// processes it starts, the authenticated connection, the page the helper streams, and the input sent to it. open
// creates a folder only the server's user can read, binds a Unix-domain socket in it, starts the helper with its
// configuration on its standard input, and accepts exactly one connection, which must present the random session token
// first; the socket is then deleted. open returns once the page has loaded and its first frame arrived, and fails with
// a BrowserException if the helper fails, exits, or takes longer than the start timeout, leaving nothing behind. A
// reader thread writes every frame region into the FrameCanvas, and a delivery thread hands the newest picture to the
// listener; pictures the listener was too slow for are skipped, never queued. Once the page stops changing, the
// delivery thread hands the same picture over again, every REPEAT_DELAY_MILLISECONDS ms and at most SETTLED_REPEATS
// times: a video pipeline that spreads a big change over several frames, as the map encoder does with its byte budget
// per frame, needs further frames to finish the change, and a page that stands still sends none. Input goes through a
// bounded queue to a writer thread, so a caller such as the server's main thread never waits for the helper. What the
// helper reports for the log, its notices and failed loads, which the page it shows can cause as often as it likes,
// reaches the log of the server within a LogBudget. close ends the helper by closing its standard input and the
// connection, waits a bounded time, then kills it and every process it started, and removes the folder. A helper that
// ends by itself is reported to the listener. The server's end is never a helper that outlives it: a helper exits when
// its standard input ends, which the operating system does when the server process dies.
final class HelperSession implements BrowserSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(HelperSession.class);
  private static final int MAXIMUM_QUEUED_INPUT = 128;
  private static final int OUTPUT_TAIL_LINES = 40;
  private static final int OUTPUT_LINE_CHARACTERS = 1024;
  private static final long STOP_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long KILL_TIMEOUT_MILLISECONDS = 5_000L;
  private static final long EXIT_GRACE_MILLISECONDS = 1_000L;
  private static final byte[] NO_PIXELS = new byte[0];
  private static final long EXIT_POLL_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(10L);
  private static final String SOCKET_NAME = "s";
  // a socket's path has at most 107 characters, and on Linux Chromium binds one 45 characters below the folder of the
  // session, which is its temporary folder: <folder>/org.chromium.Chromium.XXXXXX/SingletonSocket
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

  // How long the delivery thread waits for a new picture before it hands over the last one again.
  static final long REPEAT_DELAY_MILLISECONDS = 50L;

  // How often the delivery thread hands over the last picture again once the page stopped changing.
  static final int SETTLED_REPEATS = 40;

  private final Path folder;
  private final Process process;
  private final Writer standardInput;
  private final SocketChannel channel;
  private final DataOutputStream output;
  private final FrameCanvas canvas;
  private final Listener listener;
  private final ThreadPoolExecutor input;
  private final Deque<String> outputTail;
  private final AtomicBoolean closing;
  private final AtomicBoolean ended;
  private final CompletableFuture<@Nullable Void> started;
  private final Object deliveryLock;
  private final Set<StartEvent> startEvents;
  private final LogBudget logBudget;
  private volatile Thread reader = NOT_STARTED;
  private volatile Thread delivery = NOT_STARTED;
  private volatile Thread drain = NOT_STARTED;
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
    this.input = new ThreadPoolExecutor(
      1,
      1,
      0L,
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
  }

  static HelperSession open(
    final HelperLauncher launcher,
    final Path natives,
    final BrowserSource source,
    final BrowserOptions options,
    final Listener listener
  ) {
    final Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
    return open(launcher, natives, source, options, listener, temporary);
  }

  // Starts a helper whose session folder lies in the given folder, so tests can see what a start leaves behind.
  @VisibleForTesting
  static HelperSession open(
    final HelperLauncher launcher,
    final Path natives,
    final BrowserSource source,
    final BrowserOptions options,
    final Listener listener,
    final Path temporary
  ) {
    final long generation = HelperProcesses.requireOpen();
    final Path folder = createFolder(temporary);
    final Path socket = folder.resolve(SOCKET_NAME);
    final byte[] token = createToken();
    Process process = null;
    HelperSession session = null;
    SocketChannel pendingChannel = null;
    try (final ServerSocketChannel server = bind(socket)) {
      requireShortEnough(launcher.getOs(), folder);
      final Path libraries = launcher.linkLibraries(folder);
      final URI uri = source.getUri();
      final HelperConfiguration configuration = configure(token, socket, natives, folder.resolve(PROFILE_FOLDER), uri, source, options);
      process = startProcess(launcher, folder, libraries);
      final Writer standardInput = sendConfiguration(process, configuration);
      final long timeoutMilliseconds = launcher.getStartTimeoutMilliseconds();
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
      final SocketChannel channel = accept(server, process, deadline);
      pendingChannel = channel;
      Files.deleteIfExists(socket);
      final FrameCanvas canvas = new FrameCanvas(source.getWidth(), source.getHeight());
      session = new HelperSession(folder, process, standardInput, channel, canvas, listener);
      pendingChannel = null;
      if (!HelperProcesses.register(session, generation)) {
        throw new BrowserException("The browser module was stopped while the browser started!");
      }
      session.startThreads(token);
      session.awaitStart(deadline, uri);
      return session;
    } catch (final IOException exception) {
      closeAfterFailure(session, process, pendingChannel, folder);
      final String message = "The browser helper could not be started (%s)!".formatted(exception.getMessage());
      throw new BrowserException(message, exception);
    } catch (final RuntimeException | Error exception) {
      closeAfterFailure(session, process, pendingChannel, folder);
      throw exception;
    }
  }

  // Refuses a folder of a session whose path is too long for the socket Chromium binds in it on Linux, with a reason
  // the operator can act on instead of Chromium's abort.
  @VisibleForTesting
  static void requireShortEnough(final Os os, final Path folder) {
    final String path = folder.toString();
    if (os == Os.LINUX && path.length() + CHROMIUM_SOCKET_TAIL > MAXIMUM_SOCKET_PATH) {
      final int longest =
        MAXIMUM_SOCKET_PATH - CHROMIUM_SOCKET_TAIL - 1 - SessionFolders.PREFIX.length() - SessionFolders.RANDOM_CHARACTERS;
      final String message =
        "The temporary folder %s is too long for the browser: on Linux it may have at most %s characters, so start the server with a shorter java.io.tmpdir (such as /tmp)!".formatted(
          folder.getParent(),
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
    return new HelperConfiguration(
      token,
      socket,
      natives,
      profile,
      uri,
      source.getWidth(),
      source.getHeight(),
      source.getFrameInterval(),
      options.getFrameRate(),
      options.isJavaScriptJit(),
      options.isPrivateNetworks(),
      options.isAutoplay(),
      options.isConfined(),
      Path.of(System.getProperty("user.dir")).toAbsolutePath(),
      options.getRefusedHosts()
    );
  }

  private static Writer sendConfiguration(final Process process, final HelperConfiguration configuration) throws IOException {
    final OutputStream processInput = process.getOutputStream();
    final Writer standardInput = new OutputStreamWriter(processInput, StandardCharsets.UTF_8);
    standardInput.write(configuration.toLine());
    standardInput.write('\n');
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
      closeQuietly(process.getOutputStream());
      stopProcess(process);
    }
    deleteFolder(folder);
  }

  // Creates the folder of a session, readable by the owner only where the file system has POSIX permissions, and names
  // this server in it, see SessionFolders.
  @VisibleForTesting
  static Path createFolder(final Path temporary) {
    return createFolder(temporary, SessionFolders::newName);
  }

  // Creates the folder of a session as createFolder(Path) does, with names drawn from a supplier: a name that is taken
  // is drawn again, MAXIMUM_FOLDER_ATTEMPTS times at most.
  @VisibleForTesting
  static Path createFolder(final Path temporary, final Supplier<String> names) {
    final FileSystem fileSystem = temporary.getFileSystem();
    final boolean supportsPosix = fileSystem.supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    final FileAttribute<?>[] attributes = supportsPosix
      ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_PERMISSIONS)) }
      : new FileAttribute<?>[0];
    try {
      return createFolderUnderDrawnName(temporary, names, attributes);
    } catch (final IOException exception) {
      final String message = "The folder of the browser session cannot be created (%s)!".formatted(exception.getMessage());
      throw new BrowserException(message, exception);
    }
  }

  private static Path createFolderUnderDrawnName(final Path temporary, final Supplier<String> names, final FileAttribute<?>[] attributes)
    throws IOException {
    for (int attempt = 1; attempt <= MAXIMUM_FOLDER_ATTEMPTS; attempt++) {
      final Path candidate = temporary.resolve(names.get());
      try {
        final Path folder = Files.createDirectory(candidate, attributes);
        SessionFolders.ofThisServer().record(folder);
        return folder;
      } catch (final FileAlreadyExistsException exception) {
        LOGGER.debug("The name {} of a browser session's folder is taken, another is drawn.", candidate);
      }
    }
    throw new IOException("Every name drawn was taken!");
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
      server.bind(address, 1);
      server.configureBlocking(false);
      return server;
    } catch (final IOException exception) {
      server.close();
      throw exception;
    }
  }

  private static Process startProcess(final HelperLauncher launcher, final Path folder, final @Nullable Path libraries) throws IOException {
    final ProcessBuilder builder = createProcessBuilder(launcher, folder, libraries);
    return builder.start();
  }

  // Prepares the process of a helper: its command, no environment but what the launcher keeps, the session folder as
  // its working folder, and one stream for its output and its errors.
  @VisibleForTesting
  static ProcessBuilder createProcessBuilder(final HelperLauncher launcher, final Path folder, final @Nullable Path libraries) {
    final List<String> command = launcher.createCommand(folder);
    final ProcessBuilder builder = new ProcessBuilder(command);
    final Map<String, String> environment = builder.environment();
    environment.clear();
    final Map<String, String> kept = launcher.createEnvironment(folder, libraries);
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
      // an interrupted select returns at once without a word, so an interrupt, such as a release's, is checked here
      if (Thread.currentThread().isInterrupted()) {
        throw new BrowserException("Interrupted while the browser helper connected!");
      }
      final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remaining <= 0) {
        throw new BrowserException("The browser helper did not connect in time!");
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
    this.drain = startDaemon("mcav-browser-helper-log", () -> this.drainOutput(processOutput));
    final InputStream rawInput = Channels.newInputStream(this.channel);
    final DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput, INPUT_BUFFER_BYTES));
    this.reader = startDaemon("mcav-browser-reader", () -> this.read(input, token));
    this.delivery = startDaemon("mcav-browser-frames", this::deliverFrames);
  }

  private static Thread startDaemon(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();

    return thread;
  }

  // Creates the thread that writes the input to the helper, which must not keep the JVM alive.
  @VisibleForTesting
  static Thread createInputThread(final Runnable task) {
    final Thread thread = new Thread(task, "mcav-browser-input");
    thread.setDaemon(true);

    return thread;
  }

  private void awaitStart(final long deadline, final URI uri) {
    final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    try {
      this.started.get(Math.max(1L, remaining), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
      throw new BrowserException("Interrupted while starting the browser!", exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = Objects.requireNonNull(exception.getCause(), "A failed start has a cause!");
      final String tail = this.getOutputTail();
      if (!tail.isEmpty()) {
        LOGGER.warn("The browser helper wrote these lines before its start failed.{}{}", System.lineSeparator(), tail);
      }
      final String address = AddressText.describe(uri.toString());
      final String message = "The browser could not open %s (%s)!".formatted(address, cause.getMessage());
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
        this.handle(message);
      }
    } catch (final EOFException exception) {
      this.end(this.describeEnd("The browser helper closed the connection!"), null);
    } catch (final IOException exception) {
      final String reason = "The connection to the browser helper failed (%s)!".formatted(exception.getMessage());
      this.end(this.describeEnd(reason), exception);
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

  // Describes the end of the connection by the exit of the helper if it exits right after: a helper that exits ends its
  // connection, which Windows reports as a reset rather than an end.
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

  // Checks the first message of a helper: a hello of this protocol version with the session token.
  @VisibleForTesting
  static void checkHello(final HelperMessage hello, final byte[] token) throws ProtocolException {
    final int type = hello.getType();
    if (type != HelperProtocol.HELLO) {
      throw new ProtocolException("The browser helper did not introduce itself!");
    }

    final byte[] presented = hello.getToken();
    if (!MessageDigest.isEqual(presented, token)) {
      throw new ProtocolException("The browser helper presented a wrong session token!");
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

  private void handle(final HelperMessage message) throws ProtocolException {
    final int type = message.getType();
    switch (type) {
      case HelperProtocol.FRAME -> {
        final FrameRegion region = message.getRegion();
        this.canvas.apply(region);
        this.onFrame();
      }
      case HelperProtocol.AUDIO -> this.listener.onAudio(message.getSamples());
      case HelperProtocol.READY -> {
        LOGGER.debug("CEF {} runs in the browser helper.", message.getText());
        this.onStartEvent(StartEvent.READY);
      }
      case HelperProtocol.LOADING -> this.onLoading(message);
      case HelperProtocol.LOAD_ERROR -> {
        final String text = message.getText();
        // The helper is untrusted, so the server must check the address again.
        final String url = AddressText.describe(message.getUrl());
        final int code = message.getNumber();
        this.logBudget.log(() -> LOGGER.warn("Page {} failed to load with error {} ({}).", url, text, code), HelperSession::logSkipped);
        final String reason = "The page failed to load with error %s (%s)!".formatted(text, code);
        final BrowserException failure = new BrowserException(reason);
        this.started.completeExceptionally(failure);
      }
      case HelperProtocol.NOTICE -> {
        final String text = message.getText();
        this.logBudget.log(() -> LOGGER.debug("Browser: {}", text), HelperSession::logSkipped);
      }
      case HelperProtocol.FAILURE -> {
        final String text = message.getText();
        final String reason = "The browser failed (%s)!".formatted(text);
        final BrowserException failure = new BrowserException(reason);
        this.started.completeExceptionally(failure);
        this.end(reason, failure);
      }
      default -> {
        final String reason = "The browser helper sent message type %s, which only the server sends!".formatted(type);
        throw new ProtocolException(reason);
      }
    }
  }

  private void onLoading(final HelperMessage message) {
    final boolean loading = message.getNumber() == 1;
    if (!loading) {
      this.onStartEvent(StartEvent.LOADED);
    }
  }

  private static void logSkipped(final long lines) {
    LOGGER.debug("Browser: {} more notices of the page were not logged.", lines);
  }

  private void onFrame() {
    this.requestFrame();
    this.onStartEvent(StartEvent.FRAME);
  }

  // Hands the current picture of the page to the listener again, as soon as the delivery thread is free.
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

  // Waits for a new picture, or, while the last picture is still repeated, at most the repeat delay.
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
      if (character == '\n') {
        this.remember(line.toString());
        line.setLength(0);
      } else if (character != '\r' && line.length() < OUTPUT_LINE_CHARACTERS) {
        line.append(character);
      }
    }
  }

  private void remember(final String line) {
    if (line.isBlank()) {
      return;
    }

    LOGGER.debug("browser helper: {}", line);
    synchronized (this.outputTail) {
      this.outputTail.addLast(line);
      while (this.outputTail.size() > OUTPUT_TAIL_LINES) {
        this.outputTail.removeFirst();
      }
    }
  }

  // Ends the session although its player did not ask for it, so the player hears of it as of a failure, and closes it;
  // this happens when the browser module stops.
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
    if (this.closing.get()) {
      return true;
    }

    try {
      this.input.execute(() -> this.write(writer));
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
      final String reason = "Input could not be sent to the browser helper (%s)!".formatted(exception.getMessage());
      this.end(reason, exception);
    }
  }

  // Ends the helper and everything it started, and removes the folder of the session. Safe to call more than once.
  @Override
  public void close() {
    if (!this.closing.compareAndSet(false, true)) {
      return;
    }

    HelperProcesses.deregister(this);
    this.input.shutdownNow();
    synchronized (this.deliveryLock) {
      this.deliveryLock.notifyAll();
    }
    closeQuietly(this.standardInput);
    closeQuietly(this.channel);
    stopProcess(this.process);
    join(this.reader);
    join(this.delivery);
    join(this.drain);
    this.canvas.close();
    deleteFolder(this.folder);
  }

  // Waits for a helper to exit after its input and connection closed, then kills it and every process it started.
  private static void stopProcess(final Process process) {
    final List<ProcessHandle> descendants = new ArrayList<>();
    try (final Stream<ProcessHandle> started = process.descendants()) {
      started.forEach(descendants::add);
    }
    try {
      final boolean exited = process.waitFor(STOP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      if (!exited) {
        killAfterTimeout(process, descendants);
      }
    } catch (final InterruptedException exception) {
      process.destroyForcibly();
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    }
    for (final ProcessHandle descendant : descendants) {
      if (descendant.isAlive()) {
        descendant.destroyForcibly();
      }
    }
  }

  private static void killAfterTimeout(final Process process, final List<ProcessHandle> descendants) throws InterruptedException {
    LOGGER.warn("The browser helper did not stop within {} ms and is killed.", STOP_TIMEOUT_MILLISECONDS);
    try (final Stream<ProcessHandle> later = process.descendants()) {
      later.forEach(descendants::add);
    }
    process.destroyForcibly();
    process.waitFor(KILL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
  }

  private static void join(final Thread thread) {
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

  // Closes something whose failure to close changes nothing, logging the failure.
  @VisibleForTesting
  static void closeQuietly(final AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (final Exception exception) {
      LOGGER.debug("Failed to close {}.", closeable, exception);
    }
  }

  @VisibleForTesting
  static void deleteFolder(final Path folder) {
    try {
      IoUtils.deleteRecursively(folder);
    } catch (final FileOperationException exception) {
      LOGGER.warn("The folder of the browser session {} could not be removed ({}).", folder, exception.getMessage());
    }
  }

  // Returns the threads that read the helper's connection, hand over the pictures and keep the helper's output, so
  // tests can check how they run and that they end.
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

  // Returns how many input messages the writer thread has handled, sent or failed, so tests can wait for them.
  @VisibleForTesting
  long getHandledInput() {
    return this.input.getCompletedTaskCount();
  }

  boolean isAlive() {
    return this.process.isAlive();
  }

  // Returns the private folder of the session, so tests can check that it goes.
  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  // Returns the thread that hands the pictures to the listener, so tests can interrupt it.
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

  // how long the delivery thread waits for a new picture: until one arrives, or at most the repeat delay while the last
  // picture is still repeated
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
    void write(DataOutputStream output) throws IOException;
  }
}

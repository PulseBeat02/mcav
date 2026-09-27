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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Stream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.IOUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One browser helper process, from its start to its end: the private folder of the session, the helper and the
 * processes it starts, the authenticated connection, the page the helper streams, and the input sent to it.
 *
 * <p>{@link #open} creates a folder only the server's user can read, binds a Unix-domain socket in it, starts the
 * helper with its configuration on its standard input, and accepts exactly one connection, which must present the
 * random session token first; the socket is then deleted. {@link #open} returns once the page has loaded and its first
 * frame arrived, and fails with a {@link PlayerException} if the helper fails, exits, or takes longer than the start
 * timeout, leaving nothing behind.
 *
 * <p>A reader thread writes every frame region into the {@link FrameCanvas}, and a delivery thread hands the newest
 * picture to the listener; pictures the listener was too slow for are skipped, never queued. Once the page stops
 * changing, the delivery thread hands the same picture over again, every {@value #REPEAT_DELAY_MILLIS} ms and at most
 * {@value #SETTLED_REPEATS} times: a video pipeline that spreads a big change over several frames, as the map encoder
 * does with its byte budget per frame, needs further frames to finish the change, and a page that stands still sends
 * none. Input goes through a bounded queue to a writer thread, so a caller such as the server's main thread never
 * waits for the helper. What the helper reports for the log, its notices and failed loads, which the page it shows
 * can cause as often as it likes, reaches the log of the server within a {@link LogBudget}.
 *
 * <p>{@link #close} ends the helper by closing its standard input and the connection, waits a bounded time, then kills
 * it and every process it started, and removes the folder. A helper that ends by itself is reported to the listener.
 * The server's end is never a helper that outlives it: a helper exits when its standard input ends, which the
 * operating system does when the server process dies.
 */
final class HelperSession implements BrowserSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(HelperSession.class);
  private static final int MAX_QUEUED_INPUT = 128;
  private static final int OUTPUT_TAIL_LINES = 40;
  private static final int OUTPUT_LINE_CHARACTERS = 1024;
  private static final long STOP_TIMEOUT_MILLIS = 10_000L;
  private static final long KILL_TIMEOUT_MILLIS = 5_000L;
  private static final long EXIT_GRACE_MILLIS = 1_000L;
  private static final byte[] NO_PIXELS = new byte[0];
  private static final long EXIT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
  private static final String SOCKET_NAME = "s";
  private static final Thread NOT_STARTED = new Thread("mcav-browser-not-started");

  /**
   * How long the delivery thread waits for a new picture before it hands over the last one again.
   */
  static final long REPEAT_DELAY_MILLIS = 50L;

  /**
   * How often the delivery thread hands over the last picture again once the page stopped changing.
   */
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
  // until the threads start, they are a thread that never runs, which close() joins at once
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
      new ArrayBlockingQueue<>(MAX_QUEUED_INPUT),
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

  /**
   * Starts a helper and waits until it shows the page.
   *
   * @param launcher the launcher
   * @param natives  the installed CEF natives
   * @param source   the page and its size
   * @param options  the security profile and frame rate
   * @param listener receives the frames and an unexpected end
   * @return the running session
   * @throws PlayerException if the browser module is stopped or stops meanwhile, or the helper cannot be started, fails,
   *                         or does not show the page in time
   */
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

  /**
   * Starts a helper whose session folder lies in the given folder, so tests can see what a start leaves behind.
   *
   * @param launcher  the launcher
   * @param natives   the installed CEF natives
   * @param source    the page and its size
   * @param options   the security profile and frame rate
   * @param listener  receives the frames and an unexpected end
   * @param temporary the folder the session folder is created in
   * @return the running session
   * @throws PlayerException as {@link #open(HelperLauncher, Path, BrowserSource, BrowserOptions, Listener)}
   */
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
    try (final ServerSocketChannel server = bind(socket)) {
      final Path libraries = launcher.linkLibraries(folder);
      final Path profile = folder.resolve("profile");
      final URI uri = source.getUri();
      final HelperConfiguration configuration = new HelperConfiguration(
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
        options.isAutoplay()
      );
      process = startProcess(launcher, folder, libraries);
      final OutputStream processInput = process.getOutputStream();
      final Writer standardInput = new OutputStreamWriter(processInput, StandardCharsets.UTF_8);
      standardInput.write(configuration.toLine());
      standardInput.write('\n');
      standardInput.flush();
      final long timeoutMillis = launcher.getStartTimeoutMillis();
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      final SocketChannel channel = accept(server, process, deadline);
      Files.deleteIfExists(socket);
      final FrameCanvas canvas = new FrameCanvas(source.getWidth(), source.getHeight());
      session = new HelperSession(folder, process, standardInput, channel, canvas, listener);
      if (!HelperProcesses.register(session, generation)) {
        throw new PlayerException("The browser module was stopped while the browser started");
      }
      session.startThreads(token);
      session.awaitStart(deadline, uri);
      return session;
    } catch (final IOException exception) {
      closeAfterFailure(session, process, folder);
      throw new PlayerException("The browser helper could not be started: " + exception.getMessage(), exception);
    } catch (final RuntimeException | Error failure) {
      closeAfterFailure(session, process, folder);
      throw failure;
    }
  }

  private static void closeAfterFailure(final @Nullable HelperSession session, final @Nullable Process process, final Path folder) {
    if (session != null) {
      // the session owns the process and the folder, and closes them once
      session.close();
      return;
    }
    if (process != null) {
      // the end of its input tells the helper to stop at once, instead of after the timeout of stopProcess
      closeQuietly(process.getOutputStream());
      stopProcess(process);
    }
    deleteFolder(folder);
  }

  /**
   * Creates the folder of a session, readable by the owner only where the file system has POSIX permissions.
   *
   * @param temporary the folder the session folder is created in
   * @return the new folder
   * @throws PlayerException if the folder cannot be created
   */
  @VisibleForTesting
  static Path createFolder(final Path temporary) {
    try {
      final FileSystem fileSystem = temporary.getFileSystem();
      final boolean posix = fileSystem.supportedFileAttributeViews().contains("posix");
      if (posix) {
        return Files.createTempDirectory(
          temporary,
          "mcav-browser-",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        );
      }
      return Files.createTempDirectory(temporary, "mcav-browser-");
    } catch (final IOException exception) {
      throw new PlayerException("The folder of the browser session cannot be created: " + exception.getMessage(), exception);
    }
  }

  /**
   * Creates the secret a helper must present when it connects.
   *
   * @return {@link HelperProtocol#TOKEN_BYTES} random bytes
   */
  @VisibleForTesting
  static byte[] createToken() {
    final SecureRandom random = new SecureRandom();
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    random.nextBytes(token);
    return token;
  }

  /**
   * Binds the Unix-domain socket the helper connects to.
   *
   * @param socket the path of the socket
   * @return the listening socket
   * @throws IOException if the socket cannot be bound, as when the path exists
   */
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

  /**
   * Prepares the process of a helper: its command, no environment but what the launcher keeps, the session folder as
   * its working folder, and one stream for its output and its errors.
   *
   * @param launcher  the launcher
   * @param folder    the folder of the session
   * @param libraries the folder of the libraries the server lacks, or null
   * @return the process builder
   */
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

  private static SocketChannel accept(final ServerSocketChannel server, final Process process, final long deadline) throws IOException {
    try (final Selector selector = Selector.open()) {
      server.register(selector, SelectionKey.OP_ACCEPT);
      while (true) {
        final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
          throw new PlayerException("The browser helper did not connect in time");
        }
        final boolean alive = process.isAlive();
        if (!alive) {
          throw new PlayerException("The browser helper exited with code " + process.exitValue() + " before it connected");
        }
        selector.select(Math.min(remaining, 250L));
        final SocketChannel channel = server.accept();
        if (channel != null) {
          channel.configureBlocking(true);
          return channel;
        }
      }
    }
  }

  private void startThreads(final byte[] token) {
    final InputStream processOutput = this.process.getInputStream();
    this.drain = startDaemon("mcav-browser-helper-log", () -> this.drainOutput(processOutput));
    final InputStream rawInput = Channels.newInputStream(this.channel);
    final DataInputStream in = new DataInputStream(new BufferedInputStream(rawInput, 1 << 16));
    this.reader = startDaemon("mcav-browser-reader", () -> this.read(in, token));
    this.delivery = startDaemon("mcav-browser-frames", this::deliverFrames);
  }

  private static Thread startDaemon(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /**
   * Creates the thread that writes the input to the helper, which must not keep the JVM alive.
   *
   * @param task what the thread runs
   * @return the unstarted thread
   */
  @VisibleForTesting
  static Thread createInputThread(final Runnable task) {
    final Thread thread = new Thread(task, "mcav-browser-input");
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Gets the threads that read the helper's connection, hand over the pictures and keep the helper's output, so tests
   * can check how they run and that they end.
   *
   * @return the reader, delivery and output threads
   */
  @VisibleForTesting
  List<Thread> getThreads() {
    return List.of(this.reader, this.delivery, this.drain);
  }

  /**
   * Gets the private folder of the session, so tests can check that it goes.
   *
   * @return the folder
   */
  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  /**
   * Checks whether the connection to the helper is still open.
   *
   * @return true while it is open
   */
  @VisibleForTesting
  boolean isConnected() {
    return this.channel.isOpen();
  }

  /**
   * Gets the thread that hands the pictures to the listener, so tests can interrupt it.
   *
   * @return the delivery thread
   */
  @VisibleForTesting
  Thread getDeliveryThread() {
    return this.delivery;
  }

  /**
   * Gets the picture of the page, so tests can close it early.
   *
   * @return the canvas
   */
  @VisibleForTesting
  FrameCanvas getCanvas() {
    return this.canvas;
  }

  private void awaitStart(final long deadline, final URI uri) {
    final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    try {
      this.started.get(Math.max(1L, remaining), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
      throw new PlayerException("Interrupted while starting the browser", exception);
    } catch (final ExecutionException exception) {
      // the start only ever fails with a cause
      final Throwable cause = Objects.requireNonNull(exception.getCause(), "A failed start has a cause");
      // a helper that crashed says why only in its output, such as the check of Chromium that failed
      final String tail = this.getOutputTail();
      if (!tail.isEmpty()) {
        LOGGER.warn("The browser helper wrote before its start failed:{}{}", System.lineSeparator(), tail);
      }
      throw new PlayerException("The browser could not open " + AddressText.describe(uri.toString()) + ": " + cause.getMessage(), cause);
    } catch (final TimeoutException exception) {
      final String tail = this.getOutputTail();
      throw new PlayerException("The browser did not show " + AddressText.describe(uri.toString()) + " in time: " + tail, exception);
    }
  }

  /**
   * Reads the messages of the helper until the connection ends.
   *
   * @param in    the stream from the helper
   * @param token the session token the helper must present first
   */
  @VisibleForTesting
  void read(final DataInputStream in, final byte[] token) {
    final byte[][] buffer = { new byte[0] };
    // a region is never larger than the page, so a helper cannot make the server hold more than one page of pixels
    final int pageBytes = this.canvas.getWidth() * this.canvas.getHeight() * HelperProtocol.PIXEL_BYTES;
    try {
      final HelperMessage hello = HelperProtocol.read(in, size -> NO_PIXELS);
      checkHello(hello, token);
      while (true) {
        final HelperMessage message = HelperProtocol.read(in, size -> {
          if (size > pageBytes) {
            // too small, so the protocol refuses the frame before any pixel is read
            return NO_PIXELS;
          }
          if (buffer[0].length < size) {
            buffer[0] = new byte[size];
          }
          return buffer[0];
        });
        this.handle(message);
      }
    } catch (final EOFException exception) {
      this.end(this.describeEnd("The browser helper closed the connection"), null);
    } catch (final IOException exception) {
      this.end(this.describeEnd("The connection to the browser helper failed: " + exception.getMessage()), exception);
    }
  }

  /**
   * Describes the end of the connection by the exit of the helper if it exits right after: a helper that exits ends
   * its connection, which Windows reports as a reset rather than an end.
   *
   * @param connectionEnd how the connection ended, if the helper keeps running
   * @return the description
   */
  private String describeEnd(final String connectionEnd) {
    if (this.closing.get()) {
      return connectionEnd;
    }
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_GRACE_MILLIS);
    while (this.process.isAlive() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(EXIT_POLL_NANOS);
    }
    if (this.process.isAlive()) {
      return connectionEnd;
    }
    return "The browser helper exited with code " + this.process.exitValue();
  }

  /**
   * Checks the first message of a helper: a hello of this protocol version with the session token.
   *
   * @param hello the first message
   * @param token the session token
   * @throws ProtocolException if the message is anything else
   */
  @VisibleForTesting
  static void checkHello(final HelperMessage hello, final byte[] token) throws ProtocolException {
    final int type = hello.getType();
    if (type != HelperProtocol.HELLO) {
      throw new ProtocolException("The browser helper did not introduce itself");
    }
    final byte[] presented = hello.getToken();
    if (!MessageDigest.isEqual(presented, token)) {
      throw new ProtocolException("The browser helper presented a wrong session token");
    }
    final int version = hello.getNumber();
    if (version != HelperProtocol.VERSION) {
      throw new ProtocolException("The browser helper speaks protocol " + version + " instead of " + HelperProtocol.VERSION);
    }
  }

  /**
   * Handles one message of the helper after its hello.
   *
   * @param message the message
   * @throws ProtocolException if the message is not one a helper sends
   */
  @VisibleForTesting
  void handle(final HelperMessage message) throws ProtocolException {
    final int type = message.getType();
    switch (type) {
      case HelperProtocol.FRAME -> {
        final FrameRegion region = message.getRegion();
        this.canvas.apply(region);
        this.onFrame();
      }
      case HelperProtocol.AUDIO -> this.listener.onAudio(message.getSamples());
      case HelperProtocol.READY -> {
        LOGGER.debug("The browser helper runs CEF {}", message.getText());
        this.onStartEvent(StartEvent.READY);
      }
      case HelperProtocol.LOADING -> {
        final boolean loading = message.getNumber() == 1;
        if (!loading) {
          this.onStartEvent(StartEvent.LOADED);
        }
      }
      case HelperProtocol.LOAD_ERROR -> {
        final String text = message.getText();
        // the helper describes the address already; a helper is not trusted to, so the server does it again
        final String url = AddressText.describe(message.getUrl());
        final int code = message.getNumber();
        this.logBudget.log(() -> LOGGER.warn("The browser could not load {}: {} ({})", url, text, code), HelperSession::logSkipped);
        final PlayerException failure = new PlayerException(text + " (" + code + ")");
        this.started.completeExceptionally(failure);
      }
      case HelperProtocol.NOTICE -> {
        final String text = message.getText();
        this.logBudget.log(() -> LOGGER.info("Browser: {}", text), HelperSession::logSkipped);
      }
      case HelperProtocol.FAILURE -> {
        final String text = message.getText();
        final PlayerException failure = new PlayerException(text);
        this.started.completeExceptionally(failure);
        this.end("The browser failed: " + text, failure);
      }
      default -> throw new ProtocolException("The browser helper sent message type " + type + ", which only the server sends");
    }
  }

  /**
   * Logs how many lines of the helper were over the budget of the log.
   *
   * @param lines the number of lines that were not logged
   */
  private static void logSkipped(final long lines) {
    LOGGER.info("Browser: {} more notices of the page were not logged", lines);
  }

  private void onFrame() {
    this.requestFrame();
    this.onStartEvent(StartEvent.FRAME);
  }

  /**
   * Hands the current picture of the page to the listener again, as soon as the delivery thread is free.
   */
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
        fresh = this.awaitFrame(repeatsLeft > 0);
      } catch (final InterruptedException exception) {
        return;
      }
      if (this.closing.get()) {
        return;
      }
      // a new picture starts the repeats over; a repeat only happens while some are left
      repeatsLeft = fresh ? SETTLED_REPEATS : repeatsLeft - 1;
      final ImageBuffer frame = this.canvas.snapshot();
      if (frame == null) {
        // the canvas was closed under a delivery that outlived the close of the session
        return;
      }
      this.listener.onFrame(frame);
    }
  }

  /**
   * Waits for a new picture, or, while the last picture is still repeated, at most the repeat delay.
   *
   * @param repeating true if the last picture is still repeated
   * @return true if a new picture arrived, false if the repeat delay passed or the session is closing
   * @throws InterruptedException if the delivery thread is interrupted
   */
  private boolean awaitFrame(final boolean repeating) throws InterruptedException {
    synchronized (this.deliveryLock) {
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPEAT_DELAY_MILLIS);
      while (!this.frameWaiting && !this.closing.get()) {
        final long remaining = deadline - System.nanoTime();
        if (!repeating) {
          this.deliveryLock.wait();
        } else if (remaining > 0) {
          TimeUnit.NANOSECONDS.timedWait(this.deliveryLock, remaining);
        } else {
          return false;
        }
      }
      final boolean fresh = this.frameWaiting;
      this.frameWaiting = false;
      return fresh;
    }
  }

  /**
   * Keeps the output of the helper, line by line, until it ends.
   *
   * @param stream the output of the helper
   */
  @VisibleForTesting
  void drainOutput(final InputStream stream) {
    final StringBuilder line = new StringBuilder();
    try (final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      final char[] characters = new char[4096];
      int count = reader.read(characters);
      while (count >= 0) {
        for (int index = 0; index < count; index++) {
          final char character = characters[index];
          if (character == '\n') {
            this.remember(line.toString());
            line.setLength(0);
          } else if (character != '\r' && line.length() < OUTPUT_LINE_CHARACTERS) {
            line.append(character);
          }
        }
        count = reader.read(characters);
      }
    } catch (final IOException exception) {
      // the helper is gone
    }
    this.remember(line.toString());
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

  /**
   * Gets the last lines the helper wrote, for error messages.
   *
   * @return the lines
   */
  String getOutputTail() {
    synchronized (this.outputTail) {
      return String.join(System.lineSeparator(), this.outputTail);
    }
  }

  /**
   * Ends the session although its player did not ask for it, so the player hears of it as of a failure, and closes
   * it; this happens when the browser module stops.
   *
   * @param reason why the session ends
   */
  void endAndClose(final String reason) {
    try {
      this.end(reason, null);
    } finally {
      // a listener that fails when it hears of the end must not keep the helper running
      this.close();
    }
  }

  /**
   * Reports an end of the helper that nobody asked for, once.
   *
   * @param reason why the helper ended
   * @param cause  the failure, if one
   */
  private void end(final String reason, final @Nullable Throwable cause) {
    final PlayerException failure = new PlayerException(reason, cause);
    this.started.completeExceptionally(failure);
    if (this.closing.get() || !this.ended.compareAndSet(false, true)) {
      return;
    }
    this.listener.onEnded(reason, failure);
  }

  /**
   * Queues mouse input for the helper.
   *
   * @param mouse the input
   * @return false if the queue was full and the input was dropped
   */
  @Override
  public boolean sendMouse(final MouseInput mouse) {
    return this.send(out -> HelperProtocol.writeMouse(out, mouse));
  }

  /**
   * Queues keyboard input for the helper.
   *
   * @param action {@link HelperProtocol#KEY_PRESS} or {@link HelperProtocol#KEY_TYPE}
   * @param value  the key name or the text
   * @return false if the queue was full and the input was dropped
   */
  @Override
  public boolean sendKey(final int action, final String value) {
    // a text longer than one message goes as several, in order
    final List<String> parts = HelperProtocol.split(value);
    return this.send(out -> {
        for (final String part : parts) {
          HelperProtocol.writeKey(out, action, part);
        }
      });
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
      this.end("Input could not be sent to the browser helper: " + exception.getMessage(), exception);
    }
  }

  /**
   * Gets how many input messages the writer thread has handled, sent or failed, so tests can wait for them.
   *
   * @return the number of handled messages
   */
  @VisibleForTesting
  long getHandledInput() {
    return this.input.getCompletedTaskCount();
  }

  /**
   * Checks whether the helper process still runs.
   *
   * @return true while it runs
   */
  boolean isAlive() {
    return this.process.isAlive();
  }

  /**
   * Gets the helper process, for tests that check its processes end.
   *
   * @return the process
   */
  @VisibleForTesting
  Process getProcess() {
    return this.process;
  }

  /**
   * Ends the helper and everything it started, and removes the folder of the session. Safe to call more than once.
   */
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

  /**
   * Waits for a helper to exit after its input and connection closed, then kills it and every process it started.
   *
   * @param process the helper
   */
  @VisibleForTesting
  static void stopProcess(final Process process) {
    final List<ProcessHandle> descendants = new ArrayList<>();
    try (final Stream<ProcessHandle> started = process.descendants()) {
      started.forEach(descendants::add);
    }
    try {
      final boolean exited = process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      if (!exited) {
        LOGGER.warn("The browser helper did not stop within {} ms and is killed", STOP_TIMEOUT_MILLIS);
        // the helper may have started more processes while it did not stop
        try (final Stream<ProcessHandle> later = process.descendants()) {
          later.forEach(descendants::add);
        }
        process.destroyForcibly();
        process.waitFor(KILL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
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

  private static void join(final Thread thread) {
    final Thread caller = Thread.currentThread();
    // a session thread that closes its own session cannot wait for itself; threads compare by identity
    if (thread.equals(caller)) {
      return;
    }
    try {
      thread.join(STOP_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * Closes something whose failure to close changes nothing, logging the failure.
   *
   * @param closeable the stream or channel
   */
  @VisibleForTesting
  static void closeQuietly(final AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (final Exception exception) {
      LOGGER.debug("Failed to close {}", closeable, exception);
    }
  }

  /**
   * Removes the folder of a session, logging a failure.
   *
   * @param folder the folder
   */
  @VisibleForTesting
  static void deleteFolder(final Path folder) {
    try {
      IOUtils.deleteRecursively(folder);
    } catch (final IOException exception) {
      LOGGER.warn("The folder of the browser session {} could not be removed: {}", folder, exception.getMessage());
    }
  }

  /**
   * What happened during the start.
   */
  private enum StartEvent {
    READY,
    LOADED,
    FRAME,
  }

  /**
   * Writes one message to the helper.
   */
  @FunctionalInterface
  private interface MessageWriter {
    void write(DataOutputStream output) throws IOException;
  }
}

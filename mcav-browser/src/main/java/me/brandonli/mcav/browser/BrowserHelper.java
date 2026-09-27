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
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The browser helper process: the program mcav starts in a JVM of its own to run CEF for one browser.
 *
 * <p>CEF can start only once in a process and stops for good when it shuts down, so a server that disables and enables
 * mcav could never show a browser again if CEF ran inside it. A crash of Chromium would also take the whole server
 * with it. So every browser runs in its own helper process, which is started with whatever JVM options CEF needs,
 * while the server itself needs none.
 *
 * <p>The helper reads its {@link HelperConfiguration} from the first line of its standard input, connects to the
 * server's Unix-domain socket, proves who it is with the session token, starts CEF and streams the page as
 * {@link HelperProtocol} frames while it forwards the server's input to the page. It exits when the server asks it
 * to, when the connection ends, or when its standard input ends, which happens when the server process dies. Once its
 * standard input ended, it halts if it has not stopped within {@value #STOP_DEADLINE_MILLIS} ms, so a browser that hangs
 * never outlives the server. It is not meant to be started by hand.
 */
public final class BrowserHelper {

  private static final long SETTLE_MILLIS = 100L;
  private static final long TAKE_TIMEOUT_MILLIS = 250L;
  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_BAD_CONFIGURATION = 2;

  /**
   * How long the helper may take to stop once its standard input ended, in milliseconds. The server is gone then,
   * and CEF's own shutdown waits up to ten seconds; a browser that hangs longer must not outlive the server.
   */
  static final long STOP_DEADLINE_MILLIS = 20_000L;

  private final HelperConfiguration configuration;
  private final HelperEngine engine;
  private final Halter halter;
  private final long stopDeadlineMillis;
  private final PageCompositor compositor;
  private final CountDownLatch stopped;
  private final AtomicReference<String> stopReason;
  // the mouse buttons the page sees held; only the thread that reads the commands of the server touches them
  private int heldButtons;
  // whether a player pressed a mouse button or a key on the page, after which its sound may reach the server
  private volatile boolean activated;

  /**
   * Constructs a helper that never halts, for tests that run it inside their own JVM.
   *
   * @param configuration the configuration
   * @param engine        the browser
   */
  @VisibleForTesting
  BrowserHelper(final HelperConfiguration configuration, final HelperEngine engine) {
    this(configuration, engine, status -> {}, 0L);
  }

  /**
   * Constructs a helper.
   *
   * @param configuration      the configuration
   * @param engine             the browser
   * @param halter             ends the JVM at once if the helper does not stop in time after its standard input ended
   * @param stopDeadlineMillis how long the helper may take to stop then
   */
  BrowserHelper(final HelperConfiguration configuration, final HelperEngine engine, final Halter halter, final long stopDeadlineMillis) {
    this.configuration = configuration;
    this.engine = engine;
    this.halter = halter;
    this.stopDeadlineMillis = stopDeadlineMillis;
    final int width = configuration.getWidth();
    final int height = configuration.getHeight();
    final int frameInterval = configuration.getFrameInterval();
    this.compositor = new PageCompositor(width, height, frameInterval, SETTLE_MILLIS, System::nanoTime);
    this.stopped = new CountDownLatch(1);
    this.stopReason = new AtomicReference<>("");
  }

  /**
   * Runs the helper and exits the JVM with its result: 0 when it was asked to stop, 1 when it failed, 2 when its
   * configuration was missing or invalid.
   *
   * @param args ignored; the configuration comes from the standard input
   */
  public static void main(final String[] args) {
    // JCEF writes progress to the standard output; the server reads both outputs as the log of the helper
    final PrintStream errors = System.err;
    System.setOut(errors);
    final InputStream standardInput = System.in;
    final Reader reader = new InputStreamReader(standardInput, StandardCharsets.UTF_8);
    final BufferedReader input = new BufferedReader(reader);
    final Runtime runtime = Runtime.getRuntime();
    final int status = runFromInput(input, new CefEngine(), runtime::halt);
    // the JVM must end even while threads of CEF or AWT still run
    System.exit(status);
    throw new AssertionError("System.exit returned");
  }

  /**
   * Reads the configuration line and runs a helper with it.
   *
   * @param input  the standard input
   * @param engine the browser
   * @param halter ends the JVM at once if the helper does not stop in time after its standard input ended
   * @return the exit status
   */
  static int runFromInput(final BufferedReader input, final HelperEngine engine, final Halter halter) {
    final HelperConfiguration configuration;
    try {
      final String line = input.readLine();
      if (line == null) {
        System.err.println("The browser helper got no configuration");
        return EXIT_BAD_CONFIGURATION;
      }
      configuration = HelperConfiguration.fromLine(line);
    } catch (final IOException | IllegalArgumentException exception) {
      System.err.println("The browser helper got an invalid configuration: " + exception.getMessage());
      return EXIT_BAD_CONFIGURATION;
    }
    final BrowserHelper helper = new BrowserHelper(configuration, engine, halter, STOP_DEADLINE_MILLIS);
    return helper.run(input, BrowserHelper::connect);
  }

  /**
   * Connects to the socket of the server.
   *
   * @param socket the path of the socket
   * @return the connected channel
   * @throws IOException if the connection fails; the channel is closed then
   */
  @VisibleForTesting
  static SocketChannel connect(final Path socket) throws IOException {
    final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      channel.connect(address);
    } catch (final IOException exception) {
      channel.close();
      throw exception;
    }
    return channel;
  }

  /**
   * Connects to the server, starts the browser and serves the page until the helper is stopped.
   *
   * @param standardInput the rest of the standard input, whose end means the server is gone
   * @param connector     connects to the socket of the server
   * @return 0 when the helper was asked to stop, 1 when it failed
   */
  int run(final Reader standardInput, final Connector connector) {
    final Path socket = this.configuration.getSocket();
    try (final SocketChannel channel = connector.connect(socket)) {
      final OutputStream rawOutput = Channels.newOutputStream(channel);
      final DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput, 1 << 16));
      final InputStream rawInput = Channels.newInputStream(channel);
      final DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
      final byte[] token = this.configuration.getToken();
      synchronized (output) {
        HelperProtocol.writeHello(output, token);
        output.flush();
      }
      final Reporter reporter = new Reporter(output);
      startDaemon("mcav-browser-helper-stdin", () -> this.watchInput(standardInput));
      startDaemon("mcav-browser-helper-commands", () -> this.readCommands(input));
      startDaemon("mcav-browser-helper-frames", () -> this.sendFrames(output));
      return this.serve(reporter);
    } catch (final IOException exception) {
      System.err.println("The browser helper cannot reach the server: " + exception.getMessage());
      return EXIT_FAILURE;
    }
  }

  private int serve(final Reporter reporter) {
    try {
      this.engine.start(this.configuration, this.compositor, reporter);
    } catch (final Exception exception) {
      final String message = "The browser could not be started: " + exception;
      reporter.onFailure(message);
    }
    try {
      this.stopped.await();
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    }
    this.compositor.close();
    this.engine.stop();
    final String reason = this.stopReason.get();
    System.err.println("The browser helper stops: " + reason);
    return reporter.hasFailed() ? EXIT_FAILURE : 0;
  }

  private static void startDaemon(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Stops the helper.
   *
   * @param reason why it stops, for the log
   */
  void stop(final String reason) {
    this.stopReason.compareAndSet("", reason);
    this.stopped.countDown();
  }

  /**
   * Waits for the end of the standard input, which the server keeps open while it lives, stops the helper, and halts
   * the JVM if the helper has not ended once the stop deadline has passed.
   *
   * @param standardInput the standard input
   */
  void watchInput(final Reader standardInput) {
    try {
      final char[] buffer = new char[256];
      while (standardInput.read(buffer) >= 0) {
        // nothing is expected after the configuration line
      }
    } catch (final IOException exception) {
      // a broken input means the server is gone as well
    }
    this.stop("the standard input ended");
    try {
      Thread.sleep(this.stopDeadlineMillis);
    } catch (final InterruptedException exception) {
      // nothing interrupts this thread but a test; the helper still must not outlive the server
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    }
    // still running: the browser hangs in its start or its shutdown, or a shutdown hook of the JVM does
    System.err.println("The browser helper did not stop within " + this.stopDeadlineMillis + " ms and halts");
    this.halter.halt(EXIT_FAILURE);
  }

  /**
   * Reads the input and close requests of the server and passes the input on to the browser.
   *
   * @param input the stream from the server
   */
  void readCommands(final DataInputStream input) {
    final byte[] noPixels = new byte[0];
    try {
      while (true) {
        final HelperMessage message = HelperProtocol.read(input, size -> noPixels);
        final boolean keepReading = this.handleCommand(message);
        if (!keepReading) {
          return;
        }
      }
    } catch (final EOFException exception) {
      this.stop("the server closed the connection");
    } catch (final IOException exception) {
      this.stop("the connection failed: " + exception.getMessage());
    }
  }

  /**
   * Handles one message from the server.
   *
   * @param message the message
   * @return true to keep reading
   */
  boolean handleCommand(final HelperMessage message) {
    final int type = message.getType();
    return switch (type) {
      case HelperProtocol.MOUSE -> {
        final MouseInput input = message.getMouse();
        if (input.getAction() == HelperProtocol.MOUSE_PRESS) {
          this.activated = true;
        }
        final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.mouse(input, this.heldButtons);
        this.heldButtons = DevToolsInput.heldAfter(input, this.heldButtons);
        this.engine.dispatch(calls);
        yield true;
      }
      case HelperProtocol.KEY -> {
        this.activated = true;
        final String value = message.getText();
        final int action = message.getNumber();
        final List<DevToolsInput.DevToolsCall> calls = action == HelperProtocol.KEY_PRESS
          ? DevToolsInput.pressKey(value)
          : DevToolsInput.typeText(value);
        this.engine.dispatch(calls);
        yield true;
      }
      case HelperProtocol.CLOSE -> {
        this.stop("the server asked to close");
        yield false;
      }
      default -> {
        this.stop("the server sent message type " + type + ", which only a helper sends");
        yield false;
      }
    };
  }

  /**
   * Sends the damaged regions of the page until the helper stops.
   *
   * @param output the stream to the server
   */
  void sendFrames(final DataOutputStream output) {
    final int pageBytes = this.compositor.getPageBytes();
    final byte[] buffer = new byte[pageBytes];
    try {
      while (this.stopped.getCount() > 0) {
        final FrameRegion region = this.compositor.takeDamage(buffer, TAKE_TIMEOUT_MILLIS);
        if (region == null) {
          continue;
        }
        synchronized (output) {
          HelperProtocol.writeFrame(output, region);
          output.flush();
        }
      }
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    } catch (final IOException exception) {
      this.stop("a frame could not be sent: " + exception.getMessage());
    }
  }

  /**
   * Gets the compositor that keeps the picture of the page.
   *
   * @return the compositor
   */
  PageCompositor getCompositor() {
    return this.compositor;
  }

  /**
   * Ends the JVM at once, without running its shutdown hooks.
   */
  @FunctionalInterface
  interface Halter {
    /**
     * Ends the JVM.
     *
     * @param status the exit status
     */
    void halt(int status);
  }

  /**
   * Connects to the socket of the server.
   */
  @FunctionalInterface
  interface Connector {
    /**
     * Connects.
     *
     * @param socket the path of the socket
     * @return the connected channel
     * @throws IOException if the connection fails
     */
    SocketChannel connect(Path socket) throws IOException;
  }

  /**
   * Passes what the browser reports on to the server. A failure also stops the helper.
   */
  final class Reporter implements HelperEvents {

    private final DataOutputStream output;
    private volatile boolean failed;

    Reporter(final DataOutputStream output) {
      this.output = output;
    }

    boolean hasFailed() {
      return this.failed;
    }

    @Override
    public void onReady(final String engineVersion) {
      this.send(out -> HelperProtocol.writeText(out, HelperProtocol.READY, engineVersion));
    }

    @Override
    public void onLoading(final boolean loading) {
      this.send(out -> HelperProtocol.writeLoading(out, loading));
    }

    @Override
    public void onLoadError(final int code, final String text, final String url) {
      this.send(out -> HelperProtocol.writeLoadError(out, code, text, url));
    }

    @Override
    public void onNotice(final String text) {
      this.send(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, text));
    }

    /**
     * Passes the sound of the page on, once a player pressed a mouse button or a key on it, or at once if the options
     * let pages play right away. Chromium holds a page's sound back until then as well; this keeps a page that gets
     * around Chromium's rule, or around the capture script, from playing to the server before anyone touched it.
     */
    @Override
    public void onAudio(final byte[] samples) {
      if (BrowserHelper.this.configuration.isAutoplay() || BrowserHelper.this.activated) {
        this.send(out -> HelperProtocol.writeAudio(out, samples, samples.length));
      }
    }

    @Override
    public void onFailure(final String text) {
      this.failed = true;
      this.send(out -> HelperProtocol.writeText(out, HelperProtocol.FAILURE, text));
      BrowserHelper.this.stop(text);
    }

    private void send(final MessageWriter writer) {
      try {
        synchronized (this.output) {
          writer.write(this.output);
          this.output.flush();
        }
      } catch (final IOException exception) {
        BrowserHelper.this.stop("a message could not be sent: " + exception.getMessage());
      }
    }
  }

  /**
   * Writes one message.
   */
  @FunctionalInterface
  private interface MessageWriter {
    void write(DataOutputStream output) throws IOException;
  }

  /**
   * Gets why the helper stopped.
   *
   * @return the reason, or null while it runs
   */
  @Nullable String getStopReason() {
    final String reason = this.stopReason.get();
    return reason.isEmpty() ? null : reason;
  }
}

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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * A misbehaving browser helper for tests of the server's session: it reads the configuration like the real helper,
 * connects, and then does what the path of the page says:
 *
 * <ul>
 *   <li>{@code /wrong-token}: introduces itself with another token;</li>
 *   <li>{@code /wrong-version}: introduces itself with another protocol version;</li>
 *   <li>{@code /no-hello}: sends a notice first;</li>
 *   <li>{@code /server-message}: introduces itself, is ready and loaded, then sends a close, which only the server
 *   sends;</li>
 *   <li>{@code /wrong-page}: introduces itself and sends a frame of a page of another size;</li>
 *   <li>{@code /oversized}: introduces itself and sends a region larger than the page of the session;</li>
 *   <li>{@code /stall}: shows the page but never reads what the server sends;</li>
 *   <li>{@code /stubborn}: shows the page and ignores the end of its standard input for two minutes;</li>
 *   <li>{@code /frame-first}: sends a frame before it introduces itself;</li>
 *   <li>{@code /hang-up}: shows the page, then closes the connection but keeps running;</li>
 *   <li>{@code /exit}: exits before it connects;</li>
 *   <li>{@code /silent}: never connects, and exits when its standard input ends;</li>
 *   <li>{@code /stubborn-child}: like {@code /stubborn}, and starts a process of its own once its standard input
 *   ended, which runs for a minute;</li>
 *   <li>{@code /child}: shows the page and starts a process of its own at once, which runs for a minute;</li>
 *   <li>{@code /chatty}: writes far more output than a pipe holds before it connects, then shows the page;</li>
 *   <li>{@code /full-page}: shows the page with one region as large as the page;</li>
 *   <li>{@code /exit-later}: shows the page and exits with code 5 half a second later;</li>
 *   <li>{@code /deaf}: shows the page and shuts down the reading side of its connection;</li>
 *   <li>{@code /silent-stubborn}: never connects and ignores the end of its standard input for two minutes;</li>
 *   <li>{@code /noisy}: shows the page, sends {@value #NOISY_LOAD_ERRORS} load errors of addresses with a secret in
 *   their query and {@value #NOISY_NOTICES} notices at once, as a page can make a helper do, and then one frame of
 *   sound.</li>
 * </ul>
 *
 * <p>It then waits until its standard input ends.
 */
public final class RawHelperMain {

  /**
   * The last line {@code /chatty} writes.
   */
  static final String CHATTY_END = "the chatty helper is done";

  /**
   * How many notices {@code /noisy} sends.
   */
  static final int NOISY_NOTICES = 500;

  /**
   * How many load errors {@code /noisy} sends.
   */
  static final int NOISY_LOAD_ERRORS = 50;

  private RawHelperMain() {}

  /**
   * Runs the misbehaving helper.
   *
   * @param args ignored
   * @throws IOException if the connection fails
   */
  public static void main(final String[] args) throws IOException {
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final HelperConfiguration configuration = HelperConfiguration.fromLine(input.readLine());
    if (configuration.getUrl().getPath().equals("/exit")) {
      System.exit(4);
    }
    if (configuration.getUrl().getPath().equals("/silent")) {
      while (input.read() >= 0) {
        // the server gives up on this helper by closing its input
      }
      System.exit(0);
    }
    if (configuration.getUrl().getPath().equals("/silent-stubborn")) {
      waitTwoMinutes();
    }
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(configuration.getSocket()));
    final DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
    final String path = configuration.getUrl().getPath();
    final byte[] token = configuration.getToken();
    switch (path) {
      case "/wrong-token" -> {
        token[0] ^= 1;
        HelperProtocol.writeHello(out, token);
      }
      case "/wrong-version" -> {
        out.writeByte(HelperProtocol.HELLO);
        out.writeInt(HelperProtocol.TOKEN_BYTES + 2);
        out.write(token);
        out.writeShort(HelperProtocol.VERSION + 1);
      }
      case "/no-hello" -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "hi");
      case "/server-message" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(out, false);
        out.flush();
        sleep();
        HelperProtocol.writeClose(out);
      }
      case "/stall", "/stubborn", "/stubborn-child", "/hang-up", "/child", "/chatty", "/exit-later", "/deaf" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(out, false);
        out.flush();
        if (path.equals("/hang-up")) {
          channel.close();
        }
        if (path.equals("/deaf")) {
          // the server can no longer write to this helper, while the helper keeps its own side open
          channel.shutdownInput();
        }
        if (path.equals("/chatty")) {
          // a pipe holds 64 KiB on Linux: unless the server reads the output, this helper blocks here
          final String line = "x".repeat(1023);
          for (int count = 0; count < 256; count++) {
            System.out.println(line);
          }
          System.out.println(CHATTY_END);
          System.out.flush();
        }
        if (path.equals("/exit-later")) {
          sleep();
          System.exit(5);
        }
        if (path.equals("/child")) {
          startChild();
        }
        if (path.equals("/stubborn-child")) {
          startChildWhenTheInputEnds(input);
        }
        if (path.equals("/stubborn") || path.equals("/stubborn-child")) {
          waitTwoMinutes();
        }
      }
      case "/noisy" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(out, false);
        // the load errors come first, so the budget of the log passes some of them
        for (int count = 0; count < NOISY_LOAD_ERRORS; count++) {
          HelperProtocol.writeLoadError(out, -2, "noise", "https://example.com/noise/" + count + "?token=secret");
        }
        for (int count = 0; count < NOISY_NOTICES; count++) {
          HelperProtocol.writeText(out, HelperProtocol.NOTICE, "noise " + count);
        }
        // the sound arrives after everything above, so a test knows the server read it all
        HelperProtocol.writeAudio(out, new byte[HelperProtocol.AUDIO_FRAME_BYTES], HelperProtocol.AUDIO_FRAME_BYTES);
      }
      case "/full-page" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        final int width = configuration.getWidth();
        final int height = configuration.getHeight();
        final byte[] pixels = new byte[width * height * 4];
        java.util.Arrays.fill(pixels, (byte) 7);
        HelperProtocol.writeFrame(out, new FrameRegion(width, height, 0, 0, width, height, pixels));
        HelperProtocol.writeLoading(out, false);
      }
      case "/frame-first" -> HelperProtocol.writeFrame(
        out,
        new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4])
      );
      case "/wrong-page" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth() + 1, configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
      }
      case "/oversized" -> {
        HelperProtocol.writeHello(out, token);
        final int width = configuration.getWidth() + 1;
        final int height = configuration.getHeight();
        HelperProtocol.writeFrame(out, new FrameRegion(width, height, 0, 0, width, height, new byte[width * height * 4]));
      }
      default -> throw new IllegalArgumentException("Unknown scenario " + path);
    }
    out.flush();
    while (input.read() >= 0) {
      // wait until the server is done with this helper
    }
    channel.close();
  }

  /**
   * Starts a process of this helper two seconds after its standard input ended, while the server waits for the helper
   * to stop: a JVM that waits for a gate that never opens, for a minute.
   *
   * @param input the standard input
   */
  private static void startChildWhenTheInputEnds(final BufferedReader input) {
    final Thread watcher = new Thread(() -> {
      try {
        awaitTheEnd(input);
        // after the server listed the processes of this helper, while it waits for it to stop
        Thread.sleep(2_000L);
        startChild();
      } catch (final IOException exception) {
        throw new java.io.UncheckedIOException(exception);
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    watcher.setDaemon(true);
    watcher.start();
  }

  /**
   * Starts a process of this helper: a JVM that waits for a gate that never opens, for a minute.
   *
   * @throws IOException if the process cannot be started
   */
  private static void startChild() throws IOException {
    final String program = ProcessHandle.current().info().command().orElse("java");
    final String classPath = System.getProperty("java.class.path");
    final String gate = System.getProperty("java.io.tmpdir") + File.separator + "never-opens";
    new ProcessBuilder(program, "-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate, "-cp", classPath, GatedHelperMain.class.getName())
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start();
  }

  /**
   * Keeps running for two minutes, whatever happens to the standard input: long enough for any test that kills this
   * helper, and short enough that a test runner killed meanwhile leaves no helper behind for long.
   */
  private static void waitTwoMinutes() {
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
    while (System.nanoTime() < deadline) {
      sleep();
    }
    System.exit(0);
  }

  private static void awaitTheEnd(final BufferedReader input) throws IOException {
    while (input.read() >= 0) {
      // the server is still using this helper
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(500L);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}

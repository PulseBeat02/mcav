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
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

// A misbehaving browser helper for the session tests: the path of the page picks the misbehavior.
public final class RawHelperMain {

  static final String CHATTY_END = "the chatty helper is done";

  static final int NOISY_NOTICES = 500;

  static final int NOISY_LOAD_ERRORS = 50;

  private RawHelperMain() {}

  public static void main(final String[] args) throws IOException {
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final HelperConfiguration configuration = HelperConfiguration.fromLine(input.readLine());
    if (configuration.getUrl().getPath().equals("/exit")) {
      System.exit(4);
    }
    if (configuration.getUrl().getPath().equals("/silent")) {
      while (input.read() >= 0) {}
      System.exit(0);
    }
    if (configuration.getUrl().getPath().equals("/silent-stubborn")) {
      waitTwoMinutes();
    }
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(configuration.getSocket()));
    final DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
    final String path = configuration.getUrl().getPath();
    final byte[] token = configuration.getToken();
    switch (path) {
      case "/wrong-token" -> {
        token[0] ^= 1;
        HelperProtocol.writeHello(output, token);
      }
      case "/wrong-version" -> {
        output.writeByte(HelperProtocol.HELLO);
        output.writeInt(HelperProtocol.TOKEN_BYTES + 2);
        output.write(token);
        output.writeShort(HelperProtocol.VERSION + 1);
      }
      case "/no-hello" -> HelperProtocol.writeText(output, HelperProtocol.NOTICE, "hi");
      case "/server-message" -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(output, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(output, false);
        output.flush();
        sleep();
        HelperProtocol.writeClose(output);
      }
      case "/stall", "/stubborn", "/stubborn-child", "/hang-up", "/child", "/output-child", "/chatty", "/exit-later", "/deaf" -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(output, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(output, false);
        output.flush();
        misbehaveAfterStarting(path, channel, input);
      }
      case "/noisy" -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(output, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(output, false);
        writeNoise(output);
        output.flush();
        sleep();
        sleep();
        sleep();
        HelperProtocol.writeText(output, HelperProtocol.NOTICE, "noise after a pause");
        HelperProtocol.writeAudio(output, new byte[HelperProtocol.AUDIO_FRAME_BYTES], HelperProtocol.AUDIO_FRAME_BYTES);
      }
      case "/full-page" -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, "raw");
        final int width = configuration.getWidth();
        final int height = configuration.getHeight();
        final byte[] pixels = new byte[width * height * 4];
        Arrays.fill(pixels, (byte) 7);
        HelperProtocol.writeFrame(output, new FrameRegion(width, height, 0, 0, width, height, pixels));
        HelperProtocol.writeLoading(output, false);
      }
      case "/frame-first" -> HelperProtocol.writeFrame(
        output,
        new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4])
      );
      case "/wrong-page" -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeFrame(
          output,
          new FrameRegion(configuration.getWidth() + 1, configuration.getHeight(), 0, 0, 1, 1, new byte[4])
        );
      }
      case "/oversized" -> {
        HelperProtocol.writeHello(output, token);
        final int width = configuration.getWidth() + 1;
        final int height = configuration.getHeight();
        HelperProtocol.writeFrame(output, new FrameRegion(width, height, 0, 0, width, height, new byte[width * height * 4]));
      }
      default -> {
        final String message = "Scenario %s is unknown!".formatted(path);
        throw new IllegalArgumentException(message);
      }
    }
    output.flush();
    while (input.read() >= 0) {}
    channel.close();
  }

  private static void misbehaveAfterStarting(final String path, final SocketChannel channel, final BufferedReader input)
    throws IOException {
    if (path.equals("/hang-up")) {
      channel.close();
    }
    if (path.equals("/deaf")) {
      channel.shutdownInput();
    }
    if (path.equals("/chatty")) {
      // more than a pipe holds (64 KiB on Linux): blocks unless the server reads the output
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
      startChild(ProcessBuilder.Redirect.DISCARD);
    }
    if (path.equals("/output-child")) {
      startChild(ProcessBuilder.Redirect.INHERIT);
    }
    if (path.equals("/stubborn-child")) {
      startChildWhenTheInputEnds(input);
    }
    if (path.equals("/stubborn") || path.equals("/stubborn-child")) {
      waitTwoMinutes();
    }
  }

  private static void writeNoise(final DataOutputStream output) throws IOException {
    for (int count = 0; count < NOISY_LOAD_ERRORS; count++) {
      HelperProtocol.writeLoadError(output, -2, "noise", "https://example.com/noise/" + count + "?token=secret");
    }
    for (int count = 0; count < NOISY_NOTICES; count++) {
      HelperProtocol.writeText(output, HelperProtocol.NOTICE, "noise " + count);
    }
  }

  // the child starts while the server waits for this helper to stop
  private static void startChildWhenTheInputEnds(final BufferedReader input) {
    final Thread watcher = new Thread(() -> startChildAfterTheEnd(input));
    watcher.setDaemon(true);
    watcher.start();
  }

  private static void startChildAfterTheEnd(final BufferedReader input) {
    try {
      awaitTheEnd(input);
      Thread.sleep(2_000L);
      startChild(ProcessBuilder.Redirect.DISCARD);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void startChild(final ProcessBuilder.Redirect output) throws IOException {
    final String program = ProcessHandle.current().info().command().orElse("java");
    final String classPath = System.getProperty("java.class.path");
    final String gate = System.getProperty("java.io.tmpdir") + File.separator + "never-opens";
    new ProcessBuilder(program, "-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate, "-cp", classPath, GatedHelperMain.class.getName())
      .redirectErrorStream(true)
      .redirectOutput(output)
      .start();
  }

  // outlasts any test that kills this helper, but leaves no orphan behind for long
  private static void waitTwoMinutes() {
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (System.nanoTime() < deadline) {
      sleep();
    }
    System.exit(0);
  }

  private static void awaitTheEnd(final BufferedReader input) throws IOException {
    while (input.read() >= 0) {}
  }

  private static void sleep() {
    try {
      Thread.sleep(500L);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}

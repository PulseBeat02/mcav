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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

// A misbehaving browser helper for the session tests: the path of the page picks the misbehavior.
public final class RawHelperMain {

  private static final int EARLY_EXIT_CODE = 4;
  private static final int LATE_EXIT_CODE = 5;

  private static final String READY_TEXT = "raw";

  private static final int CHATTY_LINE_CHARACTERS = 1023;
  private static final int CHATTY_LINES = 256;

  private static final int LOAD_ERROR_CODE = -2;

  private static final long CHILD_DELAY_MILLISECONDS = 2_000L;
  private static final long STUBBORN_MINUTES = 2L;
  private static final long PAUSE_MILLISECONDS = 500L;

  private static final String NEVER_OPENING_GATE = "never-opens";

  private static final String CLASS_PATH_PROPERTY = "java.class.path";
  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";

  static final String EXIT_PATH = "/exit";
  static final String SILENT_PATH = "/silent";
  static final String SILENT_STUBBORN_PATH = "/silent-stubborn";
  static final String WRONG_TOKEN_PATH = "/wrong-token";
  static final String WRONG_VERSION_PATH = "/wrong-version";
  static final String NO_HELLO_PATH = "/no-hello";
  static final String SERVER_MESSAGE_PATH = "/server-message";
  static final String STALL_PATH = "/stall";
  static final String STUBBORN_PATH = "/stubborn";
  static final String STUBBORN_CHILD_PATH = "/stubborn-child";
  static final String HANG_UP_PATH = "/hang-up";
  static final String CHILD_PATH = "/child";
  static final String OUTPUT_CHILD_PATH = "/output-child";
  static final String CHATTY_PATH = "/chatty";
  static final String EXIT_LATER_PATH = "/exit-later";
  static final String DEAF_PATH = "/deaf";
  static final String NOISY_PATH = "/noisy";
  static final String FULL_PAGE_PATH = "/full-page";
  static final String FRAME_FIRST_PATH = "/frame-first";
  static final String WRONG_PAGE_PATH = "/wrong-page";
  static final String OVERSIZED_PATH = "/oversized";
  static final String UNLOADED_PATH = "/unloaded";
  static final String UNPAINTED_PATH = "/unpainted";

  static final String CHATTY_END = "the chatty helper is done";

  static final int NOISY_NOTICES = 500;
  static final int NOISY_LOAD_ERRORS = 50;

  private RawHelperMain() {}

  public static void main(final String[] args) throws IOException {
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final HelperConfiguration configuration = HelperConfiguration.fromLine(input.readLine());
    if (configuration.getUrl().getPath().equals(EXIT_PATH)) {
      System.exit(EARLY_EXIT_CODE);
    }

    if (configuration.getUrl().getPath().equals(SILENT_PATH)) {
      awaitTheEnd(input);
      System.exit(0);
    }

    if (configuration.getUrl().getPath().equals(SILENT_STUBBORN_PATH)) {
      exitAfterTwoMinutes();
    }

    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(configuration.getSocket()));
    final DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
    final String path = configuration.getUrl().getPath();
    final byte[] token = configuration.getToken();
    switch (path) {
      case WRONG_TOKEN_PATH -> {
        token[0] ^= 1;
        HelperProtocol.writeHello(output, token);
      }
      case WRONG_VERSION_PATH -> {
        output.writeByte(HelperProtocol.HELLO);
        output.writeInt(HelperProtocol.TOKEN_BYTES + 2);
        output.write(token);
        output.writeShort(HelperProtocol.VERSION + 1);
      }
      case NO_HELLO_PATH -> HelperProtocol.writeText(output, HelperProtocol.NOTICE, "hi");
      case SERVER_MESSAGE_PATH -> {
        writeStartedSession(output, token, configuration);
        output.flush();
        sleep();
        HelperProtocol.writeClose(output);
      }
      case
        STALL_PATH,
        STUBBORN_PATH,
        STUBBORN_CHILD_PATH,
        HANG_UP_PATH,
        CHILD_PATH,
        OUTPUT_CHILD_PATH,
        CHATTY_PATH,
        EXIT_LATER_PATH,
        DEAF_PATH -> {
        writeStartedSession(output, token, configuration);
        output.flush();
        misbehaveAfterStarting(path, channel, input);
      }
      case NOISY_PATH -> {
        writeStartedSession(output, token, configuration);
        writeNoise(output);
        output.flush();
        sleep();
        sleep();
        sleep();
        HelperProtocol.writeText(output, HelperProtocol.NOTICE, "noise after a pause");
        HelperProtocol.writeAudio(output, new byte[HelperProtocol.AUDIO_FRAME_BYTES], HelperProtocol.AUDIO_FRAME_BYTES);
      }
      case FULL_PAGE_PATH -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, READY_TEXT);
        final int width = configuration.getWidth();
        final int height = configuration.getHeight();
        final byte[] pixels = new byte[width * height * HelperProtocol.PIXEL_BYTES];
        Arrays.fill(pixels, (byte) 7);
        HelperProtocol.writeFrame(output, new FrameRegion(width, height, 0, 0, width, height, pixels));
        HelperProtocol.writeLoading(output, false);
      }
      case FRAME_FIRST_PATH -> writeFirstPixel(output, configuration);
      case WRONG_PAGE_PATH -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeFrame(
          output,
          new FrameRegion(configuration.getWidth() + 1, configuration.getHeight(), 0, 0, 1, 1, new byte[HelperProtocol.PIXEL_BYTES])
        );
      }
      case OVERSIZED_PATH -> {
        HelperProtocol.writeHello(output, token);
        final int width = configuration.getWidth() + 1;
        final int height = configuration.getHeight();
        HelperProtocol.writeFrame(
          output,
          new FrameRegion(width, height, 0, 0, width, height, new byte[width * height * HelperProtocol.PIXEL_BYTES])
        );
      }
      case UNLOADED_PATH -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, READY_TEXT);
        writeFirstPixel(output, configuration);
      }
      case UNPAINTED_PATH -> {
        HelperProtocol.writeHello(output, token);
        HelperProtocol.writeText(output, HelperProtocol.READY, READY_TEXT);
        HelperProtocol.writeLoading(output, false);
      }
      default -> {
        final String message = "Scenario %s is unknown!".formatted(path);
        throw new IllegalArgumentException(message);
      }
    }

    output.flush();
    awaitTheEnd(input);
    channel.close();
  }

  private static void writeStartedSession(final DataOutputStream output, final byte[] token, final HelperConfiguration configuration)
    throws IOException {
    HelperProtocol.writeHello(output, token);
    HelperProtocol.writeText(output, HelperProtocol.READY, READY_TEXT);
    writeFirstPixel(output, configuration);
    HelperProtocol.writeLoading(output, false);
  }

  private static void writeFirstPixel(final DataOutputStream output, final HelperConfiguration configuration) throws IOException {
    final int width = configuration.getWidth();
    final int height = configuration.getHeight();
    final byte[] pixels = new byte[HelperProtocol.PIXEL_BYTES];
    final FrameRegion pixel = new FrameRegion(width, height, 0, 0, 1, 1, pixels);
    HelperProtocol.writeFrame(output, pixel);
  }

  private static void misbehaveAfterStarting(final String path, final SocketChannel channel, final BufferedReader input)
    throws IOException {
    if (path.equals(HANG_UP_PATH)) {
      channel.close();
    }

    if (path.equals(DEAF_PATH)) {
      channel.shutdownInput();
    }

    if (path.equals(CHATTY_PATH)) {
      // more than a pipe holds (64 KiB on Linux): blocks unless the server reads the output
      final String line = "x".repeat(CHATTY_LINE_CHARACTERS);
      for (int count = 0; count < CHATTY_LINES; count++) {
        System.out.println(line);
      }
      System.out.println(CHATTY_END);
      System.out.flush();
    }

    if (path.equals(EXIT_LATER_PATH)) {
      sleep();
      System.exit(LATE_EXIT_CODE);
    }

    if (path.equals(CHILD_PATH)) {
      startChild(ProcessBuilder.Redirect.DISCARD);
    }

    if (path.equals(OUTPUT_CHILD_PATH)) {
      startChild(ProcessBuilder.Redirect.INHERIT);
    }

    if (path.equals(STUBBORN_CHILD_PATH)) {
      startChildWhenTheInputEnds(input);
    }

    if (path.equals(STUBBORN_PATH) || path.equals(STUBBORN_CHILD_PATH)) {
      exitAfterTwoMinutes();
    }
  }

  private static void writeNoise(final DataOutputStream output) throws IOException {
    for (int count = 0; count < NOISY_LOAD_ERRORS; count++) {
      HelperProtocol.writeLoadError(output, LOAD_ERROR_CODE, "noise", "https://example.com/noise/%s?token=secret".formatted(count));
    }

    for (int count = 0; count < NOISY_NOTICES; count++) {
      HelperProtocol.writeText(output, HelperProtocol.NOTICE, "noise %s".formatted(count));
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
      Thread.sleep(CHILD_DELAY_MILLISECONDS);
      startChild(ProcessBuilder.Redirect.DISCARD);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "The start of the child helper was interrupted!";
      throw new IllegalStateException(message, exception);
    }
  }

  private static void startChild(final ProcessBuilder.Redirect output) throws IOException {
    final String program = ProcessHandle.current().info().command().orElse("java");
    final String classPath = System.getProperty(CLASS_PATH_PROPERTY);
    final String gate = Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY), NEVER_OPENING_GATE).toString();
    new ProcessBuilder(program, "-D%s=%s".formatted(GatedHelperMain.GATE_PROPERTY, gate), "-cp", classPath, GatedHelperMain.class.getName())
      .redirectErrorStream(true)
      .redirectOutput(output)
      .start();
  }

  // outlasts any test that kills this helper, but leaves no orphan behind for long
  private static void exitAfterTwoMinutes() {
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(STUBBORN_MINUTES);
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
      Thread.sleep(PAUSE_MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "The helper was interrupted!";
      throw new IllegalStateException(message, exception);
    }
  }
}

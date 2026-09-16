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
package me.brandonli.mcav.sandbox.e2e;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A Minecraft server running as a child process, with its console output collected line by line and its console
 * input used to send commands. Closing it always ends the process.
 */
final class ServerProcess implements AutoCloseable {

  private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);
  private static final long POLL_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final Process process;
  private final List<String> lines;
  private final Thread reader;

  private ServerProcess(final Process process) {
    this.process = process;
    this.lines = new ArrayList<>();
    final Thread.Builder.OfPlatform threadBuilder = Thread.ofPlatform();
    threadBuilder.daemon();
    threadBuilder.name("server-output");
    this.reader = threadBuilder.start(this::readOutput);
  }

  /**
   * Starts a server without a display: {@code DISPLAY} and {@code WAYLAND_DISPLAY} are removed from its environment,
   * as on a real headless server, and the command is expected to run AWT headless.
   *
   * @param directory the server directory
   * @param command   the Java command that starts the server
   * @return the running server
   * @throws IOException if the process cannot be started
   */
  static ServerProcess start(final Path directory, final List<String> command) throws IOException {
    final ProcessBuilder builder = new ProcessBuilder(command);
    final File directoryFile = directory.toFile();
    builder.directory(directoryFile);
    builder.redirectErrorStream(true);

    final Map<String, String> environment = builder.environment();
    environment.remove("DISPLAY");
    environment.remove("WAYLAND_DISPLAY");

    final Process started = builder.start();
    return new ServerProcess(started);
  }

  private void readOutput() {
    final InputStream serverOutput = this.process.getInputStream();
    final InputStreamReader streamReader = new InputStreamReader(serverOutput, StandardCharsets.UTF_8);
    try (final BufferedReader bufferedReader = new BufferedReader(streamReader)) {
      String line = bufferedReader.readLine();
      while (line != null) {
        System.out.println("[server] " + line);
        this.addLine(line);
        line = bufferedReader.readLine();
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      this.addLine("[output closed: " + message + "]");
    }
  }

  private void addLine(final String line) {
    synchronized (this.lines) {
      this.lines.add(line);
      this.lines.notifyAll();
    }
  }

  /**
   * Sends a command to the server console.
   *
   * @param command the command, without a leading slash
   * @throws UncheckedIOException if the console of the server is closed
   */
  void command(final String command) {
    final String separator = System.lineSeparator();
    final String consoleLine = command + separator;
    final byte[] bytes = consoleLine.getBytes(StandardCharsets.UTF_8);
    final OutputStream consoleInput = this.process.getOutputStream();
    try {
      consoleInput.write(bytes);
      consoleInput.flush();
    } catch (final IOException exception) {
      throw new UncheckedIOException("The server console is closed", exception);
    }
  }

  /**
   * Waits until the server prints a line that matches, counting only lines printed from a given index on.
   *
   * @param fromLine the index of the first line to look at, see {@link #getLineCount()}
   * @param matcher  decides which line is wanted
   * @param timeout  how long to wait
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws AssertionError       if no line matches in time or the server exits first
   */
  void awaitLine(final int fromLine, final Predicate<String> matcher, final Duration timeout) throws InterruptedException {
    final long startNanos = System.nanoTime();
    final long timeoutNanos = timeout.toNanos();
    final long deadline = startNanos + timeoutNanos;
    synchronized (this.lines) {
      int nextLine = fromLine;
      while (true) {
        final int lineCount = this.lines.size();
        final Optional<String> match = this.findMatch(nextLine, lineCount, matcher);
        if (match.isPresent()) {
          return;
        }
        nextLine = lineCount;
        this.waitForMoreOutput(deadline, timeout);
      }
    }
  }

  private Optional<String> findMatch(final int fromLine, final int toLine, final Predicate<String> matcher) {
    for (int index = fromLine; index < toLine; index++) {
      final String line = this.lines.get(index);
      if (matcher.test(line)) {
        return Optional.of(line);
      }
    }
    return Optional.empty();
  }

  /**
   * Waits for the next line while holding the monitor of the lines, at most until the deadline.
   */
  private void waitForMoreOutput(final long deadline, final Duration timeout) throws InterruptedException {
    final long now = System.nanoTime();
    final long remaining = deadline - now;
    final boolean running = this.process.isAlive() || this.reader.isAlive();
    if (remaining <= 0 || !running) {
      throw new AssertionError("The server did not print the expected line within " + timeout);
    }
    final long waitNanos = Math.min(remaining, POLL_INTERVAL_NANOS);
    TimeUnit.NANOSECONDS.timedWait(this.lines, waitNanos);
  }

  /**
   * Gets the number of lines printed so far.
   *
   * @return the line count
   */
  int getLineCount() {
    synchronized (this.lines) {
      return this.lines.size();
    }
  }

  /**
   * Gets every line printed so far.
   *
   * @return a copy of the output
   */
  List<String> getLines() {
    synchronized (this.lines) {
      return List.copyOf(this.lines);
    }
  }

  /**
   * Waits for the server to exit and for its output to be read completely.
   *
   * @param timeout how long to wait
   * @return the exit code
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws AssertionError       if the server is still running after the timeout
   */
  int awaitExit(final Duration timeout) throws InterruptedException {
    final long millis = timeout.toMillis();
    final boolean exited = this.process.waitFor(millis, TimeUnit.MILLISECONDS);
    if (!exited) {
      throw new AssertionError("The server did not stop within " + timeout);
    }
    this.reader.join(millis);
    return this.process.exitValue();
  }

  /**
   * Ends the server if it still runs: first politely, then forcibly after {@code DESTROY_TIMEOUT}. An interrupt while
   * waiting ends the server forcibly at once and keeps the interrupt flag of the thread set.
   */
  @Override
  public void close() {
    if (!this.process.isAlive()) {
      return;
    }
    this.process.destroy();
    final long millis = DESTROY_TIMEOUT.toMillis();
    try {
      final boolean exited = this.process.waitFor(millis, TimeUnit.MILLISECONDS);
      if (!exited) {
        this.process.destroyForcibly();
      }
    } catch (final InterruptedException exception) {
      this.process.destroyForcibly();
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }
}

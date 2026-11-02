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
package me.brandonli.mcav.plugin.e2e;

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
import java.util.stream.Stream;

final class ServerProcess implements AutoCloseable {

  private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);
  private static final long POLL_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);

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

  // without DISPLAY and WAYLAND_DISPLAY, as on a real headless server
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

  // an interrupt while waiting ends the server forcibly at once
  @Override
  public void close() {
    if (!this.process.isAlive()) {
      return;
    }

    this.process.destroy();
    final long milliseconds = DESTROY_TIMEOUT.toMillis();
    try {
      final boolean exited = this.process.waitFor(milliseconds, TimeUnit.MILLISECONDS);
      if (!exited) {
        this.process.destroyForcibly();
      }
    } catch (final InterruptedException exception) {
      this.process.destroyForcibly();
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  private void readOutput() {
    final InputStream serverOutput = this.process.getInputStream();
    final InputStreamReader streamReader = new InputStreamReader(serverOutput, StandardCharsets.UTF_8);
    try (final BufferedReader bufferedReader = new BufferedReader(streamReader)) {
      String line = bufferedReader.readLine();
      while (line != null) {
        System.out.println("[server] %s".formatted(line));
        this.addLine(line);
        line = bufferedReader.readLine();
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      this.addLine("[output closed: %s]".formatted(message));
    }
  }

  private void addLine(final String line) {
    synchronized (this.lines) {
      this.lines.add(line);
      this.lines.notifyAll();
    }
  }

  void command(final String command) {
    final String separator = System.lineSeparator();
    final String consoleLine = "%s%s".formatted(command, separator);
    final byte[] bytes = consoleLine.getBytes(StandardCharsets.UTF_8);
    final OutputStream consoleInput = this.process.getOutputStream();
    try {
      consoleInput.write(bytes);
      consoleInput.flush();
    } catch (final IOException exception) {
      final String message = "The server console is closed!";
      throw new UncheckedIOException(message, exception);
    }
  }

  String awaitLine(final int fromLine, final Predicate<String> matcher, final Duration timeout) throws InterruptedException {
    final long startNanoseconds = System.nanoTime();
    final long timeoutNanoseconds = timeout.toNanos();
    final long deadline = startNanoseconds + timeoutNanoseconds;
    synchronized (this.lines) {
      return this.awaitLineUntil(fromLine, matcher, deadline, timeout);
    }
  }

  private String awaitLineUntil(final int fromLine, final Predicate<String> matcher, final long deadline, final Duration timeout)
    throws InterruptedException {
    int nextLine = fromLine;
    while (true) {
      final int lineCount = this.lines.size();
      final Optional<String> match = this.findMatch(nextLine, lineCount, matcher);
      if (match.isPresent()) {
        return match.get();
      }
      nextLine = lineCount;
      this.waitForMoreOutput(deadline, timeout);
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

  private void waitForMoreOutput(final long deadline, final Duration timeout) throws InterruptedException {
    final long now = System.nanoTime();
    final long remaining = deadline - now;
    final boolean running = this.process.isAlive() || this.reader.isAlive();
    if (remaining <= 0 || !running) {
      final String message = "The server took longer than %s to print the expected line!".formatted(timeout);
      throw new AssertionError(message);
    }

    final long waitNanoseconds = Math.min(remaining, POLL_INTERVAL_NANOSECONDS);
    TimeUnit.NANOSECONDS.timedWait(this.lines, waitNanoseconds);
  }

  int getLineCount() {
    synchronized (this.lines) {
      return this.lines.size();
    }
  }

  List<String> getLines() {
    synchronized (this.lines) {
      return List.copyOf(this.lines);
    }
  }

  int awaitExit(final Duration timeout) throws InterruptedException {
    final long milliseconds = timeout.toMillis();
    final boolean exited = this.process.waitFor(milliseconds, TimeUnit.MILLISECONDS);
    if (!exited) {
      final String message = "The server took longer than %s to stop!".formatted(timeout);
      throw new AssertionError(message);
    }

    this.reader.join(milliseconds);

    return this.process.exitValue();
  }

  // the program is all a process tells about itself on every system
  List<ProcessHandle> findDescendants(final Predicate<String> matcher) {
    try (final Stream<ProcessHandle> descendants = this.process.descendants()) {
      return descendants
        .filter(ProcessHandle::isAlive)
        .filter(handle -> matcher.test(programOf(handle)))
        .toList();
    }
  }

  static String programOf(final ProcessHandle handle) {
    return handle.info().command().orElse("");
  }
}

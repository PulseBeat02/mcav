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
package me.brandonli.mcav.bukkit.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class GeneratedMedia {

  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);
  private static final int LOG_LIMIT_BYTES = 65_536;
  private static final Set<Path> COMPLETED = new HashSet<>();

  private GeneratedMedia() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static Path ffmpegPath() {
    final String location = Loader.load(ffmpeg.class);
    return Path.of(location);
  }

  public static synchronized Path generate(final Path output, final Function<Path, List<String>> command) {
    return generate(output, command, PROCESS_TIMEOUT);
  }

  static synchronized Path generate(final Path output, final Function<Path, List<String>> command, final Duration timeout) {
    try {
      return generateChecked(output, command, timeout);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while generating test media!";
      throw new IllegalStateException(message, exception);
    }
  }

  private static Path generateChecked(final Path output, final Function<Path, List<String>> command, final Duration timeout)
    throws IOException, InterruptedException {
    final Path destination = output.toAbsolutePath();
    if (COMPLETED.contains(destination) && Files.isRegularFile(destination) && Files.size(destination) > 0) {
      return destination;
    }

    COMPLETED.remove(destination);
    final Path parent = Objects.requireNonNull(destination.getParent(), "A media file must have a directory!");
    Files.createDirectories(parent);
    final String name = destination.getFileName().toString();
    final int dot = name.lastIndexOf('.');
    final String extension = dot >= 0 ? name.substring(dot) : ".tmp";
    final Path temporary = Files.createTempFile(parent, "mcav-generating-", extension);
    Path log = null;
    Throwable primary = null;
    try {
      log = Files.createTempFile(parent, "mcav-generating-", ".log");
      final List<String> arguments = command.apply(temporary);
      runProcess(arguments, log, timeout);

      if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
        final String message = "The fixture command %s succeeded without producing nonempty media!".formatted(arguments);
        throw new IOException(message);
      }

      publish(temporary, destination);
      COMPLETED.add(destination);
      return destination;
    } catch (final IOException | InterruptedException | RuntimeException | Error exception) {
      primary = exception;
      throw exception;
    } finally {
      cleanup(temporary, log, primary);
    }
  }

  private static void publish(final Path temporary, final Path destination) throws IOException {
    try {
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (final AtomicMoveNotSupportedException exception) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void runProcess(final List<String> command, final Path log, final Duration timeout)
    throws IOException, InterruptedException {
    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    final long startedAt = System.nanoTime();
    final Process process = builder.start();
    final Set<ProcessHandle> descendants = new HashSet<>();
    boolean successful = false;
    try {
      waitForExit(process, command, timeout, startedAt, descendants);

      final int code = process.exitValue();
      if (code != 0) {
        final String diagnostic = readLog(log);
        final String message = "Fixture command failed with exit code %s, see its log below!\n%s".formatted(code, diagnostic);
        throw new IOException(message);
      }

      successful = true;
    } finally {
      if (!successful) {
        terminate(process, descendants);
      }
    }
  }

  private static void waitForExit(
    final Process process,
    final List<String> command,
    final Duration timeout,
    final long startedAt,
    final Set<ProcessHandle> descendants
  ) throws IOException, InterruptedException {
    final long budget = TimeUnit.NANOSECONDS.convert(timeout);
    boolean exited = false;
    while (!exited) {
      recordDescendants(process, descendants);
      final long remaining = budget - (System.nanoTime() - startedAt);
      if (remaining <= 0) {
        final String message = "Fixture command %s took longer than its %s timeout!".formatted(command, timeout);
        throw new IOException(message);
      }

      final long poll = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20));
      exited = process.waitFor(poll, TimeUnit.NANOSECONDS);
    }
  }

  private static void recordDescendants(final Process process, final Set<ProcessHandle> descendants) {
    try (final Stream<ProcessHandle> children = process.descendants()) {
      children.forEach(descendants::add);
    }
  }

  private static String readLog(final Path log) throws IOException {
    try (final InputStream input = Files.newInputStream(log)) {
      final byte[] bytes = input.readNBytes(LOG_LIMIT_BYTES);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static void terminate(final Process process, final Set<ProcessHandle> descendants) {
    recordDescendants(process, descendants);

    descendants.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      // A bounded grace period lets Windows release handles before temporary files are deleted.
      process.waitFor(3, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private static void cleanup(final Path temporary, final @Nullable Path log, final @Nullable Throwable primary) throws IOException {
    IOException cleanup = null;
    final List<Path> paths = log == null ? List.of(temporary) : List.of(temporary, log);
    for (final Path path : paths) {
      cleanup = deleteCollectingFailure(path, cleanup);
    }

    if (cleanup == null) {
      return;
    }

    if (primary == null) {
      throw cleanup;
    }

    primary.addSuppressed(cleanup);
  }

  private static @Nullable IOException deleteCollectingFailure(final Path path, final @Nullable IOException earlier) {
    try {
      Files.deleteIfExists(path);
      return earlier;
    } catch (final IOException exception) {
      if (earlier == null) {
        return exception;
      }

      earlier.addSuppressed(exception);
      return earlier;
    }
  }
}

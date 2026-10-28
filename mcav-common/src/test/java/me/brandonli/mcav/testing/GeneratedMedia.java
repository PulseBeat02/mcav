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
package me.brandonli.mcav.testing;

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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import me.brandonli.mcav.utils.ffmpeg.FfmpegCommand;
import me.brandonli.mcav.utils.ffmpeg.FfmpegExecutableProvider;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class GeneratedMedia {

  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);
  private static final int LOG_LIMIT_BYTES = 65_536;
  private static final Set<Path> COMPLETED = new HashSet<>();

  private static final String TEMPORARY_FILE_PREFIX = "mcav-generating-";
  private static final String DEFAULT_EXTENSION = ".tmp";
  private static final String LOG_EXTENSION = ".log";

  private GeneratedMedia() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static Path ffmpegPath() {
    return FfmpegExecutableProvider.getFfmpegPath();
  }

  // Generates a file into a temporary sibling with the same extension and publishes it only after a successful nonempty
  // result. A fixture process has a 120 second deadline. Successful paths are reused within this JVM.
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
      throw new IllegalStateException("Interrupted while generating test media!", exception);
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
    final String extension = dot >= 0 ? name.substring(dot) : DEFAULT_EXTENSION;
    final Path temporary = Files.createTempFile(parent, TEMPORARY_FILE_PREFIX, extension);
    Path log = null;
    Throwable primary = null;
    try {
      log = Files.createTempFile(parent, TEMPORARY_FILE_PREFIX, LOG_EXTENSION);
      final List<String> arguments = command.apply(temporary);
      runProcess(arguments, log, timeout);

      if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
        final String message = "Fixture command %s succeeded without producing nonempty media!".formatted(arguments);
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
    final Map<String, String> environment = builder.environment();
    final Map<String, String> ffmpegEnvironment = ffmpegEnvironment();
    environment.putAll(ffmpegEnvironment);
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    final long startedAt = System.nanoTime();
    final Process process = builder.start();
    final Set<ProcessHandle> descendants = new HashSet<>();
    boolean successful = false;
    try {
      awaitExit(process, command, timeout, startedAt, descendants);

      final int code = process.exitValue();
      if (code != 0) {
        final String diagnostic = readLog(log);
        final String message = "Fixture command failed with exit code %s and the diagnostic \"%s\"!".formatted(code, diagnostic);
        throw new IOException(message);
      }

      successful = true;
    } finally {
      if (!successful) {
        terminate(process, descendants);
      }
    }
  }

  // the ffmpeg process needs the library path that FfmpegCommand sets
  private static Map<String, String> ffmpegEnvironment() {
    final FfmpegCommand command = FfmpegCommand.builder().build();
    return command.getEnvironment();
  }

  private static void awaitExit(
    final Process process,
    final List<String> command,
    final Duration timeout,
    final long startedAt,
    final Set<ProcessHandle> descendants
  ) throws IOException, InterruptedException {
    final long budget = TimeUnit.NANOSECONDS.convert(timeout);
    while (true) {
      try (final Stream<ProcessHandle> children = process.descendants()) {
        children.forEach(descendants::add);
      }
      final long remaining = budget - (System.nanoTime() - startedAt);
      if (remaining <= 0) {
        final String message = "Fixture command %s exceeded its timeout of %s!".formatted(command, timeout);
        throw new IOException(message);
      }
      final long poll = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20));
      if (process.waitFor(poll, TimeUnit.NANOSECONDS)) {
        break;
      }
    }
  }

  private static String readLog(final Path log) throws IOException {
    try (final InputStream input = Files.newInputStream(log)) {
      final byte[] bytes = input.readNBytes(LOG_LIMIT_BYTES);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static void terminate(final Process process, final Set<ProcessHandle> descendants) {
    try (final Stream<ProcessHandle> children = process.descendants()) {
      children.forEach(descendants::add);
    }

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
      try {
        Files.deleteIfExists(path);
      } catch (final IOException exception) {
        cleanup = combineFailures(cleanup, exception);
      }
    }

    if (cleanup != null) {
      if (primary != null) {
        primary.addSuppressed(cleanup);
      } else {
        throw cleanup;
      }
    }
  }

  private static IOException combineFailures(final @Nullable IOException first, final IOException next) {
    if (first == null) {
      return next;
    }

    first.addSuppressed(next);
    return first;
  }
}

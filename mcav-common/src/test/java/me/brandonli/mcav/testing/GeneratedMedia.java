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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Generates fixture media without depending on production subprocess code that PIT may mutate. */
public final class GeneratedMedia {

  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);
  private static final int LOG_LIMIT_BYTES = 65_536;
  private static final Set<Path> COMPLETED = new HashSet<>();

  private GeneratedMedia() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /** Locates the bundled executable directly, without production FFmpeg provider mutations affecting fixtures. */
  public static Path ffmpegPath() {
    final String location = Loader.load(ffmpeg.class);
    return Path.of(location);
  }

  /**
   * Generates a file into a temporary sibling with the same extension and publishes it only after a successful
   * nonempty result. A fixture process has a 120 second deadline. Successful paths are reused within this JVM.
   *
   * @param output the destination media file
   * @param command constructs the command using the temporary media destination
   * @return the absolute published path
   */
  public static synchronized Path generate(final Path output, final Function<Path, List<String>> command) {
    return generate(output, command, PROCESS_TIMEOUT);
  }

  static synchronized Path generate(final Path output, final Function<Path, List<String>> command, final Duration timeout) {
    try {
      return generateChecked(output, command, timeout);
    } catch (final IOException failure) {
      throw new UncheckedIOException(failure);
    } catch (final InterruptedException failure) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IllegalStateException("Interrupted while generating test media", failure);
    }
  }

  private static Path generateChecked(final Path output, final Function<Path, List<String>> command, final Duration timeout)
    throws IOException, InterruptedException {
    final Path destination = output.toAbsolutePath();
    if (COMPLETED.contains(destination) && Files.isRegularFile(destination) && Files.size(destination) > 0) {
      return destination;
    }
    COMPLETED.remove(destination);
    final Path parent = Objects.requireNonNull(destination.getParent(), "A media file must have a directory");
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
        throw new IOException("The fixture command succeeded without producing nonempty media: " + arguments);
      }
      publish(temporary, destination);
      COMPLETED.add(destination);
      return destination;
    } catch (final IOException | InterruptedException | RuntimeException | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      cleanup(temporary, log, primary);
    }
  }

  private static void publish(final Path temporary, final Path destination) throws IOException {
    try {
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (final AtomicMoveNotSupportedException unavailable) {
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
      final long budget = TimeUnit.NANOSECONDS.convert(timeout);
      while (true) {
        try (final Stream<ProcessHandle> children = process.descendants()) {
          children.forEach(descendants::add);
        }
        final long remaining = budget - (System.nanoTime() - startedAt);
        if (remaining <= 0) {
          throw new IOException("Fixture command exceeded " + timeout + ": " + command);
        }
        final long poll = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20));
        if (process.waitFor(poll, TimeUnit.NANOSECONDS)) {
          break;
        }
      }
      final int code = process.exitValue();
      if (code != 0) {
        final String diagnostic = readLog(log);
        throw new IOException("Fixture command failed with exit code " + code + ": " + diagnostic);
      }
      successful = true;
    } finally {
      if (!successful) {
        terminate(process, descendants);
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
    } catch (final InterruptedException interrupted) {
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
      } catch (final IOException failure) {
        if (cleanup == null) {
          cleanup = failure;
        } else {
          cleanup.addSuppressed(failure);
        }
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
}

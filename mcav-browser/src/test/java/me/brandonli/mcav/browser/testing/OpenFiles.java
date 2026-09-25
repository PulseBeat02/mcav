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
package me.brandonli.mcav.browser.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Checks that an operation which fails closes what it opened, by counting the open files of the JVM before and after
 * it failed many times. Only Linux lists them ({@code /proc/self/fd}); elsewhere the check is skipped.
 */
public final class OpenFiles {

  private static final int ATTEMPTS = 100;

  private OpenFiles() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Runs an attempt a hundred times and fails if the JVM has half as many more files open afterwards.
   *
   * @param description what is attempted, used in the failure message
   * @param attempt     the attempt, which must close what it opens
   */
  public static void leaveNoneOpen(final String description, final Runnable attempt) {
    final Path descriptors = Path.of("/proc/self/fd");
    assumeTrue(Files.isDirectory(descriptors), "this system lists the open files of a process");
    final long before = count(descriptors);
    for (int count = 0; count < ATTEMPTS; count++) {
      attempt.run();
    }
    final long left = count(descriptors) - before;
    assertTrue(left < ATTEMPTS / 2, description + " left " + left + " files open in " + ATTEMPTS + " attempts");
  }

  private static long count(final Path descriptors) {
    try (final Stream<Path> open = Files.list(descriptors)) {
      return open.count();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}

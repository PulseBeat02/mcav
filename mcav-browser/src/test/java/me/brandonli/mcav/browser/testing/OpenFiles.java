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

public final class OpenFiles {

  private static final int ATTEMPTS = 100;

  private static final Path DESCRIPTORS = Path.of("/proc/self/fd");

  private OpenFiles() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void leaveNoneOpen(final String description, final Runnable attempt) {
    final Path descriptors = DESCRIPTORS;
    assumeTrue(Files.isDirectory(descriptors), "This system does not list the open files of a process!");
    final long before = count(descriptors);
    for (int count = 0; count < ATTEMPTS; count++) {
      attempt.run();
    }

    final long left = count(descriptors) - before;
    final boolean fewLeftOpen = left < ATTEMPTS / 2;
    assertTrue(fewLeftOpen, description + " left " + left + " files open in " + ATTEMPTS + " attempts");
  }

  private static long count(final Path descriptors) {
    try (final Stream<Path> open = Files.list(descriptors)) {
      return open.count();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}

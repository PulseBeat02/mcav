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
import java.io.UncheckedIOException;

// Runs the file work of a fake whose interface declares no checked exception, reporting a failure unchecked.
public final class IoActions {

  private IoActions() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void runUnchecked(final IoAction action) {
    try {
      action.run();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @FunctionalInterface
  public interface IoAction {
    void run() throws IOException;
  }
}

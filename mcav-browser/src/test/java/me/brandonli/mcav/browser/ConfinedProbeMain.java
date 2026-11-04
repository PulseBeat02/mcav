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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

// Restricts its main thread as a confined helper does, then runs cat to show that a later process is restricted too.
final class ConfinedProbeMain {

  private static final String STATUS_FILE = "/proc/self/status";

  private static final Path ROOT = Path.of("/");

  private ConfinedProbeMain() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] arguments) throws Exception {
    final Path hidden = Path.of(arguments[0]);
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(ROOT, List.of(hidden), List.of(), List.of());
    final int version = Landlock.version();
    Landlock.restrictThread(version, rules);

    final Process cat = new ProcessBuilder("cat", arguments[1], STATUS_FILE).redirectErrorStream(true).start();
    final InputStream stream = cat.getInputStream();
    final byte[] bytes = stream.readAllBytes();
    final String output = new String(bytes, StandardCharsets.UTF_8);
    System.out.println(output);
    final int exitCode = cat.waitFor();
    System.out.println("cat exited %s".formatted(exitCode));
  }
}

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Restricts its main thread as a confined helper does, hiding the folder of its first argument, and then runs
 * {@code cat} on the file of its second argument and on its own status, to show that a process started afterwards is
 * restricted too.
 */
final class ConfinedProbeMain {

  private ConfinedProbeMain() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static void main(final String[] arguments) throws Exception {
    final Path hidden = Path.of(arguments[0]);
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(Path.of("/"), List.of(hidden), List.of(), List.of());
    Landlock.restrictThread(Landlock.version(), rules);
    final Process cat = new ProcessBuilder("cat", arguments[1], "/proc/self/status").redirectErrorStream(true).start();
    final String output = new String(cat.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(output);
    System.out.println("cat exited " + cat.waitFor());
  }
}

/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.vm; // Licensed to the Software Freedom Conservancy (SFC) under one

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A utility class to find executables, stolen directly from Selenium.
 */
public class ExecutableFinder {

  private static final List<String> ENDINGS = OSUtils.getOS() == OS.WINDOWS
    ? Arrays.asList("", ".cmd", ".exe", ".com", ".bat")
    : singletonList("");

  /**
   * Find the executable by scanning the file system and the PATH. In the case of Windows this
   * method allows common executable endings (".com", ".bat" and ".exe") to be omitted.
   *
   * @param named The name of the executable to find
   * @return The absolute path to the executable, or null if no match is made.
   */
  public @Nullable String find(final String named) {
    File file = new File(named);
    if (canExecute(file)) {
      return named;
    }

    final OS os = OSUtils.getOS();
    if (os == OS.WINDOWS) {
      file = new File(named + ".exe");
      if (canExecute(file)) {
        return named + ".exe";
      }
    }

    final List<String> pathSegments = new ArrayList<>(this.fromEnvironment());
    if (os == OS.MAC) {
      pathSegments.addAll(this.macSpecificPathSegments());
    }

    for (final String pathSegment : pathSegments) {
      for (final String ending : ENDINGS) {
        file = new File(pathSegment, named + ending);
        if (canExecute(file)) {
          return file.getAbsolutePath();
        }
      }
    }
    return null;
  }

  private List<String> fromEnvironment() {
    String pathName = "PATH";
    final Map<String, String> env = System.getenv();
    if (!env.containsKey(pathName)) {
      for (final String key : env.keySet()) {
        if (pathName.equalsIgnoreCase(key)) {
          pathName = key;
          break;
        }
      }
    }
    final String path = env.get(pathName);
    return path != null ? Arrays.asList(path.split(File.pathSeparator)) : emptyList();
  }

  private List<String> macSpecificPathSegments() {
    final File pathFile = new File("/etc/paths");
    if (pathFile.exists()) {
      try {
        return Files.readAllLines(pathFile.toPath());
      } catch (final IOException e) {
        // Guess we won't include those, then
      }
    }
    return emptyList();
  }

  private static boolean canExecute(final File file) {
    return file.exists() && !file.isDirectory() && file.canExecute();
  }
}

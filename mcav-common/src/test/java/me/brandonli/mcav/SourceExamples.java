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
package me.brandonli.mcav;

import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.SourceDetectionHelper;

public final class SourceExamples {

  private static final String LOCAL_FILE_PATH = "C:\\rickroll.mp4";

  private SourceExamples() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final List<String> inputs = List.of(
      "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "2",
      LOCAL_FILE_PATH,
      "https://github.com/mediaelement/mediaelement-files/blob/master/echo-hereweare.mp4",
      "dshow||video=OBS Virtual Camera",
      "daibsdahsbdashvb"
    );
    for (final String input : inputs) {
      final Optional<Source> detected = helper.detectSource(input);
      final String name;
      if (detected.isPresent()) {
        final Source source = detected.get();
        name = source.getName();
      } else {
        name = "unknown";
      }
      System.out.println("%s -> %s".formatted(input, name));
    }
  }
}

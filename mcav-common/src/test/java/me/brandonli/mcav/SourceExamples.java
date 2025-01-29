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

import java.util.Optional;
import java.util.function.Consumer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.SourceDetectionHelper;

public class SourceExamples {

  public static void main(final String[] args) {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Consumer<String> determine = source -> {
      final Optional<Source> detectedSource = helper.detectSource(source);
      final String name = detectedSource.isPresent() ? detectedSource.get().getName() : "unknown";
      System.out.println(name);
    };
    determine.accept("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    determine.accept("2");
    determine.accept("C:\\rickroll.mp4");
    determine.accept("https://github.com/mediaelement/mediaelement-files/blob/master/echo-hereweare.mp4");
    determine.accept("dshow||video=OBS Virtual Camera");
    determine.accept("daibsdahsbdashvb");
  }
}

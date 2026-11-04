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
package me.brandonli.mcav.bukkit;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.source.file.FileSource;

// the scoreboard example of the documentation, compiled here so it stays valid
public final class JavadocFormattingExample {

  private JavadocFormattingExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated!");
  }

  public static DisplayableImage showImageOnScoreboard(final Collection<UUID> viewers, final Path imageFile) {
    final int width = 16;
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
    builder.character(Characters.BLACK_SQUARE);
    builder.lines(ScoreboardConfiguration.MAXIMUM_LINES);
    builder.width(width);
    builder.viewers(viewers);
    final ScoreboardConfiguration configuration = builder.build();

    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final FileSource file = FileSource.path(imageFile);
    try (final ImageBuffer image = ImageBuffer.path(file)) {
      display.displayImage(image);
    }

    return display;
  }
}

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
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.source.file.FileSource;

/**
 * The scoreboard example from the documentation, kept here so it keeps compiling. It must run inside a server
 * where the Bukkit module is installed.
 */
public final class JavaDocFormattingExample {

  private JavaDocFormattingExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated");
  }

  /**
   * Shows an image on the scoreboard of a player.
   *
   * @param viewer the player who sees the image
   * @param file   the image file
   */
  public static void showScoreboardImage(final UUID viewer, final Path file) {
    final Collection<UUID> viewers = List.of(viewer);
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.character(Characters.FULL_CHARACTER);
    builder.lines(15);
    builder.width(16);
    builder.viewers(viewers);
    final ScoreboardConfiguration configuration = builder.build();

    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final FileSource source = FileSource.path(file);
    try (final ImageBuffer image = ImageBuffer.path(source)) {
      display.displayImage(image);
    }

    // later, when the image is no longer shown
    display.release();
  }
}

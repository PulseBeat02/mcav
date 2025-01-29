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
package me.brandonli.mcav.bukkit;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.media.image.ImageBuffer;

@SuppressWarnings("all")
public class JavaDocFormattingExample {

  public static void main(final String[] args) {
    final Collection<UUID> viewers = null;
    final ScoreboardConfiguration configuration = ScoreboardConfiguration.builder()
      .character(Characters.FULL_CHARACTER)
      .lines(16)
      .width(16)
      .viewers(viewers)
      .build();
    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final ImageBuffer image = null;
    display.displayImage(image);
    // do some play back
    display.release();
    image.release();
  }
}

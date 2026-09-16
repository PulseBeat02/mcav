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
package me.brandonli.mcav.bukkit.media.image;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.render.ScoreboardRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * Displays still images on the sidebar scoreboard of the viewers. The scoreboard the viewers had before is restored
 * when the image is released.
 */
public class ScoreboardImage implements DisplayableImage {

  private final ScoreboardRenderer renderer;

  ScoreboardImage(final ScoreboardConfiguration configuration) {
    this.renderer = new ScoreboardRenderer(configuration);
  }

  /**
   * Shows the scoreboard if it is not shown yet, and draws the image onto it during the next server tick. May be
   * called from any thread.
   *
   * @param image the image to show, which is resized to the scoreboard in place
   * @throws NullPointerException if the image is null
   */
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    this.renderer.show();
    this.renderer.render(image);
  }

  /**
   * Removes the scoreboard and restores the scoreboard every viewer had before. May be called from any thread.
   */
  @Override
  public void release() {
    this.renderer.hide();
  }
}

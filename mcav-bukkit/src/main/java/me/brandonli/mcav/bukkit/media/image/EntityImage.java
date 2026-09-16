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
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.render.EntityRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.bukkit.entity.TextDisplay;

/**
 * Displays still images as colored text inside a {@link TextDisplay} entity. The entity is removed when the image
 * is released.
 */
public class EntityImage implements DisplayableImage {

  private final EntityRenderer renderer;

  EntityImage(final EntityConfiguration configuration) {
    this.renderer = new EntityRenderer(configuration);
  }

  /**
   * Spawns the text display if it is not spawned yet, and draws the image into it during the next server tick. May
   * be called from any thread.
   *
   * @param image the image to show, which is resized to the entity in place
   * @throws NullPointerException if the image is null
   */
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    this.renderer.show();
    this.renderer.render(image);
  }

  /**
   * Removes the text display. May be called from any thread.
   */
  @Override
  public void release() {
    this.renderer.hide();
  }
}

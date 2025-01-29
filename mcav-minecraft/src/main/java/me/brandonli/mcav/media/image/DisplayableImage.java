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
package me.brandonli.mcav.media.image;

/**
 * The DisplayableImage interface defines a contract for displaying images of type StaticImage.
 * Classes implementing this interface should provide the implementation for the display method
 * to render or handle the provided StaticImage as per specific requirements.
 */
public interface DisplayableImage {
  /**
   * Displays the specified static image. This method is intended to be implemented
   * by classes that handle rendering or processing of images of type StaticImage.
   *
   * @param image the static image to be displayed; must not be null
   */
  void display(final StaticImage image);

  /**
   * Releases any resources associated with the DisplayableImage instance.
   * <p>
   * This method should be called to clean up resources when the instance is no longer needed.
   * Implementations of this method ensure that any system or memory resources used are properly released.
   */
  void release();
}

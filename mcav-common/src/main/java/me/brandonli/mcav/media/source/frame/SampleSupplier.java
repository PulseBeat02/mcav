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
package me.brandonli.mcav.media.source.frame;

/**
 * Supplies frames as packed ARGB pixels to a {@link FrameSource}. Called once per frame on the player thread.
 *
 * <p>The {@link me.brandonli.mcav.media.player.image.ImagePlayer} copies the pixels into its frame before it runs the
 * pipeline and never modifies or keeps the returned array, so a supplier may hand out the same array every time, such
 * as the shared pixels of an image, see {@link me.brandonli.mcav.media.image.ImageBuffer#getPixels()}.
 */
@FunctionalInterface
public interface SampleSupplier {
  /**
   * Gets the next frame.
   *
   * @return the pixels of the frame laid out row by row, or an empty array if no frame is available yet. The array
   *     may be shared and is read-only, as the pixels of
   *     {@link me.brandonli.mcav.media.image.ImageBuffer#getPixels()} are; copy it before modifying it, as
   *     {@link me.brandonli.mcav.media.image.ImageBuffer#copyPixels()} does, or hand it on as a read-only view, as
   *     {@link me.brandonli.mcav.media.image.ImageBuffer#getReadOnlyPixels()} does
   */
  int[] getFrameSamples();
}

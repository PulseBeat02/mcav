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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

/**
 * Represents a functional interface used to handle the result of a dithering operation.
 */
@FunctionalInterface
public interface DitherResultStep {
  /**
   * Processes the dithered video output and its associated metadata.
   *
   * @param dithered a byte array representing the dithered video data
   * @param width   the width of the dithered video in pixels
   * @param height  the height of the dithered video in pixels
   */
  void process(final byte[] dithered, final int width, final int height);
}

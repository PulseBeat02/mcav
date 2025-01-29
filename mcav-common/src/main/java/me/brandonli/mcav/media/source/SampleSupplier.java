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
package me.brandonli.mcav.media.source;

/**
 * Represents a functional interface that supplies an array of integers representing pixel data.
 * This interface can be implemented to provide custom logic for retrieving pixel data for a frame.
 * <p>
 * This interface is a core part of the media playback utilities and is used for extracting or
 * streaming frame samples in a flexible manner.
 */
@FunctionalInterface
public interface SampleSupplier {
  /**
   * Supplies an array of integers representing the pixel data of a frame.
   *
   * @return an array of integers representing the pixel data of a frame
   */
  int[] getFrameSamples();
}

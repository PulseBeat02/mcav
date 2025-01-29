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

import me.brandonli.mcav.media.image.DynamicImageBuffer;

/**
 * Represents a source of frames that can be repeated a specified number of times.
 */
public interface RepeatingFrameSource extends FrameSource {
  /**
   * Returns the number of times the frame should be repeated.
   *
   * @return the repeat count
   */
  int getRepeatCount();

  /**
   * Creates a new {@link RepeatingFrameSource} instance, which repeats the frames
   * of a given {@link DynamicImageBuffer} for the specified number of times.
   *
   * @param source the dynamic image whose frames will be repeated
   * @param repeatCount the number of times each frame is repeated; must be positive, or -1 for infinite repetition
   * @return a new instance of {@link RepeatingFrameSource} configured with the provided source and repeat count
   */
  static RepeatingFrameSource repeating(final DynamicImageBuffer source, final int repeatCount) {
    return new RepeatingFrameSourceImpl(source, repeatCount);
  }

  /**
   * Creates a new {@link RepeatingFrameSource} instance, which repeats the frames
   * of a given {@link DynamicImageBuffer} indefinitely.
   *
   * @param source the dynamic image whose frames will be repeated
   * @return a new instance of {@link RepeatingFrameSource} configured with the provided source for infinite repetition
   */
  static RepeatingFrameSource repeating(final DynamicImageBuffer source) {
    return new RepeatingFrameSourceImpl(source, Integer.MAX_VALUE);
  }
}

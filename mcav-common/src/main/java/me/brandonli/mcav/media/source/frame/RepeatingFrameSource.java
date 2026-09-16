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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.DynamicImageBuffer;

/**
 * Plays the frames of an animated image, such as a GIF, in a loop.
 *
 * <p>The supplier of the source hands out the pixels of the animation frames themselves, so looping copies nothing:
 * the returned arrays are shared and read-only, and the same arrays come back in every loop, see
 * {@link me.brandonli.mcav.media.image.ImageBuffer#getPixels()}. Use
 * {@link me.brandonli.mcav.media.image.ImageBuffer#copyPixels()} for pixels you may modify and
 * {@link me.brandonli.mcav.media.image.ImageBuffer#getReadOnlyPixels()} for a view you can hand to other code.
 */
public interface RepeatingFrameSource extends FrameSource {
  /**
   * Creates a source that loops an animation a fixed number of times, then keeps showing its last frame.
   *
   * @param animation   the decoded animation, which must stay open while the source is played
   * @param repeatCount how often the animation is played, at least 1
   * @return the source
   */
  static RepeatingFrameSource repeating(final DynamicImageBuffer animation, final int repeatCount) {
    Preconditions.checkNotNull(animation, "Animation must not be null");
    Preconditions.checkArgument(repeatCount > 0, "Repeat count must be positive");
    return new RepeatingFrameSourceImpl(animation, repeatCount);
  }

  /**
   * Creates a source that loops an animation forever.
   *
   * @param animation the decoded animation, which must stay open while the source is played
   * @return the source
   */
  static RepeatingFrameSource repeating(final DynamicImageBuffer animation) {
    Preconditions.checkNotNull(animation, "Animation must not be null");
    return new RepeatingFrameSourceImpl(animation, Integer.MAX_VALUE);
  }

  /**
   * Gets how often the animation is played.
   *
   * @return the repeat count, or {@link Integer#MAX_VALUE} for endless looping
   */
  int getRepeatCount();
}

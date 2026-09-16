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
import java.util.List;
import me.brandonli.mcav.media.image.DynamicImageBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * The default {@link RepeatingFrameSource}. The player paces the frames using {@link #getFrameRate()}, so the
 * supplier simply hands out the next frame every time it is asked.
 *
 * <p>The supplier returns the shared, read-only pixels of the frames without copying them, see
 * {@link me.brandonli.mcav.media.image.ImageBuffer#getPixels()},
 * {@link me.brandonli.mcav.media.image.ImageBuffer#copyPixels()} and
 * {@link me.brandonli.mcav.media.image.ImageBuffer#getReadOnlyPixels()}.
 */
public final class RepeatingFrameSourceImpl implements RepeatingFrameSource {

  private final List<ImageBuffer> frames;
  private final int repeatCount;
  private final int width;
  private final int height;
  private final float frameRate;

  private int nextFrame;
  private int completedLoops;

  RepeatingFrameSourceImpl(final DynamicImageBuffer animation, final int repeatCount) {
    final List<ImageBuffer> animationFrames = animation.getFrames();
    final boolean empty = animationFrames.isEmpty();
    Preconditions.checkArgument(!empty, "Animation has no frames");
    final ImageBuffer first = animationFrames.getFirst();
    this.frames = animationFrames;
    this.repeatCount = repeatCount;
    this.width = first.getWidth();
    this.height = first.getHeight();
    this.frameRate = animation.getFrameRate();
  }

  @Override
  public int getRepeatCount() {
    return this.repeatCount;
  }

  @Override
  public SampleSupplier supplyFrameSamples() {
    return this::nextSamples;
  }

  private synchronized int[] nextSamples() {
    final int frameCount = this.frames.size();
    final boolean finished = this.completedLoops >= this.repeatCount;
    if (finished) {
      final ImageBuffer last = this.frames.get(frameCount - 1);
      return last.getPixels();
    }
    final ImageBuffer frame = this.frames.get(this.nextFrame);
    this.nextFrame++;
    if (this.nextFrame >= frameCount) {
      this.nextFrame = 0;
      if (this.repeatCount != Integer.MAX_VALUE) {
        this.completedLoops++;
      }
    }
    return frame.getPixels();
  }

  @Override
  public int getFrameWidth() {
    return this.width;
  }

  @Override
  public int getFrameHeight() {
    return this.height;
  }

  @Override
  public float getFrameRate() {
    return this.frameRate;
  }
}

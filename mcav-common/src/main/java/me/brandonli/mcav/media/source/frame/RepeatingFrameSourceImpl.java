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
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.image.DynamicImageBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * Implementation of the {@link RepeatingFrameSource} interface.
 */
public class RepeatingFrameSourceImpl implements RepeatingFrameSource {

  private final int repeats;
  private final List<ImageBuffer> images;
  private final int width;
  private final int height;

  private final AtomicInteger counter;
  private final AtomicInteger imageIndex;
  private final long sleepTimeMs;

  RepeatingFrameSourceImpl(final DynamicImageBuffer source, final int repeats) {
    this.images = source.getFrames();
    Preconditions.checkArgument(!this.images.isEmpty());
    final ImageBuffer firstImage = this.images.getFirst();
    this.repeats = repeats;
    this.width = firstImage.getWidth();
    this.height = firstImage.getHeight();
    this.counter = new AtomicInteger(0);
    this.imageIndex = new AtomicInteger(0);
    this.sleepTimeMs = (long) (1000.0 / source.getFrameRate());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getRepeatCount() {
    return this.repeats;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SampleSupplier supplyFrameSamples() {
    if (this.counter.get() >= RepeatingFrameSourceImpl.this.repeats && RepeatingFrameSourceImpl.this.repeats != -1) {
      return () -> new int[this.width * this.height];
    }
    try {
      Thread.sleep(this.sleepTimeMs);
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
    int index = this.imageIndex.getAndIncrement();
    if (index >= this.images.size()) {
      this.counter.incrementAndGet();
      this.imageIndex.set(0);
      index = 0;
      if (this.counter.get() >= RepeatingFrameSourceImpl.this.repeats && RepeatingFrameSourceImpl.this.repeats != -1) {
        return () -> new int[this.width * this.height];
      }
    }
    final ImageBuffer image = this.images.get(index);
    final int[] frameSamples = image.getPixels();
    return () -> frameSamples;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFrameWidth() {
    return this.width;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFrameHeight() {
    return this.height;
  }
}

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

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.image.DynamicImage;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Implementation of the {@link RepeatingFrameSource} interface that repeats video frames
 * a specified number of times. This class acts as a wrapper for a given {@link DynamicImage},
 * transforming it into a repeated frame source.
 */
public class RepeatingFrameSourceImpl implements RepeatingFrameSource {

  private final int repeats;
  private final List<StaticImage> images;
  private final VideoMetadata metadata;
  private final AtomicInteger counter;
  private final AtomicInteger imageIndex;
  private final long sleepTimeMs;

  RepeatingFrameSourceImpl(final DynamicImage source, final int repeats) {
    this.images = source.getFrames();
    Preconditions.checkArgument(!this.images.isEmpty());
    this.repeats = repeats;
    this.metadata = this.getMetadata(this.images);
    this.counter = new AtomicInteger(0);
    this.imageIndex = new AtomicInteger(0);
    this.sleepTimeMs = (long) (1000.0 / source.getFrameRate());
  }

  private VideoMetadata getMetadata(@UnderInitialization RepeatingFrameSourceImpl this, final List<StaticImage> images) {
    final StaticImage first = images.get(0);
    return VideoMetadata.of(first.getWidth(), first.getHeight());
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
      return () -> new int[this.metadata.getVideoWidth() * this.metadata.getVideoHeight()];
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
        return () -> new int[this.metadata.getVideoWidth() * this.metadata.getVideoHeight()];
      }
    }
    final StaticImage image = this.images.get(index);
    final int[] frameSamples = image.getAllPixels();
    return () -> frameSamples;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoMetadata getVideoMetadata() {
    return this.metadata;
  }
}

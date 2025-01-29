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

import java.nio.IntBuffer;
import java.util.function.Supplier;
import me.brandonli.mcav.media.source.DynamicSource;

/**
 * Represents a source that provides raw frames, typically used for video playback.
 */
public interface FrameSource extends DynamicSource {
  /**
   * Returns a supplier that provides the frame samples as an {@link IntBuffer}.
   * The buffer will be empty if there are no samples available.
   *
   * @return a supplier that provides the frame samples as an {@link IntBuffer}.
   */
  default Supplier<IntBuffer> getFrameSupplier() {
    return () -> {
      final int[] frameSamples = this.supplyFrameSamples().getFrameSamples();
      if (frameSamples == null || frameSamples.length == 0) {
        return IntBuffer.allocate(0);
      }
      return IntBuffer.wrap(frameSamples);
    };
  }

  /**
   * Creates a new {@link FrameSource} instance that provides frames from the specified image supplier.
   *
   * @param images the {@link ImageSupplier} that provides the images for the frames.
   * @param width  the width of the frames.
   * @param height the height of the frames.
   * @return a new {@link FrameSource} instance.
   */
  static FrameSource image(final ImageSupplier images, final int width, final int height) {
    return new FrameSourceImpl(images, width, height);
  }

  /**
   * Returns a supplier that provides the frame samples.
   * The samples are typically represented as an array of integers.
   *
   * @return a {@link SampleSupplier} that provides the frame samples.
   */
  SampleSupplier supplyFrameSamples();

  /**
   * Returns the width of the frames provided by this source.
   *
   * @return the width of the frames.
   */
  int getFrameWidth();

  /**
   * Returns the height of the frames provided by this source.
   *
   * @return the height of the frames.
   */
  int getFrameHeight();

  /**
   * {@inheritDoc}
   */
  @Override
  default String getName() {
    return "frame-source";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  default String getResource() {
    return this.getFrameSupplier().toString();
  }

  /**
   * Creates a new {@link FrameSource} instance that provides frames from the specified sample supplier.
   *
   * @param supplier the {@link SampleSupplier} that provides the samples for the frames.
   * @param width    the width of the frames.
   * @param height   the height of the frames.
   * @return a new {@link FrameSource} instance.
   */
  static FrameSource supplier(final SampleSupplier supplier, final int width, final int height) {
    return new FrameSourceImpl(supplier, width, height);
  }
}

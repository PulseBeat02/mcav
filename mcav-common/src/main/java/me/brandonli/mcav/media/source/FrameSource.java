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

import java.nio.IntBuffer;
import java.util.function.Supplier;

public interface FrameSource extends DynamicSource {
  default Supplier<IntBuffer> getFrameSupplier() {
    return () -> {
      final int[] frameSamples = this.supplyFrameSamples().getFrameSamples();
      if (frameSamples == null || frameSamples.length == 0) {
        return IntBuffer.allocate(0);
      }
      return IntBuffer.wrap(frameSamples);
    };
  }

  static FrameSource image(final ImageSupplier images, final int width, final int height) {
    return new FrameSourceImpl(images, width, height);
  }

  SampleSupplier supplyFrameSamples();

  int getFrameWidth();

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

  static FrameSource supplier(final SampleSupplier supplier, final int width, final int height) {
    return new FrameSourceImpl(supplier, width, height);
  }
}

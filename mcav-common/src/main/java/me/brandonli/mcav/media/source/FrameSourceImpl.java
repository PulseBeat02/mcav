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

import java.util.function.Supplier;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;

/**
 * A concrete implementation of the {@link FrameSource} interface that provides
 * functionality for supplying frame samples and retrieving video metadata.
 * This class encapsulates a {@link Supplier} for frame samples and a {@link VideoMetadata} instance.
 * It is designed to act as a source of video frame data and associated video-specific metadata.
 */
public class FrameSourceImpl implements FrameSource {

  private final SampleSupplier frameSamplesSupplier;
  private final int width;
  private final int height;

  FrameSourceImpl(final SampleSupplier supplier, final int width, final int height) {
    this.frameSamplesSupplier = supplier;
    this.width = width;
    this.height = height;
  }

  FrameSourceImpl(final ImageSupplier supplier, final int width, final int height) {
    this.frameSamplesSupplier = supplier.toSampleSupplier();
    this.width = width;
    this.height = height;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SampleSupplier supplyFrameSamples() {
    return this.frameSamplesSupplier;
  }

  @Override
  public int getFrameWidth() {
    return this.width;
  }

  @Override
  public int getFrameHeight() {
    return this.height;
  }
}

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
  private final VideoMetadata videoMetadata;

  FrameSourceImpl(final SampleSupplier supplier, final VideoMetadata videoMetadata) {
    this.frameSamplesSupplier = supplier;
    this.videoMetadata = videoMetadata;
  }

  FrameSourceImpl(final ImageSupplier supplier, final VideoMetadata videoMetadata) {
    this.frameSamplesSupplier = supplier.toSampleSupplier();
    this.videoMetadata = videoMetadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SampleSupplier supplyFrameSamples() {
    return this.frameSamplesSupplier;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoMetadata getVideoMetadata() {
    return this.videoMetadata;
  }
}

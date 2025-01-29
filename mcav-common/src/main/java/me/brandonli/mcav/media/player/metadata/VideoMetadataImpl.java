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
package me.brandonli.mcav.media.player.metadata;

/**
 * Implementation of the {@link VideoMetadata} interface representing metadata for a video.
 * This class provides details such as the video width, height, bitrate, and frame rate.
 * Instances of this class are immutable and provide read-only access to the video properties.
 */
public final class VideoMetadataImpl implements VideoMetadata {

  private final int videoWidth;
  private final int videoHeight;
  private final int videoBitrate;
  private final float videoFrameRate;

  VideoMetadataImpl(final int videoWidth, final int videoHeight, final int videoBitrate, final float videoFrameRate) {
    this.videoWidth = videoWidth;
    this.videoHeight = videoHeight;
    this.videoBitrate = videoBitrate;
    this.videoFrameRate = videoFrameRate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getVideoWidth() {
    return this.videoWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getVideoHeight() {
    return this.videoHeight;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getVideoBitrate() {
    return this.videoBitrate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getVideoFrameRate() {
    return this.videoFrameRate;
  }
}

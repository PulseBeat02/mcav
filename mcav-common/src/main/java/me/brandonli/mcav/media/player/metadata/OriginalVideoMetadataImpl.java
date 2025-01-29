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
package me.brandonli.mcav.media.player.metadata;

/**
 * Implementation of the {@link OriginalVideoMetadata} interface.
 */
public final class OriginalVideoMetadataImpl implements OriginalVideoMetadata {

  private final int videoWidth;
  private final int videoHeight;
  private final int videoBitrate;
  private final float videoFrameRate;

  OriginalVideoMetadataImpl(final int videoWidth, final int videoHeight, final int videoBitrate, final float videoFrameRate) {
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

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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link OriginalVideoMetadata}.
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

  @Override
  public int getVideoWidth() {
    return this.videoWidth;
  }

  @Override
  public int getVideoHeight() {
    return this.videoHeight;
  }

  @Override
  public int getVideoBitrate() {
    return this.videoBitrate;
  }

  @Override
  public float getVideoFrameRate() {
    return this.videoFrameRate;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final OriginalVideoMetadataImpl metadata)) {
      return false;
    }
    final int frameRateComparison = Float.compare(this.videoFrameRate, metadata.videoFrameRate);
    return (
      this.videoWidth == metadata.videoWidth &&
      this.videoHeight == metadata.videoHeight &&
      this.videoBitrate == metadata.videoBitrate &&
      frameRateComparison == 0
    );
  }

  @Override
  public int hashCode() {
    final int frameRateHash = Float.hashCode(this.videoFrameRate);
    int result = this.videoWidth;
    result = result * 31 + this.videoHeight;
    result = result * 31 + this.videoBitrate;
    result = result * 31 + frameRateHash;
    return result;
  }

  @Override
  public String toString() {
    return (
      "VideoMetadata[" + this.videoWidth + "x" + this.videoHeight + ", " + this.videoFrameRate + " fps, " + this.videoBitrate + " bps]"
    );
  }
}

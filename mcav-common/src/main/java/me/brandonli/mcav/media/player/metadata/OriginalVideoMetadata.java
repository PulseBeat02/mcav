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

import com.google.common.base.Preconditions;

/**
 * Size, bitrate, and frame rate of a video stream as decoded. Bitrate and frame rate are {@link #UNKNOWN} when
 * the source does not report them.
 */
public interface OriginalVideoMetadata extends OriginalMetadata {
  /**
   * The value of a property the source does not report.
   */
  int UNKNOWN = -1;

  /**
   * Metadata with every property unknown.
   */
  OriginalVideoMetadata EMPTY = new OriginalVideoMetadataImpl(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);

  /**
   * Creates metadata with every property.
   *
   * @param videoWidth     the width in pixels
   * @param videoHeight    the height in pixels
   * @param videoBitrate   the bitrate in bits per second, or {@link #UNKNOWN}
   * @param videoFrameRate the frame rate in frames per second, or {@link #UNKNOWN}
   * @return the metadata
   */
  static OriginalVideoMetadata of(final int videoWidth, final int videoHeight, final int videoBitrate, final float videoFrameRate) {
    Preconditions.checkArgument(videoWidth > 0 && videoHeight > 0, "Video size must be positive but was %sx%s", videoWidth, videoHeight);
    return new OriginalVideoMetadataImpl(videoWidth, videoHeight, videoBitrate, videoFrameRate);
  }

  /**
   * Creates metadata with a size and frame rate.
   *
   * @param videoWidth     the width in pixels
   * @param videoHeight    the height in pixels
   * @param videoFrameRate the frame rate in frames per second, or {@link #UNKNOWN}
   * @return the metadata
   */
  static OriginalVideoMetadata of(final int videoWidth, final int videoHeight, final float videoFrameRate) {
    return of(videoWidth, videoHeight, UNKNOWN, videoFrameRate);
  }

  /**
   * Creates metadata with a size only.
   *
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return the metadata
   */
  static OriginalVideoMetadata of(final int width, final int height) {
    return of(width, height, UNKNOWN, UNKNOWN);
  }

  /**
   * Gets the width of the decoded frames.
   *
   * @return the width in pixels
   */
  int getVideoWidth();

  /**
   * Gets the height of the decoded frames.
   *
   * @return the height in pixels
   */
  int getVideoHeight();

  /**
   * Gets the bitrate of the video stream.
   *
   * @return the bitrate in bits per second, or {@link #UNKNOWN}
   */
  int getVideoBitrate();

  /**
   * Gets the frame rate of the video stream.
   *
   * @return the frame rate in frames per second, or {@link #UNKNOWN}
   */
  float getVideoFrameRate();
}

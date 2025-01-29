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
 * Represents metadata for video-specific properties.
 */
public interface OriginalVideoMetadata extends OriginalMetadata {
  /**
   * A constant value indicating that an operation or parameter is not applicable or is undefined
   * in the current context.
   */
  int NO_OP = -1;

  /**
   * An empty instance of {@code VideoMetadata} with all properties set to {@link #NO_OP}.
   */
  OriginalVideoMetadata EMPTY = new OriginalVideoMetadataImpl(NO_OP, NO_OP, NO_OP, NO_OP);

  /**
   * Retrieves the width of the video in pixels.
   *
   * @return the width of the video as an integer value, measured in pixels
   */
  int getVideoWidth();

  /**
   * Retrieves the video height in pixels.
   *
   * @return the height of the video as an integer value in pixels
   */
  int getVideoHeight();

  /**
   * Retrieves the bitrate of the video in bits per second (bps).
   *
   * @return the video bitrate as an integer value in bits per second (bps)
   */
  int getVideoBitrate();

  /**
   * Retrieves the frame rate of the video in frames per second (fps).
   *
   * @return the video frame rate as a floating-point value in frames per second
   */
  float getVideoFrameRate();

  /**
   * Creates a new instance of {@code VideoMetadata} with the specified video properties.
   *
   * @param videoWidth     the width of the video in pixels
   * @param videoHeight    the height of the video in pixels
   * @param videoBitrate   the bitrate of the video in kilobits per second (kbps)
   * @param videoFrameRate the frame rate of the video in frames per second (fps)
   * @return a new {@code VideoMetadata} instance containing the specified video properties
   */
  static OriginalVideoMetadata of(final int videoWidth, final int videoHeight, final int videoBitrate, final float videoFrameRate) {
    return new OriginalVideoMetadataImpl(videoWidth, videoHeight, videoBitrate, videoFrameRate);
  }

  /**
   * Creates a new instance of {@code VideoMetadata} with the specified video properties.
   *
   * @param videoWidth     the width of the video in pixels
   * @param videoHeight    the height of the video in pixels
   * @param videoFrameRate the frame rate of the video in frames per second (fps)
   * @return a new {@code VideoMetadata} instance containing the specified video properties
   */
  static OriginalVideoMetadata of(final int videoWidth, final int videoHeight, final float videoFrameRate) {
    return new OriginalVideoMetadataImpl(videoWidth, videoHeight, NO_OP, videoFrameRate);
  }

  /**
   * Creates a new instance of {@code VideoMetadata} with the specified width and height.
   * This method sets the bitrate to a default value and the frame rate to a no-operation value.
   *
   * @param width  the width of the video in pixels
   * @param height the height of the video in pixels
   * @return a new {@code VideoMetadata} instance containing the specified width and height,
   * along with default values for bitrate and frame rate
   */
  static OriginalVideoMetadata of(final int width, final int height) {
    return new OriginalVideoMetadataImpl(width, height, NO_OP, NO_OP);
  }
}

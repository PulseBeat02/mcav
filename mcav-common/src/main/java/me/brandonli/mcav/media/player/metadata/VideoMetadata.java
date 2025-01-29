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
 * Represents metadata for video-specific properties.
 * This interface defines methods for retrieving essential details about a video file or stream,
 * including its width, height, bitrate, and frame rate. It provides static utility methods for
 * creating instances of {@code VideoMetadata}.
 * <p>
 * The instances of this interface are typically immutable, offering read-only access
 * to the video metadata.
 */
public interface VideoMetadata extends Metadata {
  /**
   * A constant value indicating that an operation or parameter is not applicable or is undefined
   * in the current context.
   * <p>
   * This can be used as a placeholder to represent optional or default states where
   * no specific value or action is required.
   */
  int NO_OP = -1;
  /**
   * The default bitrate for video files or streams, measured in kilobits per second (kbps).
   * This value is used as a fallback when a specific bitrate is not explicitly provided.
   */
  int DEFAULT_BITRATE = 4000;

  /**
   * The default frame rate used for video playback or metadata processing.
   * It represents the number of frames displayed per second, typically measured in frames per second (fps).
   * This constant can be used as a fallback value when a specific frame rate is not provided or available.
   */
  int DEFAULT_FRAME_RATE = 30;

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
   * Retrieves the bitrate of the video, typically measured in kilobits per second (kbps).
   * This value reflects the amount of data used per second for the video encoding,
   * impacting the video quality and file size.
   *
   * @return the video bitrate as an integer value in kbps
   */
  int getVideoBitrate();

  /**
   * Retrieves the frame rate of the video, representing the number of frames
   * displayed per second (fps). This value is typically used to determine the
   * smoothness of the video playback.
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
  static VideoMetadata of(final int videoWidth, final int videoHeight, final int videoBitrate, final float videoFrameRate) {
    return new VideoMetadataImpl(videoWidth, videoHeight, videoBitrate, videoFrameRate);
  }

  /**
   * Creates a new instance of {@code VideoMetadata} with the specified video properties.
   *
   * @param videoWidth     the width of the video in pixels
   * @param videoHeight    the height of the video in pixels
   * @param videoFrameRate the frame rate of the video in frames per second (fps)
   * @return a new {@code VideoMetadata} instance containing the specified video properties
   */
  static VideoMetadata of(final int videoWidth, final int videoHeight, final float videoFrameRate) {
    return new VideoMetadataImpl(videoWidth, videoHeight, DEFAULT_BITRATE, videoFrameRate);
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
  static VideoMetadata of(final int width, final int height) {
    return new VideoMetadataImpl(width, height, NO_OP, NO_OP);
  }
}

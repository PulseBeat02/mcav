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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;

/**
 * Represents a functional interface for applying transformations or filters
 * to video data, defined by {@link StaticImage} as the video frame data and
 * {@link VideoMetadata} as associated metadata.
 * <p>
 * This interface extends {@code Filter<StaticImage, VideoMetadata>} and
 * leverages its contract for defining filtering behavior. Implementations of
 * this interface process video frames with metadata and apply their defined
 * filter logic.
 * <p>
 * As a functional interface, {@code VideoFilter} can be implemented via lambda
 * expressions or method references to encapsulate specific video processing
 * behaviors. Additionally, commonly used filters are provided as predefined
 * constants, such as {@code INVERT} and {@code GRAYSCALE}.
 * <p>
 * Constants:
 * - {@code INVERT}: A filter that inverts the colors of the video frame.
 * - {@code GRAYSCALE}: A filter that converts the video frame to grayscale.
 * <p>
 * Implementors can create custom video filters by implementing the
 * applyFilter(StaticImage, VideoMetadata) method to define new
 * transformation logic.
 */
@FunctionalInterface
public interface VideoFilter extends Filter<StaticImage, VideoMetadata> {
  /**
   * A predefined video filter that inverts the colors of a video frame.
   * <p>
   * This filter processes the given video frame data, represented by {@link StaticImage},
   * and applies an inversion of colors for each pixel. The resulting frame will appear
   * as a photographic negative of the original.
   * <p>
   * The color inversion is achieved using the {@link StaticImage#invertColors()} method.
   * <p>
   * Implementation adheres to the {@link VideoFilter} interface, ensuring compatibility
   * with video processing pipelines that utilize this interface for applying filters.
   */
  VideoFilter INVERT = (samples, metadata) -> samples.invertColors();

  /**
   * A predefined {@link VideoFilter} implementation that applies a transformation
   * to convert a video frame to grayscale.
   * <p>
   * This filter leverages the {@code toGrayscale} method of the provided
   * {@link StaticImage}. Implementers or users may adapt or refine the filter
   * to ensure it performs accurate grayscale conversions if intended.
   * <p>
   * GRAYSCALE is part of the standard filter constants available in {@link VideoFilter}
   * and can be applied directly to enhance or modify video frame data in
   * processing pipelines.
   */
  VideoFilter GRAYSCALE = (samples, metadata) -> samples.toGrayscale();

  /**
   * A predefined {@link VideoFilter} implementation that overlays the frame rate
   * value onto the video frames in real-time.
   * <p>
   * This filter calculates the frame rate based on the time elapsed
   * between consecutive frames and displays the frame rate as text on
   * the video frames. The text is rendered in a specific position
   * with customizable font size and color.
   * <p>
   * FRAME_RATE is part of the standard filter constants provided by {@link VideoFilter},
   * and it can be utilized in processing pipelines where monitoring or displaying
   * the current frame rate is necessary.
   * <p>
   * The frame rate is computed using the system's current time and the time
   * elapsed since the last frame was processed. This helps measure playback
   * performance and diagnose issues in video rendering pipelines.
   */
  VideoFilter FRAME_RATE = new FrameRateFilter();
}

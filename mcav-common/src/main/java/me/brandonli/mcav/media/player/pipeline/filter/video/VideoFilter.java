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

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;

/**
 * Represents a functional interface for applying transformations or filters
 * to video data, defined by {@link ImageBuffer} as the video frame data and
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
public interface VideoFilter extends Filter<ImageBuffer, VideoMetadata> {}

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
package me.brandonli.mcav.media.player.pipeline.step;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;

/**
 * Represents a processing step in a video processing pipeline.
 */
public interface VideoPipelineStep extends PipelineStep<ImageBuffer, VideoMetadata, VideoPipelineStep> {
  /**
   * Creates a new instance of {@code VideoPipelineStep} with the specified
   * next step and video filter.
   *
   * @param next   the next step in the pipeline. Can be null if this is the last step.
   * @param filter the video filter to be applied in this pipeline step.
   *
   * @return a new {@code VideoPipelineStep} instance that applies the given filter.
   */
  static VideoPipelineStep of(final VideoPipelineStep next, final VideoFilter filter) {
    return new VideoPipelineStepImpl(next, filter);
  }

  /**
   * Creates a {@code VideoPipelineStep} with a specified {@code VideoFilter}.
   *
   * @param filter the video filter to be applied in this pipeline step.
   *               Must not be null.
   * @return a new {@code VideoPipelineStep} instance that applies the given filter.
   */
  static VideoPipelineStep of(final VideoFilter filter) {
    return new VideoPipelineStepImpl(null, filter);
  }

  /**
   * A no-operation (no-op) implementation of {@link VideoPipelineStep}.
   */
  VideoPipelineStep NO_OP = new VideoPipelineStepImpl(null, (samples, metadata) -> {});
}

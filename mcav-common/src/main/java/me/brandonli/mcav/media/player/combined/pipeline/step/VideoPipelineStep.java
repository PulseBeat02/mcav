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
package me.brandonli.mcav.media.player.combined.pipeline.step;

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;

/**
 * Represents a processing step in a video processing pipeline.
 * This interface extends {@code PipelineStep} and operates specifically
 * on {@code StaticImage} data with associated {@code VideoMetadata}.
 * Each step in the pipeline applies a specific {@code VideoFilter}
 * to the input data.
 * <p>
 * Video pipeline steps can be chained together with each step
 * potentially delegating to a subsequent step.
 */
public interface VideoPipelineStep extends PipelineStep<StaticImage, VideoMetadata, VideoPipelineStep> {
  /**
   * Creates a new instance of {@code VideoPipelineStep} with the specified
   * next step and video filter.
   * <p>
   * This method is used to chain video processing steps, where each step
   * applies a specific {@code VideoFilter} to the input data and delegates
   * further processing to the next step in the chain, if provided.
   *
   * @param next   the next {@code VideoPipelineStep} in the chain, or {@code null}
   *               if this step is the last in the pipeline
   * @param filter the {@code VideoFilter} to be applied by this step
   * @return a new {@code VideoPipelineStep} instance that applies the given filter
   * and optionally delegates to the next step
   */
  static VideoPipelineStep of(final VideoPipelineStep next, final VideoFilter filter) {
    return new VideoPipelineStepImpl(next, filter);
  }

  /**
   * Creates a {@code VideoPipelineStep} with a specified {@code VideoFilter}.
   * The resulting step will not delegate to any subsequent step in the pipeline.
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
   * <p>
   * This constant represents a pipeline step that performs no processing on the
   * provided video frames or associated metadata. It acts as a placeholder or
   * terminator in a video processing pipeline. Specifically:
   * <p>
   * - It has no subsequent step in the pipeline.
   * - It uses an empty {@link VideoFilter} that performs no operations when its
   * `applyFilter` method is invoked.
   * <p>
   * Use this constant when a step in the pipeline is required but no
   * processing is necessary.
   */
  VideoPipelineStep NO_OP = new VideoPipelineStepImpl(null, (samples, metadata) -> {});
}

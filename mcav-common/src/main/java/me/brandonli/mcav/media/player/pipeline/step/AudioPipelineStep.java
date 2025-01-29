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
package me.brandonli.mcav.media.player.pipeline.step;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

/**
 * Represents a processing step in an audio pipeline.
 * <p>
 * Each step takes audio data in the form of a {@link ByteBuffer} and its associated metadata
 * of type {@link AudioMetadata}, applies a transformation or filtering operation, and passes
 * the processed data to the next step in the pipeline, if available.
 * <p>
 * This interface extends {@link PipelineStep} and provides additional static factory methods
 * for constructing audio pipeline steps with specified filters and optional subsequent steps.
 * <p>
 * Implementations of this interface are designed to process raw audio data sequentially, where
 * multiple steps can be chained to apply a series of transformations or filters.
 * <p>
 * Key characteristics and usages:
 * - Chaining: Supports linking subsequent steps in the pipeline for sequential processing.
 * - Filtering: Each step is tied to an {@link AudioFilter}, which defines the operation applied
 * to the audio data.
 * - Static Factory Methods: Provides methods for convenient instantiation of audio pipeline steps.
 * - Default No-Op Implementation: Includes a no-operation step that performs no processing.
 */
public interface AudioPipelineStep extends PipelineStep<ByteBuffer, AudioMetadata, AudioPipelineStep> {
  /**
   * Creates a new {@code AudioPipelineStep} with the specified next step and audio filter.
   * This method allows the construction of a processing step in the audio pipeline that
   * transforms audio data using the provided {@link AudioFilter}, and then delegates the
   * processing to the next pipeline step, if specified.
   *
   * @param next   the next {@link AudioPipelineStep} in the pipeline; can be {@code null}
   *               if this is the last step in the pipeline
   * @param filter the {@link AudioFilter} to apply in this step; must not be {@code null}
   * @return a new {@code AudioPipelineStep} that applies the given filter and links to the next step
   * @throws NullPointerException if the {@code filter} is {@code null}
   */
  static AudioPipelineStep of(final AudioPipelineStep next, final AudioFilter filter) {
    return new AudioPipelineStepImpl(next, filter);
  }

  /**
   * Creates a new instance of {@link AudioPipelineStep} with the specified {@link AudioFilter}.
   * <p>
   * This factory method constructs a pipeline step that applies the given filter to the audio data
   * during processing. The created step does not have a subsequent step in the pipeline.
   *
   * @param filter the {@link AudioFilter} to be applied in this pipeline step. Must not be null.
   * @return a new {@link AudioPipelineStep} instance configured with the specified filter.
   */
  static AudioPipelineStep of(final AudioFilter filter) {
    return new AudioPipelineStepImpl(null, filter);
  }

  /**
   * A no-operation (no-op) implementation of {@link AudioPipelineStep}.
   * <p>
   * This constant represents a pipeline step that performs no processing
   * on the provided audio buffer or metadata. It acts as a placeholder
   * or terminator in an audio processing pipeline. Specifically:
   * <p>
   * - It has no subsequent step in the pipeline.
   * - It uses an empty {@link AudioFilter} that performs no operations
   * when its `applyFilter` method is invoked.
   * <p>
   * Use this constant when a step in the pipeline is required but no
   * processing is necessary.
   */
  AudioPipelineStep NO_OP = new AudioPipelineStepImpl(null, (samples, metadata) -> {});
}

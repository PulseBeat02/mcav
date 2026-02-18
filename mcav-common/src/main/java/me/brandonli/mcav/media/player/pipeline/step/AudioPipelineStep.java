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

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

/**
 * Represents a processing step in an audio pipeline.
 */
public interface AudioPipelineStep extends PipelineStep<ByteBuffer, OriginalAudioMetadata, AudioPipelineStep> {
  /**
   * Creates a new {@code AudioPipelineStep} with the specified next step and audio filter.
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
   *
   * @param filter the {@link AudioFilter} to be applied in this pipeline step. Must not be null.
   * @return a new {@link AudioPipelineStep} instance configured with the specified filter.
   */
  static AudioPipelineStep of(final AudioFilter filter) {
    return new AudioPipelineStepImpl(null, filter);
  }

  /**
   * A no-operation (no-op) implementation of {@link AudioPipelineStep}.
   */
  AudioPipelineStep NO_OP = new AudioPipelineStepImpl(null, (samples, metadata) -> false);
}

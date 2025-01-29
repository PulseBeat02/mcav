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
package me.brandonli.mcav.media.player.combined.pipeline.builder;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.combined.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.combined.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;

/**
 * A concrete implementation of {@link AbstractPipelineStepBuilder} for constructing audio processing pipelines.
 * This builder is specifically designed to create {@link AudioPipelineStep} instances, chaining multiple
 * {@link AudioFilter} elements in reverse order to form a processing chain.
 * <p>
 * The resulting pipeline processes audio data represented as {@link ByteBuffer} along with its associated
 * {@link AudioMetadata}. Each filter in the chain will process the output of the previous filter,
 * forming a backward-linked chain where the last added filter is the first to be executed.
 * If no filters are added, the builder will return a no-operation pipeline step.
 */
public final class AudioPipelineStepBuilderImpl
  extends AbstractPipelineStepBuilder<ByteBuffer, AudioMetadata, AudioFilter, AudioPipelineStep> {

  AudioPipelineStepBuilderImpl() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public AudioPipelineStep build() {
    AudioPipelineStep chain = null;
    for (int i = this.filters.size() - 1; i >= 0; i--) {
      final AudioFilter filter = this.filters.get(i);
      if (chain == null) {
        chain = AudioPipelineStep.of(filter);
      } else {
        chain = AudioPipelineStep.of(chain, filter);
      }
    }
    return chain == null ? AudioPipelineStep.NO_OP : chain;
  }
}

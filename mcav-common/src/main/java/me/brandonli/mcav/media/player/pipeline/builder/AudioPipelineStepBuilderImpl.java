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
package me.brandonli.mcav.media.player.pipeline.builder;

import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link AudioPipelineStepBuilder}.
 */
public final class AudioPipelineStepBuilderImpl extends AudioPipelineStepBuilder {

  AudioPipelineStepBuilderImpl() {
    // nothing else to set up
  }

  /**
   * Creates an audio step that applies a filter and continues with the next step.
   *
   * @param next   the step that follows, or null for the last step
   * @param filter the filter of the step
   * @return the step
   */
  @Override
  protected AudioPipelineStep createStep(final @Nullable AudioPipelineStep next, final AudioFilter filter) {
    return AudioPipelineStep.of(next, filter);
  }

  /**
   * Gets {@link AudioPipelineStep#NO_OP}, the audio step built when no filter was added.
   *
   * @return the no-op audio step
   */
  @Override
  protected AudioPipelineStep createEmptyStep() {
    return AudioPipelineStep.NO_OP;
  }

  /**
   * Gets this builder typed as an audio builder.
   *
   * @return this builder
   */
  @Override
  protected AudioPipelineStepBuilder self() {
    return this;
  }
}

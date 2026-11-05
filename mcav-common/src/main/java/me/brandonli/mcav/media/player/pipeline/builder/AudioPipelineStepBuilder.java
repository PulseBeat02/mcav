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

/**
 * Builds an audio pipeline from {@link AudioFilter}s; create one with {@link PipelineBuilder#audio()}.
 *
 * <p>Builders are not thread-safe; use one per thread.
 *
 * @since 1.0.0
 */
public interface AudioPipelineStepBuilder {
  /**
   * Adds a filter to the end of the pipeline.
   *
   * @param filter the filter
   * @return this builder
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  AudioPipelineStepBuilder then(final AudioFilter filter);

  /**
   * Returns the number of filters added so far.
   *
   * @return the filter count
   * @since 1.0.0
   */
  int size();

  /**
   * Links the filters into a new chain, first filter first, retaining the filter instances. The builder is
   * unchanged; appending more filters later does not alter this chain.
   *
   * @return the first step, or {@link AudioPipelineStep#NO_OPERATION} if no filter was added
   * @since 1.0.0
   */
  AudioPipelineStep build();
}

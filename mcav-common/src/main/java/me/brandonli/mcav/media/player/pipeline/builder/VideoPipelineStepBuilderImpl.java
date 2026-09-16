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

import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VideoPipelineStepBuilder}.
 */
public final class VideoPipelineStepBuilderImpl extends VideoPipelineStepBuilder {

  VideoPipelineStepBuilderImpl() {
    // nothing else to set up
  }

  /**
   * Creates a video step that applies a filter and continues with the next step.
   *
   * @param next   the step that follows, or null for the last step
   * @param filter the filter of the step
   * @return the step
   */
  @Override
  protected VideoPipelineStep createStep(final @Nullable VideoPipelineStep next, final VideoFilter filter) {
    return VideoPipelineStep.of(next, filter);
  }

  /**
   * Gets {@link VideoPipelineStep#NO_OP}, the video step built when no filter was added.
   *
   * @return the no-op video step
   */
  @Override
  protected VideoPipelineStep createEmptyStep() {
    return VideoPipelineStep.NO_OP;
  }

  /**
   * Gets this builder typed as a video builder.
   *
   * @return this builder
   */
  @Override
  protected VideoPipelineStepBuilder self() {
    return this;
  }
}

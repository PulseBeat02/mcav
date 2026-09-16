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

import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One link of a pipeline: a filter plus a pointer to the next link. Players walk the chain with
 * {@link #processAll(Object, Object)} for every frame or sample chunk. Build chains with
 * {@link me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder}.
 *
 * @param <T> the type of data that flows through the pipeline
 * @param <M> the type of metadata that accompanies the data
 * @param <S> the type of the steps of this pipeline
 */
public interface PipelineStep<T, M, S extends PipelineStep<T, M, S>> {
  /**
   * Gets the step that follows this one.
   *
   * @return the next step, or null if this is the last step
   */
  @Nullable S next();

  /**
   * Checks whether this is the last step of the chain.
   *
   * @return true if no step follows
   */
  default boolean isLast() {
    final S next = this.next();
    return next == null;
  }

  /**
   * Checks whether this step is the empty pipeline, the {@code NO_OP} step of {@link AudioPipelineStep} or
   * {@link VideoPipelineStep}, which does nothing. Players skip preparing frames and samples for the empty pipeline,
   * because no filter would read them.
   *
   * @return true only for the empty pipeline
   */
  default boolean isNoOp() {
    return false;
  }

  /**
   * Applies the filter of this step only.
   *
   * @param buffer   the data
   * @param metadata the metadata of the data
   * @throws NullPointerException if the data or the metadata is null
   */
  void process(final T buffer, final M metadata);

  /**
   * Applies the filter of this step and of every step that follows, in order. Every filter runs, whatever the
   * filters before it returned.
   *
   * @param buffer   the data
   * @param metadata the metadata of the data
   * @throws NullPointerException if the data or the metadata is null
   */
  default void processAll(final T buffer, final M metadata) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    S step = this.self();
    while (step != null) {
      step.process(buffer, metadata);
      step = step.next();
    }
  }

  /**
   * Gets this step typed as the step type of the pipeline.
   *
   * @return this step
   */
  S self();
}

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
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One link of a pipeline: a filter plus a pointer to the next link. Players walk the chain with
 * {@link #processAll(Object, Object)} for every frame or sample chunk. Build chains with
 * {@link me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder}.
 *
 * <p>Implementations in this library are immutable; they are as thread-safe as their filters.
 *
 * @param <DataType>     the type of data that flows through the pipeline
 * @param <MetadataType> the type of metadata that accompanies the data
 * @param <StepType>     the type of the steps of this pipeline
 * @since 1.0.0
 */
public interface PipelineStep<DataType, MetadataType, StepType extends PipelineStep<DataType, MetadataType, StepType>> {
  /**
   * Returns the step that follows this one.
   *
   * @return the next step, or empty if this is the last step
   * @since 1.0.0
   */
  Optional<StepType> next();

  /**
   * Returns whether this is the last step of the chain.
   *
   * @return true if no step follows
   * @since 1.0.0
   */
  default boolean isLast() {
    final Optional<StepType> next = this.next();
    return next.isEmpty();
  }

  /**
   * Returns whether this step is the empty pipeline, the {@code NO_OPERATION} step of {@link AudioPipelineStep} or
   * {@link VideoPipelineStep}, which does nothing. Players skip preparing frames and samples for the empty pipeline,
   * because no filter would read them.
   *
   * @return true only for the empty pipeline
   * @since 1.0.0
   */
  default boolean isNoOperation() {
    return false;
  }

  /**
   * Applies the filter of this step only.
   *
   * @param buffer   the data
   * @param metadata the metadata of the data
   * @throws NullPointerException if the data or the metadata is null
   * @since 1.0.0
   */
  void process(final DataType buffer, final MetadataType metadata);

  /**
   * Applies the filter of this step and of every step that follows, in order. Every filter runs, whatever the
   * filters before it returned.
   *
   * @param buffer   the data
   * @param metadata the metadata of the data
   * @throws NullPointerException if the data or the metadata is null
   * @throws RuntimeException     if a step or filter fails; subsequent steps are not invoked
   * @since 1.0.0
   */
  default void processAll(final DataType buffer, final MetadataType metadata) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

    @Nullable StepType step = this.self();
    while (step != null) {
      step.process(buffer, metadata);
      final Optional<StepType> next = step.next();
      step = next.orElse(null);
    }
  }

  /**
   * Returns this step typed as the step type of the pipeline.
   *
   * @return this step
   * @since 1.0.0
   */
  StepType self();
}

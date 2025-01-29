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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a generic processing step in a data pipeline.
 *
 * @param <A> the type of the input data to be processed by this pipeline step
 * @param <B> the type of the metadata associated with the input data
 * @param <C> the type of the next pipeline step
 */
public interface PipelineStep<A, B, C> {
  /**
   * Retrieves the next step in the pipeline if present.
   * <p>
   * This method is used to traverse or retrieve the subsequent step
   * in a chained pipeline configuration. If no further steps exist,
   * it returns null, indicating the end of the pipeline.
   *
   * @return the next step in the pipeline, or null if this is the last step
   */
  @Nullable C next();

  /**
   * Determines whether this is the last step in the pipeline.
   *
   * @return {@code true} if there is no subsequent step in the pipeline,
   * {@code false} otherwise
   */
  default boolean isLast() {
    return this.next() == null;
  }

  /**
   * Processes the input data and associated metadata for this pipeline step.
   * The specific operation performed on the data and metadata depends on the
   * implementation of the pipeline step.
   *
   * @param buffer   the input data to be processed. The type of this parameter
   *                 is determined by the generic type {@code A}.
   * @param metadata the metadata associated with the input data. The type of
   *                 this parameter is determined by the generic type {@code B}.
   */
  void process(final A buffer, final B metadata);
}

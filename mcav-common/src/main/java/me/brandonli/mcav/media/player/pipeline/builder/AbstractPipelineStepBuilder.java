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

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;
import me.brandonli.mcav.media.player.pipeline.step.PipelineStep;

/**
 * An abstract builder class for constructing instances of {@link PipelineStep} implementations in a sequential manner.
 *
 * @param <T> the type representing the data to be processed in the pipeline
 * @param <M> the type representing the metadata associated with the data
 * @param <F> the type of filters implementing the {@link Filter} interface used in the pipeline
 * @param <S> the type of {@link PipelineStep} to be built by this builder
 */
public abstract class AbstractPipelineStepBuilder<T, M, F extends Filter<T, M>, S extends PipelineStep<T, M, S>> {

  /**
   * A list of filters that will be applied in the pipeline step.
   */
  protected final List<F> filters;

  /**
   * Constructs a new instance of the {@link AbstractPipelineStepBuilder} with an empty list of filters.
   */
  protected AbstractPipelineStepBuilder() {
    this.filters = new ArrayList<>();
  }

  /**
   * Adds the specified filter to the pipeline and returns the builder instance for chaining.
   *
   * @param filter the filter to be added to the processing pipeline
   * @return the current instance of the builder to allow for method chaining
   */
  public AbstractPipelineStepBuilder<T, M, F, S> then(final F filter) {
    this.filters.add(filter);
    return this;
  }

  /**
   * Constructs and returns a new instance of the pipeline step of type {@code S}.
   *
   * @return a fully constructed pipeline step of type {@code S}, representing the
   * sequence of filters added to the builder, or a no-operation pipeline
   * step if no filters were added.
   */
  public abstract S build();
}

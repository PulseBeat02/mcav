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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;
import me.brandonli.mcav.media.player.pipeline.step.PipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collects filters in order and links them into a chain of steps.
 *
 * @param <T> the type of data that flows through the pipeline
 * @param <M> the type of metadata that accompanies the data
 * @param <F> the type of filter
 * @param <S> the type of step
 * @param <B> the type of the concrete builder, returned from {@link #then(Filter)}
 */
public abstract class AbstractPipelineStepBuilder<
  T, M, F extends Filter<T, M>, S extends PipelineStep<T, M, S>, B extends AbstractPipelineStepBuilder<T, M, F, S, B>
> {

  private final List<F> filters;

  /**
   * Constructs an empty builder.
   */
  protected AbstractPipelineStepBuilder() {
    this.filters = new ArrayList<>();
  }

  /**
   * Adds a filter to the end of the pipeline.
   *
   * @param filter the filter
   * @return this builder
   */
  public B then(final F filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null");
    this.filters.add(filter);
    return this.self();
  }

  /**
   * Gets the number of filters added so far.
   *
   * @return the filter count
   */
  public int size() {
    return this.filters.size();
  }

  /**
   * Links the filters into a chain, first filter first.
   *
   * @return the first step, or the no-op step if no filter was added
   */
  public S build() {
    final int count = this.filters.size();
    if (count == 0) {
      return this.createEmptyStep();
    }
    final F last = this.filters.get(count - 1);
    S chain = this.createStep(null, last);
    for (int i = count - 2; i >= 0; i--) {
      final F filter = this.filters.get(i);
      chain = this.createStep(chain, filter);
    }
    return chain;
  }

  /**
   * Creates a step that applies a filter and continues with the next step.
   *
   * @param next   the step that follows, or null for the last step
   * @param filter the filter of the step
   * @return the step
   */
  protected abstract S createStep(final @Nullable S next, final F filter);

  /**
   * Gets the step returned when no filter was added.
   *
   * @return the no-op step
   */
  protected abstract S createEmptyStep();

  /**
   * Gets this builder typed as the concrete builder.
   *
   * @return this builder
   */
  protected abstract B self();
}

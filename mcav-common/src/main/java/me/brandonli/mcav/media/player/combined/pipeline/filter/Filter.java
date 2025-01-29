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
package me.brandonli.mcav.media.player.combined.pipeline.filter;

/**
 * Represents a functional interface for applying transformations or processing logic
 * to input data of type A, with optional metadata of type B.
 * <p>
 * This interface provides a generic contract for defining filters that operate on
 * a dataset and its associated metadata. It can be implemented for processing
 * a variety of media or data types, such as audio, video, or other custom data structures.
 * <p>
 * By being annotated with {@code @FunctionalInterface}, this interface can be used
 * as the assignment target for lambda expressions or method references, facilitating
 * concise and flexible implementation of filtering logic.
 *
 * @param <A> the type of data samples to be processed
 * @param <B> the type of metadata associated with the data samples
 */
@FunctionalInterface
public interface Filter<A, B> {
  /**
   * Applies a filter to the provided data samples, potentially transforming or processing
   * them based on the provided metadata.
   * <p>
   * This method serves as a generic contract for custom data filtering, allowing implementations
   * to define specific transformation or processing logic for the provided input.
   *
   * @param samples  the data samples to be processed
   * @param metadata the metadata associated with the data samples, which may guide the filtering process
   */
  void applyFilter(final A samples, final B metadata);
}

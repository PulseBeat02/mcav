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
package me.brandonli.mcav.media.player.pipeline.filter;

/**
 * Represents a functional interface for applying transformations or processing logic
 * to input data of type A, with optional metadata of type B.
 *
 * @param <A> the type of data samples to be processed
 * @param <B> the type of metadata associated with the data samples
 */
@FunctionalInterface
public interface Filter<A, B> {
  /**
   * Applies a filter to the provided data samples, potentially transforming or processing
   * them based on the provided metadata.
   *
   * @param samples  the data samples to be processed
   * @param metadata the metadata associated with the data samples, which may guide the filtering process
   * @return true if the filter was successfully applied or false if the samples should be discarded
   */
  boolean applyFilter(final A samples, final B metadata);
}

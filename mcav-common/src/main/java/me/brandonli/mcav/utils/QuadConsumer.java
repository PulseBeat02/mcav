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
package me.brandonli.mcav.utils;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A consumer that accepts four arguments and returns no result.
 *
 * @param <T> the type of the first argument to the operation
 * @param <U> the type of the second argument to the operation
 * @param <V> the type of the third argument to the operation
 * @param <W> the type of the fourth argument to the operation
 */
@FunctionalInterface
public interface QuadConsumer<T, U, V, W> {
  /**
   * Performs this operation on the given arguments.
   *
   * @param t the first input argument
   * @param u the second input argument
   * @param v the third input argument
   * @param w the fourth input argument
   */
  void accept(T t, U u, V v, W w);

  /**
   * Chains this {@code QuadConsumer} with another {@code QuadConsumer} to be executed after this one.
   *
   * @param after the {@code QuadConsumer} to be executed after this one
   * @return a new {@code QuadConsumer} that first executes this one, then the {@code after} consumer
   */
  default QuadConsumer<T, U, V, W> andThen(@Nullable final QuadConsumer<? super T, ? super U, ? super V, W> after) {
    if (after == null) {
      return this;
    }
    return (t, u, v, w) -> {
      this.accept(t, u, v, w);
      after.accept(t, u, v, w);
    };
  }
}

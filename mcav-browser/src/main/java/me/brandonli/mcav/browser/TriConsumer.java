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
package me.brandonli.mcav.browser;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A consumer that accepts three arguments and returns no result.
 *
 * @param <T> the type of the first argument to the operation
 * @param <U> the type of the second argument to the operation
 * @param <V> the type of the third argument to the operation
 */
@FunctionalInterface
public interface TriConsumer<T, U, V> {
  /**
   * Performs this operation on the given arguments.
   *
   * @param t the first input argument
   * @param u the second input argument
   * @param v the third input argument
   */
  void accept(T t, U u, V v);

  /**
   * Chains this {@code TriConsumer} with another {@code TriConsumer} to be executed after this one.
   *
   * @param after the {@code TriConsumer} to be executed after this one
   * @return a new {@code TriConsumer} that first executes this one, then the {@code after} consumer
   */
  default TriConsumer<T, U, V> andThen(@Nullable final TriConsumer<? super T, ? super U, ? super V> after) {
    if (after == null) {
      return this;
    }
    return (t, u, v) -> {
      this.accept(t, u, v);
      after.accept(t, u, v);
    };
  }
}

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
package me.brandonli.mcav.utils.immutable;

import com.google.common.base.Preconditions;

/**
 * A generic immutable tuple that holds two values of possibly different types.
 *
 * @param <A> the type of the first element in the pair
 * @param <B> the type of the second element in the pair
 */
public final class Pair<A, B> {

  private final A first;
  private final B second;

  Pair(final A first, final B second) {
    this.first = first;
    this.second = second;
  }

  /**
   * Creates a new {@code Pair} instance with the specified first and second elements.
   * Both elements must be non-null.
   *
   * @param <C>    the type of the first element in the pair
   * @param <D>    the type of the second element in the pair
   * @param first  the first element of the pair, must not be null
   * @param second the second element of the pair, must not be null
   * @return a new {@code Pair} instance containing the specified elements
   * @throws NullPointerException if either {@code first} or {@code second} is null
   */
  public static <C, D> Pair<C, D> pair(final C first, final D second) {
    Preconditions.checkNotNull(first);
    Preconditions.checkNotNull(second);
    return new Pair<>(first, second);
  }

  /**
   * Retrieves the first element of the pair.
   *
   * @return the first element of this pair, which is of type {@code A}
   */
  public A getFirst() {
    return this.first;
  }

  /**
   * Retrieves the second element stored in the pair.
   *
   * @return the second element of type {@code B} contained in the pair
   */
  public B getSecond() {
    return this.second;
  }
}

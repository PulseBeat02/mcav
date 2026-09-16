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
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable pair of two non-null values.
 *
 * @param <A> the type of the first value
 * @param <B> the type of the second value
 */
public final class Pair<A, B> {

  private final A first;
  private final B second;

  Pair(final A first, final B second) {
    this.first = first;
    this.second = second;
  }

  /**
   * Creates a pair.
   *
   * @param <C>    the type of the first value
   * @param <D>    the type of the second value
   * @param first  the first value
   * @param second the second value
   * @return the pair
   */
  public static <C, D> Pair<C, D> pair(final C first, final D second) {
    Preconditions.checkNotNull(first, "First value must not be null");
    Preconditions.checkNotNull(second, "Second value must not be null");
    return new Pair<>(first, second);
  }

  /**
   * Gets the first value.
   *
   * @return the first value
   */
  public A getFirst() {
    return this.first;
  }

  /**
   * Gets the second value.
   *
   * @return the second value
   */
  public B getSecond() {
    return this.second;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final Pair<?, ?> pair)) {
      return false;
    }
    return Objects.equals(this.first, pair.first) && Objects.equals(this.second, pair.second);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.first, this.second);
  }

  @Override
  public String toString() {
    return "Pair[" + this.first + ", " + this.second + "]";
  }
}

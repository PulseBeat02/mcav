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
package me.brandonli.mcav.utils.immutable;

/**
 * Represents an operation that accepts a single boolean-valued argument and performs
 * an action.
 *
 * <p>This is a functional interface whose functional method is {@link #accept(boolean)}.</p>
 */
@FunctionalInterface
public interface BooleanConsumer {
  /**
   * Performs an operation on the given boolean value.
   *
   * @param value the boolean input on which the operation is performed
   */
  void accept(boolean value);

  /**
   * Returns a composed {@code BooleanConsumer} that performs, in sequence, this operation followed by
   * the {@code after} operation. If performing either operation throws an exception, it is relayed
   * to the caller of the composed operation.
   *
   * @param after the operation to perform after this operation
   * @return a composed {@code BooleanConsumer} that performs in sequence this operation followed by
   * the {@code after} operation
   * @throws NullPointerException if {@code after} is null
   */
  default BooleanConsumer andThen(final BooleanConsumer after) {
    return (final boolean value) -> {
      this.accept(value);
      after.accept(value);
    };
  }
}

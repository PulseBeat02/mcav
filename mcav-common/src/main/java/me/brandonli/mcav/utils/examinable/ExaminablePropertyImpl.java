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
package me.brandonli.mcav.utils.examinable;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of {@link ExaminableProperty} that represents a property with a name and type.
 *
 * @param <T> the type of the property
 */
public class ExaminablePropertyImpl<T> implements ExaminableProperty<T> {

  private final String name;
  private final Class<T> type;

  ExaminablePropertyImpl(final String name, final Class<T> type) {
    this.name = name;
    this.type = type;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(final @Nullable Object obj) {
    if (obj instanceof final ExaminablePropertyImpl<?> other) {
      return this.name.equals(other.name) && this.type.equals(other.type);
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getName() {
    return this.name;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Class<T> getType() {
    return this.type;
  }
}

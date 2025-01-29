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

/**
 * An interface representing a property that can be examined.
 * @param <T> the type of the property
 */
public interface ExaminableProperty<T> {
  /**
   * Creates a new instance of {@link ExaminableProperty} with the specified name and type.
   *
   * @param name the name of the property
   * @param type the class type of the property
   * @param <T>  the type of the property
   * @return a new instance of {@link ExaminableProperty}
   */
  static <T> ExaminableProperty<T> property(final String name, final Class<T> type) {
    return new ExaminablePropertyImpl<>(name, type);
  }

  /**
   * Gets the name of the property.
   *
   * @return the name of the property
   */
  String getName();

  /**
   * Gets the value of the property.
   *
   * @return the value of the property
   */
  Class<T> getType();
}

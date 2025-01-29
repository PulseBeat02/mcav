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

import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Interface for objects that can be examined, providing a way to retrieve and set properties.
 */
public interface Examinable {
  /**
   * Retrieves a list of properties that can be examined.
   *
   * @return a list of examinable properties
   */
  List<? extends ExaminableProperty<?>> getExaminableProperties();

  /**
   * Retrieves the value of a specific property.
   *
   * @param property the property to retrieve
   * @param <T>      the type of the property value
   * @return the value of the property, or null if not set
   */
  <T> @Nullable T get(final ExaminableProperty<T> property);

  /**
   * Retrieves the value of a specific property, throwing an exception if the property is not set.
   *
   * @param property the property to retrieve
   * @param <T>      the type of the property value
   * @return the value of the property
   * @throws IllegalStateException if the property is not set
   */
  <T> T getOrThrow(final ExaminableProperty<T> property);

  /**
   * Sets the value of a specific property.
   *
   * @param property the property to set
   * @param value    the value to set
   * @param <T>      the type of the property value
   */
  <T> void set(final ExaminableProperty<T> property, final T value);

  /**
   * Checks if a specific property is set.
   *
   * @param property the property to check
   * @param <T>      the type of the property value
   * @return true if the property is set, false otherwise
   */
  <T> boolean has(final ExaminableProperty<T> property);
}

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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of {@link Examinable} that allows properties to be set and retrieved.
 * This class is thread-safe and uses a concurrent map to store properties.
 */
public class ExaminableObject implements Examinable {

  private final Map<ExaminableProperty<?>, Object> properties;

  /**
   * Constructs a new {@code ExaminableObject} with an empty set of properties.
   */
  public ExaminableObject() {
    this.properties = new ConcurrentHashMap<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> void set(final ExaminableProperty<T> property, final T value) {
    requireNonNull(value);
    this.properties.put(property, value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> boolean has(final ExaminableProperty<T> property) {
    return this.properties.containsKey(property);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<? extends ExaminableProperty<?>> getExaminableProperties() {
    return new ArrayList<>(this.properties.keySet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public @Nullable <T> T get(final ExaminableProperty<T> property) {
    return (T) this.properties.get(property);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T getOrThrow(final ExaminableProperty<T> property) {
    final T value = this.get(property);
    if (value != null) {
      return value;
    }
    final String name = property.getName();
    final Class<T> type = property.getType();
    final String simpleName = type.getSimpleName();
    final String msg = String.format("Property '%s' of type '%s' not found", name, simpleName);
    throw new ExaminableException(msg);
  }
}

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
package me.brandonli.mcav.media.source;

/**
 * Represents a type of {@link Source} that is not static, indicating that its
 * content or resource can change over time or is dynamically resolved.
 * <p>
 * A class implementing {@code DynamicSource} should provide behavior for
 * accessing dynamic resources or data, while guaranteeing that its static
 * state is always {@code false}.
 * <p>
 * By default, the {@code isStatic()} method is overridden to return
 * {@code false}, ensuring that any instance of a {@code DynamicSource}
 * is recognized as non-static.
 */
public interface DynamicSource extends Source {
  /**
   * {@inheritDoc}
   */
  @Override
  default boolean isStatic() {
    return false;
  }
}

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
 * The StaticSource interface represents a source identified as being static.
 * It extends the {@link Source} interface and provides a default implementation
 * that signifies the immutability of this source's nature.
 * <p>
 * A StaticSource is characterized by its unchanging state or content.
 * Implementers of this interface inherit the default behavior of the
 * {@code isStatic} method to always return {@code true}, indicating
 * that the source is static.
 */
public interface StaticSource extends Source {
  /**
   * {@inheritDoc}
   */
  @Override
  default boolean isStatic() {
    return true;
  }
}

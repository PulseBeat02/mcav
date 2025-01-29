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
 * The Source interface represents a general abstraction of a resource.
 * It defines methods for retrieving the resource, its name, and whether
 * it is static in nature.
 * <p>
 * This interface allows for implementation-specific definitions of the
 * resource while maintaining a common contract for querying static or
 * dynamic behavior.
 */
public interface Source {
  /**
   * Retrieves the resource associated with the source. The returned string typically
   * represents the location or identifier of the resource, such as a file path, URI, or
   * frame-related information.
   *
   * @return the resource as a string, representing its location or identifier.
   */
  String getResource();

  /**
   * Retrieves the name of the source.
   *
   * @return the name of the source as a {@code String}.
   */
  String getName();

  /**
   * Determines if the source is static.
   *
   * @return {@code true} if the source is static; {@code false} otherwise.
   */
  boolean isStatic();

  /**
   * Determines whether the source is dynamic. A dynamic source is the opposite of
   * a static source, meaning that its state or content can change over time.
   *
   * @return {@code true} if the source is dynamic; {@code false} otherwise.
   */
  default boolean isDynamic() {
    return !this.isStatic();
  }
}

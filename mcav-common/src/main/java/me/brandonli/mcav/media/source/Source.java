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
package me.brandonli.mcav.media.source;

/**
 * Something a player can play: a file, a URL, a capture device, a raw FFmpeg input, or frames supplied by code.
 *
 * <p>Sources are small immutable descriptions; they do not open anything themselves. Players receive the
 * {@link #getResource() resource string} and open it with their own backend. Create sources with the static
 * factories of the subtypes, such as {@link me.brandonli.mcav.media.source.file.FileSource#path(java.nio.file.Path)}
 * or {@link me.brandonli.mcav.media.source.uri.UriSource#uri(java.net.URI)}, or detect the right type from a
 * string with {@link SourceDetectionHelper}.
 */
public interface Source {
  /**
   * Gets the string a player opens, such as a file path, a URL, or a device index.
   *
   * @return the resource string
   */
  String getResource();

  /**
   * Gets a short name of the source type, such as {@code file} or {@code uri}, for log messages.
   *
   * @return the type name
   */
  String getName();

  /**
   * Checks whether the source is a fixed piece of media whose length is known, as opposed to a live stream,
   * a device, or generated frames.
   *
   * @return true for files and similar finite media
   */
  boolean isStatic();

  /**
   * Checks whether the source is a live or endless stream, the opposite of {@link #isStatic()}.
   *
   * @return true for streams, devices, and generated frames
   */
  default boolean isDynamic() {
    return !this.isStatic();
  }
}

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
 * Represents a direct FFmpeg source with a format and resource locator.
 */
public interface FFmpegDirectSource extends StaticSource {
  /**
   * Retrieves the Media Resource Locator (MRL) associated with the source.
   *
   * @return the MRL as a String, representing the resource location or identifier.
   */
  String getMrl();

  /**
   * Retrieves the format associated with this source.
   *
   * @return the format as a String, which typically describes the type or structure of the resource.
   */
  String getFormat();

  /**
   * {@inheritDoc}
   */
  @Override
  default String getName() {
    return "mrl";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  default String getResource() {
    return this.getMrl();
  }

  /**
   * Creates a new {@link FFmpegDirectSource} instance using the specified Media Resource Locator (MRL)
   * and format details.
   *
   * @param mrl    the Media Resource Locator (MRL) as a string, representing the resource's location or identifier.
   * @param format the format of the resource as a string, typically describing its type or structure.
   * @return a new {@link FFmpegDirectSource} instance initialized with the given MRL and format.
   */
  static FFmpegDirectSource mrl(final String mrl, final String format) {
    return new FFmpegDirectSourceImpl(mrl, format);
  }
}

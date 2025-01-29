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
package me.brandonli.mcav.json.ytdlp;

import java.io.IOException;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * Interface for parsing YTDLP (YouTube-DL Parser) data.
 * <p>
 * This interface defines a method for parsing input data and returning a {@link URLParseDump} object.
 * </p>
 */
public interface YTDLPParser {
  /**
   * Parses the given input and returns a {@link URLParseDump} object.
   *
   * @param input the input to parse
   * @param arguments additional arguments for parsing that can be null or empty
   *
   * @return a {@link URLParseDump} object containing the parsed data
   * @throws IOException if an I/O error occurs during parsing
   */
  URLParseDump parse(final UriSource input, final String... arguments) throws IOException;

  /**
   * Parses the given input and returns a {@link URLParseDump} object.
   *
   * @return a {@link URLParseDump} object containing the parsed data
   */
  static YTDLPParser simple() {
    return YTDLPParserImpl.INSTANCE;
  }
}

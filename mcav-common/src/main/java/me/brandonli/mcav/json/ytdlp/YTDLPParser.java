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
 * Resolves the streams behind a web page URL, such as a YouTube video, with yt-dlp.
 *
 * <p>yt-dlp is run as an external process, so parsing blocks for a few seconds and must not be called on a
 * server main thread. The result lists every available stream; pick one with a
 * {@link me.brandonli.mcav.json.ytdlp.strategy.StrategySelector}.
 *
 * <pre>{@code
 * final URI page = URI.create("https://youtu.be/...");
 * final UriSource source = UriSource.uri(page);
 * final YTDLPParser parser = YTDLPParser.simple();
 * final URLParseDump dump = parser.parse(source);
 * final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
 * final Format video = selector.getVideoSource(dump);
 * }</pre>
 */
public interface YTDLPParser {
  /**
   * Runs yt-dlp on a URL and parses the JSON it prints. Only the first video of a playlist is resolved.
   *
   * @param input     the URL of the web page, which must be an absolute {@code http} or {@code https} URL
   * @param arguments extra command line options for yt-dlp, such as {@code --cookies-from-browser}; they are placed
   *                  before the URL, which yt-dlp always reads as a URL and never as an option
   * @return the parsed metadata and streams
   * @throws IOException              if yt-dlp cannot be installed or run, or does not finish within two minutes
   * @throws YTDLPParseException      if yt-dlp reports an error or prints no usable JSON
   * @throws IllegalArgumentException if the input is not an absolute {@code http} or {@code https} URL
   * @throws IllegalStateException    if the parser runs the yt-dlp of the library, as {@link #simple()} does, and the
   *                                  library is still preparing yt-dlp in the background; wait for
   *                                  {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(me.brandonli.mcav.capability.Capability)}
   *                                  with {@link me.brandonli.mcav.capability.Capability#YT_DLP} or try again shortly
   */
  URLParseDump parse(final UriSource input, final String... arguments) throws IOException;

  /**
   * Gets the shared parser, which uses the yt-dlp installed by the library. It refuses to parse while the library
   * still prepares yt-dlp in the background, see {@link #parse(UriSource, String...)}.
   *
   * @return the parser
   */
  static YTDLPParser simple() {
    return YTDLPParserImpl.INSTANCE;
  }
}

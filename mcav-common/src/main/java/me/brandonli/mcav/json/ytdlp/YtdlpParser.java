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

import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.runtime.ProcessException;

/**
 * Resolves the streams behind a web page URL, such as a YouTube video, with yt-dlp.
 *
 * <p>yt-dlp is run as an external process, so parsing blocks for a few seconds and must not be called on a
 * server main thread. The result lists every available stream; pick one with a
 * {@link me.brandonli.mcav.json.ytdlp.strategy.StrategySelector}. The shared default parser can serve concurrent
 * calls, each with its own command process and result. Returned metadata is mutable and belongs to the caller;
 * do not mutate source settings or the argument array while a parse uses them.
 *
 * {@snippet :
 * final String address = "https://youtu.be/...";
 * final URI page = URI.create(address);
 * final UriSource source = UriSource.uri(page);
 * final YtdlpParser parser = YtdlpParser.simple();
 * final UrlParseDump dump = parser.parse(source);
 * final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
 * final Format video = selector.getVideoSource(dump);
 * }
 *
 * <p>Implementations in this library are thread-safe.
 *
 * @since 1.0.0
 */
public interface YtdlpParser {
  /**
   * Runs yt-dlp on a URL and parses the first JSON output line. The default command disables playlists on
   * watch pages and selects the first playlist entry; supplied options can override these command defaults.
   *
   * @param input     the non-null web-page source with an absolute {@code http} or {@code https} URI
   * @param arguments a non-null array of non-null extra yt-dlp options, passed verbatim after defaults and
   *                  before the URL, which yt-dlp always reads as a URL and never as an option
   * @return the parsed metadata and streams
   * @throws InstallationException    if yt-dlp cannot be installed
   * @throws DownloadException        if the download of yt-dlp fails
   * @throws FileOperationException   if the downloaded yt-dlp cannot be moved into place
   * @throws ProcessException         if yt-dlp cannot be run, or does not finish within two minutes
   * @throws YtdlpParseException      if yt-dlp reports an error or prints no usable JSON
   * @throws IllegalArgumentException if the input is not an absolute {@code http} or {@code https} URL
   * @throws IllegalStateException    if the parser runs the yt-dlp of the library, as {@link #simple()} does, and the
   *                                  library is still preparing yt-dlp in the background; wait for
   *                                  {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(me.brandonli.mcav.capability.Capability)}
   *                                  with {@link me.brandonli.mcav.capability.Capability#YT_DLP} or try again shortly
   * @throws NullPointerException     if {@code input}, the argument array or an argument is null
   * @since 1.0.0
   */
  UrlParseDump parse(final UriSource input, final String... arguments);

  /**
   * Returns the shared parser, which uses the yt-dlp installed by the library. It refuses to parse while the library
   * still prepares yt-dlp in the background, see {@link #parse(UriSource, String...)}.
   *
   * @return the parser
   * @since 1.0.0
   */
  static YtdlpParser simple() {
    return ExecutableYtdlpParser.INSTANCE;
  }
}

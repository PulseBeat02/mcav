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
/**
 * Resolves web-page media through the installed yt-dlp program.
 *
 * <p>Call {@link me.brandonli.mcav.json.ytdlp.YtdlpParser#simple()} and parse an absolute HTTP or HTTPS
 * {@link me.brandonli.mcav.media.source.uri.UriSource}. Parsing blocks on a child process and returns immutable
 * {@link me.brandonli.mcav.json.ytdlp.format.UrlParseDump} metadata; use a
 * {@link me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy} to choose streams. The parser interface contains
 * an example. The default parser waits at most two minutes for each command and reports nonzero exits or
 * unusable output as {@link me.brandonli.mcav.json.ytdlp.YtdlpParseException}.
 *
 * <p>The shared parser may run concurrent calls. During normal bootstrap it refuses to run while the library
 * is preparing yt-dlp; use capability readiness notification instead of blocking a server's main thread.
 * Each call returns its own result, while the executable remains in the shared installation cache.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.json.ytdlp;

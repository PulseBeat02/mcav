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
package me.brandonli.mcav.json.ytdlp.format;

/**
 * Download hints yt-dlp reports for a stream.
 *
 * <p>This is a mutable Gson data model, not a validated media object. Nullable fields may be null when absent;
 * primitive fields default to zero or false, which does not distinguish missing values from reported ones.
 * Instances and their nested lists are not synchronized; copy or coordinate them before concurrent mutation.
 */
public class DownloaderOptions {

  DownloaderOptions() {}

  /** The size in bytes of the chunks yt-dlp requests the stream in, which avoids the throttling of some sites, or zero if the stream is requested at once. */
  public int http_chunk_size;
}

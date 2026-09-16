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

import com.google.gson.annotations.SerializedName;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The HTTP headers yt-dlp used to fetch a stream, which a player has to send as well.
 */
public class HttpHeaders {

  HttpHeaders() {
    // populated by Gson
  }

  /** The {@code User-Agent} header, which names the browser yt-dlp pretended to be. */
  @SerializedName("User-Agent")
  public @Nullable String userAgent;

  /** The {@code Accept} header, which lists the content types the request accepts. */
  @SerializedName("Accept")
  public @Nullable String accept;

  /** The {@code Accept-Language} header, which lists the preferred languages. */
  @SerializedName("Accept-Language")
  public @Nullable String acceptLanguage;

  /** The {@code Sec-Fetch-Mode} header, which some sites check to tell browsers from other clients. */
  @SerializedName("Sec-Fetch-Mode")
  public @Nullable String secFetchMode;
}

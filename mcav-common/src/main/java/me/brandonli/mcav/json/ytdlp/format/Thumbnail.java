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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One thumbnail of a video, as listed by yt-dlp.
 */
public class Thumbnail {

  Thumbnail() {
    // populated by Gson
  }

  /** The URL of the image. */
  public @Nullable String url;
  /** How strongly yt-dlp prefers the thumbnail over the others, where higher is better. */
  public int preference;
  /** The identifier of the thumbnail among the thumbnails of the video. */
  public @Nullable String id;
  /** The height of the image in pixels, or zero if unknown. */
  public int height;
  /** The width of the image in pixels, or zero if unknown. */
  public int width;
  /** The resolution of the image as text, such as {@code 1920x1080}, or null if unknown. */
  public @Nullable String resolution;
}

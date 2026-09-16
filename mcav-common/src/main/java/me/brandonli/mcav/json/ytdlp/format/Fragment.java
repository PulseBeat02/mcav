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
 * One fragment of a segmented stream, as listed by yt-dlp.
 */
public class Fragment {

  Fragment() {
    // populated by Gson
  }

  /** The URL of the fragment, or null if yt-dlp did not report one. */
  public @Nullable String url;
  /** The length of the fragment in seconds. */
  public double duration;
}

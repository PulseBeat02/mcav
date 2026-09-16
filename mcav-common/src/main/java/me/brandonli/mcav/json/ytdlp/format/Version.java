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
 * The version of yt-dlp that produced the metadata.
 */
public class Version {

  Version() {
    // populated by Gson
  }

  /** The version of yt-dlp, such as {@code 2025.09.05}. */
  public @Nullable String version;
  /** The Git commit the running yt-dlp was built from, or null for a release build. */
  public @Nullable Object current_git_head;
  /** The Git commit of the release. */
  public @Nullable String release_git_head;
  /** The repository the build came from, such as {@code yt-dlp/yt-dlp}. */
  public @Nullable String repository;
}

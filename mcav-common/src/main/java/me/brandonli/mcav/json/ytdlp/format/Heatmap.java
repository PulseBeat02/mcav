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
 * One entry of the replay heat map of a video, as listed by yt-dlp.
 */
public class Heatmap {

  Heatmap() {
    // populated by Gson
  }

  /** Where the entry starts, in seconds from the start of the video. */
  public double start_time;
  /** Where the entry ends, in seconds from the start of the video. */
  public double end_time;
  /** How often this part of the video was replayed, relative to the most replayed part, from 0 to 1. */
  public double value;
}

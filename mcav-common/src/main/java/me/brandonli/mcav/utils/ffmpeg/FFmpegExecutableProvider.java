/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils.ffmpeg;

import java.nio.file.Path;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;

/**
 * Provides the path to the FFmpeg executable required for executing FFmpeg-related commands.
 */
public final class FFmpegExecutableProvider {

  private static final Path FFMPEG_PATH;

  static {
    final String path = Loader.load(ffmpeg.class);
    FFMPEG_PATH = Path.of(path);
  }

  /**
   * Returns the path to the FFmpeg executable.
   *
   * @return the path to the FFmpeg executable as a {@code Path} object
   */
  public static Path getFFmpegPath() {
    return FFMPEG_PATH;
  }
}

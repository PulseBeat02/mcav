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
package me.brandonli.mcav.media.source.ffmpeg;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.source.DynamicSource;

/**
 * A raw FFmpeg input with an explicit input format, for inputs FFmpeg cannot probe on its own, such as screen
 * capture ({@code gdigrab} on Windows, {@code x11grab} on Linux, {@code avfoundation} on macOS) or raw pipes.
 * Only {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#ffmpeg()} understands these sources. Such inputs
 * are live and have no fixed length, so the source is dynamic.
 */
public interface FFmpegDirectSource extends DynamicSource {
  /**
   * Creates a raw FFmpeg source.
   *
   * @param mrl    the input FFmpeg opens, such as {@code desktop} for {@code gdigrab}
   * @param format the FFmpeg input format, such as {@code gdigrab}
   * @return the source
   */
  static FFmpegDirectSource mrl(final String mrl, final String format) {
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    Preconditions.checkNotNull(format, "Format must not be null");
    final boolean blank = format.isBlank();
    Preconditions.checkArgument(!blank, "Format must not be blank");
    return new FFmpegDirectSourceImpl(mrl, format);
  }

  /**
   * Gets the input FFmpeg opens.
   *
   * @return the media resource locator
   */
  String getMrl();

  /**
   * Gets the FFmpeg input format.
   *
   * @return the format name
   */
  String getFormat();

  @Override
  default String getName() {
    return "ffmpeg";
  }

  @Override
  default String getResource() {
    return this.getMrl();
  }
}

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

import java.util.regex.Pattern;
import me.brandonli.mcav.media.source.SourceDetector;

/**
 * A source detector for FFmpeg direct sources, separated by two pipes ("||").
 */
public class FFmpegDirectSourceDetector implements SourceDetector<FFmpegDirectSource> {

  private static final String SPLIT_PATTERN = Pattern.quote("||");

  /**
   * Constructs a new {@link FFmpegDirectSourceDetector}.
   */
  public FFmpegDirectSourceDetector() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    final String[] split = raw.split(SPLIT_PATTERN);
    return split.length == 2;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FFmpegDirectSource createSource(final String raw) {
    final String[] split = raw.split(SPLIT_PATTERN);
    final String format = split[0];
    final String mrl = split[1];
    return FFmpegDirectSource.mrl(mrl, format);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}

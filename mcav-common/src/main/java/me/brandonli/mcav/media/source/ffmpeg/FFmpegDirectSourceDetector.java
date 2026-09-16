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
import java.util.regex.Pattern;
import me.brandonli.mcav.media.source.SourceDetector;

/**
 * Detects raw FFmpeg inputs written as {@code format||input}, such as {@code gdigrab||desktop}.
 */
public class FFmpegDirectSourceDetector implements SourceDetector<FFmpegDirectSource> {

  private static final Pattern SEPARATOR = Pattern.compile(Pattern.quote("||"));

  /**
   * Constructs a new detector.
   */
  public FFmpegDirectSourceDetector() {
    // stateless
  }

  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    final String[] parts = SEPARATOR.split(raw, -1);
    if (parts.length != 2) {
      return false;
    }

    final String format = parts[0];
    final String mrl = parts[1];
    final boolean formatBlank = format.isBlank();
    final boolean mrlBlank = mrl.isBlank();
    return !formatBlank && !mrlBlank;
  }

  @Override
  public FFmpegDirectSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    final String[] parts = SEPARATOR.split(raw, -1);
    Preconditions.checkArgument(parts.length == 2, "Expected format||input but got %s", raw);
    final String format = parts[0];
    final String mrl = parts[1];
    return FFmpegDirectSource.mrl(mrl, format);
  }

  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}

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
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class FfmpegDirectSourceDetector implements SourceDetector<FfmpegDirectSource> {

  private static final String RESOURCE_NULL_MESSAGE = "Resource must not be null!";

  private static final Pattern SEPARATOR = Pattern.compile(Pattern.quote("||"));
  private static final int PARTS = 2;
  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  /**
   * Creates a detector.
   *
   * @since 1.0.0
   */
  public FfmpegDirectSourceDetector() {}

  /**
   * Returns whether the string has the form {@code format||input} with both parts non-blank.
   *
   * @param resource the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @throws NullPointerException if {@code resource} is null
   * @since 1.0.0
   */
  @Override
  public boolean isDetectedSource(final String resource) {
    Preconditions.checkNotNull(resource, RESOURCE_NULL_MESSAGE);

    final String[] parts = SEPARATOR.split(resource, KEEP_TRAILING_EMPTY_PARTS);
    if (parts.length != PARTS) {
      return false;
    }

    final String format = parts[0];
    final String mrl = parts[1];
    final boolean formatBlank = format.isBlank();
    final boolean mrlBlank = mrl.isBlank();
    return !formatBlank && !mrlBlank;
  }

  /**
   * Creates an FFmpeg source from the format and the input on either side of {@code ||}.
   *
   * @param resource the string entered by a user
   * @return the source
   * @throws IllegalArgumentException if there is not exactly one {@code ||} separator or the format is blank
   * @throws NullPointerException     if {@code resource} is null
   * @since 1.0.0
   */
  @Override
  public FfmpegDirectSource createSource(final String resource) {
    Preconditions.checkNotNull(resource, RESOURCE_NULL_MESSAGE);

    final String[] parts = SEPARATOR.split(resource, KEEP_TRAILING_EMPTY_PARTS);
    Preconditions.checkArgument(parts.length == PARTS, "Raw source %s must have the form format||input!", resource);

    final String format = parts[0];
    final String mrl = parts[1];
    return FfmpegDirectSource.mrl(mrl, format);
  }

  /**
   * Returns {@link SourceDetector#HIGH_PRIORITY}, because the {@code ||} separator marks a direct FFmpeg source on purpose.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}

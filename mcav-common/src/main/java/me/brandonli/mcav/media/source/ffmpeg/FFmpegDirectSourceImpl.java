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

import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link FFmpegDirectSource}.
 */
public final class FFmpegDirectSourceImpl implements FFmpegDirectSource {

  private final String mrl;
  private final String format;

  FFmpegDirectSourceImpl(final String mrl, final String format) {
    this.mrl = mrl;
    this.format = format;
  }

  @Override
  public String getFormat() {
    return this.format;
  }

  @Override
  public String getMrl() {
    return this.mrl;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final FFmpegDirectSourceImpl source)) {
      return false;
    }
    return this.mrl.equals(source.mrl) && this.format.equals(source.format);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.mrl, this.format);
  }

  @Override
  public String toString() {
    return "FFmpegDirectSource[" + this.format + "||" + this.mrl + "]";
  }
}

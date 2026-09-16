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
package me.brandonli.mcav.json.ytdlp.strategy;

import com.google.common.base.Preconditions;
import java.util.Optional;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;

/**
 * The default {@link StrategySelector}.
 */
public final class StrategySelectorImpl implements StrategySelector {

  private final FormatStrategy audio;
  private final FormatStrategy video;

  StrategySelectorImpl(final FormatStrategy audio, final FormatStrategy video) {
    this.audio = audio;
    this.video = video;
  }

  @Override
  public FormatStrategy getAudioStrategy() {
    return this.audio;
  }

  @Override
  public FormatStrategy getVideoStrategy() {
    return this.video;
  }

  @Override
  public Format getAudioSource(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    final Optional<Format> selected = this.audio.select(dump);
    if (selected.isEmpty()) {
      final String description = describe(dump);
      throw new NoMatchingFormatException("No audio stream matches the strategy for " + description);
    }
    return selected.get();
  }

  @Override
  public Format getVideoSource(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    final Optional<Format> selected = this.video.select(dump);
    if (selected.isEmpty()) {
      final String description = describe(dump);
      throw new NoMatchingFormatException("No video stream matches the strategy for " + description);
    }
    return selected.get();
  }

  private static String describe(final URLParseDump dump) {
    final String title = dump.title;
    final String url = dump.webpage_url;
    if (title != null) {
      return title;
    }
    if (url != null) {
      return url;
    }
    return "the media";
  }
}

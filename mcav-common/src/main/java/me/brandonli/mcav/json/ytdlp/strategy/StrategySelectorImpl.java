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
package me.brandonli.mcav.json.ytdlp.strategy;

import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;

/**
 * Represents the default implementation of the {@link StrategySelector} interface.
 */
public final class StrategySelectorImpl implements StrategySelector {

  private final FormatStrategy audio;
  private final FormatStrategy video;

  StrategySelectorImpl(final FormatStrategy audio, final FormatStrategy video) {
    this.audio = audio;
    this.video = video;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FormatStrategy getAudioStrategy() {
    return this.audio;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FormatStrategy getVideoStrategy() {
    return this.video;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Format getAudioSource(final URLParseDump dump) {
    return this.audio.select(dump).orElseThrow();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Format getVideoSource(final URLParseDump dump) {
    return this.video.select(dump).orElseThrow();
  }
}

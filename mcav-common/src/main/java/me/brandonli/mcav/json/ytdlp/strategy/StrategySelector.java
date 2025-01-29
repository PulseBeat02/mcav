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

import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;

/**
 * StrategySelector is an interface that defines methods for selecting audio and video strategies
 * and sources based on a given URLParseDump.
 * <p>
 * Implementations of this interface can provide different strategies for selecting formats and
 * sources.
 * </p>
 */
public interface StrategySelector {
  /**
   * Get the audio strategy.
   *
   * @return the audio strategy
   */
  FormatStrategy getAudioStrategy();

  /**
   * Get the video strategy.
   *
   * @return the video strategy
   */
  FormatStrategy getVideoStrategy();

  /**
   * Get the audio source from the given URLParseDump.
   *
   * @param dump the URLParseDump to parse
   * @return the audio source format
   */
  Format getAudioSource(final URLParseDump dump);

  /**
   * Get the video source from the given URLParseDump.
   *
   * @param dump the URLParseDump to parse
   * @return the video source format
   */
  Format getVideoSource(final URLParseDump dump);

  /**
   * Constructs a new StrategySelector with the given audio and video strategies.
   *
   * @param audio the audio strategy
   * @param video the video strategy
   * @return the new StrategySelector
   */
  static StrategySelector of(final FormatStrategy audio, final FormatStrategy video) {
    return new StrategySelectorImpl(audio, video);
  }
}

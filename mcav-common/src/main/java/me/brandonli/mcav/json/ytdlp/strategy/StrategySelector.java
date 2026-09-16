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
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;

/**
 * Pairs an audio strategy with a video strategy, so both streams of a video can be selected together.
 *
 * <pre><code>
 *   final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
 *   final Format audio = selector.getAudioSource(dump);
 *   final Format video = selector.getVideoSource(dump);
 * </code></pre>
 */
public interface StrategySelector {
  /**
   * Gets the strategy used for audio.
   *
   * @return the audio strategy
   */
  FormatStrategy getAudioStrategy();

  /**
   * Gets the strategy used for video.
   *
   * @return the video strategy
   */
  FormatStrategy getVideoStrategy();

  /**
   * Selects the audio stream.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected stream
   * @throws NoMatchingFormatException if no stream matches the audio strategy
   */
  Format getAudioSource(final URLParseDump dump);

  /**
   * Selects the video stream.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected stream
   * @throws NoMatchingFormatException if no stream matches the video strategy
   */
  Format getVideoSource(final URLParseDump dump);

  /**
   * Creates a selector.
   *
   * @param audio the strategy for audio
   * @param video the strategy for video
   * @return the selector
   */
  static StrategySelector of(final FormatStrategy audio, final FormatStrategy video) {
    Preconditions.checkNotNull(audio, "Audio strategy must not be null");
    Preconditions.checkNotNull(video, "Video strategy must not be null");
    return new StrategySelectorImpl(audio, video);
  }
}

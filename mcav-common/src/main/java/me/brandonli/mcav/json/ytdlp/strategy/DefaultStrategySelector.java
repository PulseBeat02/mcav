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
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link StrategySelector}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class DefaultStrategySelector implements StrategySelector {

  private static final String DUMP_NULL_MESSAGE = "Dump must not be null!";

  private final FormatStrategy audio;
  private final FormatStrategy video;

  DefaultStrategySelector(final FormatStrategy audio, final FormatStrategy video) {
    this.audio = audio;
    this.video = video;
  }

  /**
   * Selects the audio stream of {@code dump} with the audio strategy of this selector.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected format object from the dump, borrowed rather than copied
   * @throws NoMatchingFormatException if no stream matches the audio strategy
   * @throws NullPointerException      if {@code dump} is null
   * @since 1.0.0
   */
  @Override
  public Format getAudioSource(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, DUMP_NULL_MESSAGE);

    final Optional<Format> selected = this.audio.select(dump);
    final @Nullable Format format = selected.orElse(null);
    if (format == null) {
      final String description = describe(dump);
      final String message = "No audio stream of %s matches the strategy!".formatted(description);
      throw new NoMatchingFormatException(message);
    }

    return format;
  }

  /**
   * Selects the video stream of {@code dump} with the video strategy of this selector.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected format object from the dump, borrowed rather than copied
   * @throws NoMatchingFormatException if no stream matches the video strategy
   * @throws NullPointerException      if {@code dump} is null
   * @since 1.0.0
   */
  @Override
  public Format getVideoSource(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, DUMP_NULL_MESSAGE);

    final Optional<Format> selected = this.video.select(dump);
    final @Nullable Format format = selected.orElse(null);
    if (format == null) {
      final String description = describe(dump);
      final String message = "No video stream of %s matches the strategy!".formatted(description);
      throw new NoMatchingFormatException(message);
    }

    return format;
  }

  private static String describe(final UrlParseDump dump) {
    final Optional<String> titleValue = dump.getTitle();
    final Optional<String> urlValue = dump.getWebpageUrl();
    final @Nullable String title = titleValue.orElse(null);
    final @Nullable String url = urlValue.orElse(null);
    if (title != null) {
      return title;
    }

    if (url != null) {
      return url;
    }

    return "the media";
  }

  /**
   * Returns the strategy this selector picks audio streams with.
   *
   * @return the audio strategy
   * @since 1.0.0
   */
  @Override
  public FormatStrategy getAudioStrategy() {
    return this.audio;
  }

  /**
   * Returns the strategy this selector picks video streams with.
   *
   * @return the video strategy
   * @since 1.0.0
   */
  @Override
  public FormatStrategy getVideoStrategy() {
    return this.video;
  }
}

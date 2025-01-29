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

import java.util.Comparator;
import java.util.Optional;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;

/**
 * Strategy for selecting a format from a {@link URLParseDump}.
 * <p>
 * This interface defines a method to select a format based on the provided {@link URLParseDump}.
 * Implementations can provide different strategies for selecting formats.
 * </p>
 */
@FunctionalInterface
public interface FormatStrategy {
  /**
   * Selects a format from the given {@link URLParseDump}.
   *
   * @param dump the {@link URLParseDump} to parse
   * @return an {@link Optional} containing the selected format, or an empty {@link Optional} if no format
   */
  Optional<Format> select(final URLParseDump dump);

  /**
   * Selects the lowest quality audio format from the given {@link URLParseDump}.
   */
  FormatStrategy LOWEST_QUALITY_AUDIO = dump ->
    dump.formats.stream().filter(format -> !format.audio_ext.equals("none")).min(Comparator.comparingDouble(format -> format.quality));

  /**
   * Selects the lowest quality video format from the given {@link URLParseDump}.
   */
  FormatStrategy LOWEST_QUALITY_VIDEO = dump ->
    dump.formats.stream().filter(format -> !format.video_ext.equals("none")).min(Comparator.comparingDouble(format -> format.quality));

  /**
   * A {@link FormatStrategy} that selects the audio format with the highest quality from a given
   * {@link URLParseDump}, excluding formats with no audio extension.
   * <p>
   * This strategy filters out formats where the audio extension is "none" and selects the format
   * with the maximum quality value based on a comparator.
   * </p>
   */
  FormatStrategy BEST_QUALITY_AUDIO = dump ->
    dump.formats.stream().filter(format -> !format.audio_ext.equals("none")).max(Comparator.comparingDouble(format -> format.quality));

  /**
   * A {@link FormatStrategy} that selects the video format with the highest quality
   * from the provided {@link URLParseDump}.
   * <p>
   * This strategy filters out formats where the video extension is "none" and
   * selects the format with the highest quality value.
   * <p>
   * The quality comparison is based on a descending order, ensuring the
   * highest quality video format is chosen.
   */
  FormatStrategy BEST_QUALITY_VIDEO = dump ->
    dump.formats.stream().filter(format -> !format.video_ext.equals("none")).max(Comparator.comparingDouble(format -> format.quality));

  /**
   * Selects the first available audio format from the given {@link URLParseDump}.
   */
  FormatStrategy FIRST_AUDIO = dump -> dump.formats.stream().filter(format -> !format.audio_ext.equals("none")).findAny();

  /**
   * Selects the first available video format from the given {@link URLParseDump}.
   */
  FormatStrategy FIRST_VIDEO = dump -> dump.formats.stream().filter(format -> !format.video_ext.equals("none")).findAny();

  /**
   * Selects the first available audio format with the "webm" extension from the given {@link URLParseDump}.
   */
  FormatStrategy PREFER_WEBM_AUDIO = dump ->
    dump.formats.stream().filter(format -> format.audio_ext.equals("webm")).filter(format -> format.protocol.equals("https")).findAny();

  /**
   * Selects the first available video format with the "webm" extension from the given {@link URLParseDump}.
   */
  FormatStrategy PREFER_MP4_VIDEO = dump ->
    dump.formats.stream().filter(format -> format.video_ext.equals("mp4")).filter(format -> format.protocol.equals("https")).findAny();
}

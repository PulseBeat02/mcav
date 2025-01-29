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
package me.brandonli.mcav.media.source;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.media.source.device.DeviceSourceDetector;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSourceDetector;
import me.brandonli.mcav.media.source.file.FileSourceDetector;
import me.brandonli.mcav.media.source.uri.UriSourceDetector;

/**
 * A helper class for detecting sources based on the provided resource string.
 */
public class SourceDetectionHelper {

  private static final Collection<SourceDetector<? extends Source>> DEFAULT_DETECTORS = Set.of(
    new DeviceSourceDetector(),
    new FFmpegDirectSourceDetector(),
    new FileSourceDetector(),
    new UriSourceDetector()
  );

  private final Collection<SourceDetector<?>> detectors;

  /**
   * Constructs a new {@link SourceDetectionHelper} with the specified detectors.
   *
   * @param detectors the collection of source detectors to use
   */
  public SourceDetectionHelper(final Collection<SourceDetector<?>> detectors) {
    this.detectors = detectors;
  }

  /**
   * Constructs a new {@link SourceDetectionHelper} with the default detectors.
   */
  public SourceDetectionHelper() {
    this(DEFAULT_DETECTORS);
  }

  /**
   * Detects the source based on the provided resource string.
   *
   * @param resource the resource string to detect the source from
   * @return an {@link Optional} containing the detected source, or empty if no source was detected
   */
  public Optional<Source> detectSource(final String resource) {
    int priority = Integer.MIN_VALUE;
    SourceDetector<? extends Source> type = null;
    for (final SourceDetector<? extends Source> detector : this.detectors) {
      final boolean successful = detector.isDetectedSource(resource);
      if (!successful) {
        continue;
      }
      final int priorityValue = detector.getPriority();
      if (priorityValue > priority) {
        priority = priorityValue;
        type = detector;
      }
    }

    if (type == null) {
      return Optional.empty();
    }

    return Optional.of(type.createSource(resource));
  }
}

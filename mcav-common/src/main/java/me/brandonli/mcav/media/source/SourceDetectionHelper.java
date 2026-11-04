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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.source.device.DeviceSourceDetector;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSourceDetector;
import me.brandonli.mcav.media.source.file.FileSourceDetector;
import me.brandonli.mcav.media.source.uri.UriSourceDetector;

/**
 * Turns strings entered by users into sources by asking a set of {@link SourceDetector}s.
 *
 * <p>The default detectors recognize existing file or directory paths, URLs with a scheme and host, plain numbers as capture
 * device indices, and {@code format||input} pairs as raw FFmpeg inputs.
 *
 * {@snippet :
 * final String resource = "videos/intro.mp4";
 * final SourceDetectionHelper helper = new SourceDetectionHelper();
 * final Optional<Source> source = helper.detectSource(resource);
 * }
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public class SourceDetectionHelper {

  private static final Collection<SourceDetector<? extends Source>> DEFAULT_DETECTORS = List.of(
    new DeviceSourceDetector(),
    new FfmpegDirectSourceDetector(),
    new FileSourceDetector(),
    new UriSourceDetector()
  );

  private final Collection<SourceDetector<? extends Source>> detectors;

  /**
   * Creates a helper with custom detectors.
   *
   * @param detectors the non-null detectors to consult in iteration order; the collection is copied and
   *                  must contain no null elements. Detectors themselves are shared, not copied
   * @throws NullPointerException if the collection or any detector is null
   * @since 1.0.0
   */
  public SourceDetectionHelper(final Collection<SourceDetector<? extends Source>> detectors) {
    Preconditions.checkNotNull(detectors, "Detectors must not be null!");

    this.detectors = List.copyOf(detectors);
  }

  /**
   * Creates a helper with the default detectors.
   *
   * @since 1.0.0
   */
  public SourceDetectionHelper() {
    this(DEFAULT_DETECTORS);
  }

  /**
   * Detects the source described by a string.
   *
   * @param resource the string entered by a user
   * @return the source created by the accepting detector with the highest priority, or empty if no detector
   * accepts the string; the first detector wins a priority tie
   * @throws RuntimeException     if a detector fails while testing or creating the source; failures propagate
   *                              and no lower-priority detector is tried as a fallback
   * @throws NullPointerException if {@code resource} is null
   * @since 1.0.0
   */
  public Optional<Source> detectSource(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");

    SourceDetector<? extends Source> best = null;
    int bestPriority = Integer.MIN_VALUE;
    for (final SourceDetector<? extends Source> detector : this.detectors) {
      final boolean accepted = detector.isDetectedSource(resource);
      if (!accepted) {
        continue;
      }
      final int priority = detector.getPriority();
      if (best == null || priority > bestPriority) {
        bestPriority = priority;
        best = detector;
      }
    }

    if (best == null) {
      return Optional.empty();
    }

    final Source source = best.createSource(resource);
    return Optional.of(source);
  }
}

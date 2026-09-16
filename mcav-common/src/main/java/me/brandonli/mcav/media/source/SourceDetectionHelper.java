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
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSourceDetector;
import me.brandonli.mcav.media.source.file.FileSourceDetector;
import me.brandonli.mcav.media.source.uri.UriSourceDetector;

/**
 * Turns strings entered by users into sources by asking a set of {@link SourceDetector}s.
 *
 * <p>The default detectors recognize existing file paths, URLs with a scheme and host, plain numbers as capture
 * device indices, and {@code format||input} pairs as raw FFmpeg inputs.
 *
 * <pre><code>
 *   final SourceDetectionHelper helper = new SourceDetectionHelper();
 *   final Optional&lt;Source&gt; source = helper.detectSource("videos/intro.mp4");
 * </code></pre>
 */
public class SourceDetectionHelper {

  private static final Collection<SourceDetector<? extends Source>> DEFAULT_DETECTORS = List.of(
    new DeviceSourceDetector(),
    new FFmpegDirectSourceDetector(),
    new FileSourceDetector(),
    new UriSourceDetector()
  );

  private final Collection<SourceDetector<? extends Source>> detectors;

  /**
   * Constructs a helper with custom detectors.
   *
   * @param detectors the detectors to consult
   */
  public SourceDetectionHelper(final Collection<SourceDetector<? extends Source>> detectors) {
    Preconditions.checkNotNull(detectors, "Detectors must not be null");
    this.detectors = List.copyOf(detectors);
  }

  /**
   * Constructs a helper with the default detectors.
   */
  public SourceDetectionHelper() {
    this(DEFAULT_DETECTORS);
  }

  /**
   * Detects the source described by a string.
   *
   * @param resource the string entered by a user
   * @return the source created by the accepting detector with the highest priority, or empty if no detector
   * accepts the string
   */
  public Optional<Source> detectSource(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    SourceDetector<? extends Source> best = null;
    int bestPriority = Integer.MIN_VALUE;
    for (final SourceDetector<? extends Source> detector : this.detectors) {
      final boolean accepted = detector.isDetectedSource(resource);
      if (!accepted) {
        continue;
      }
      final int priority = detector.getPriority();
      if (priority > bestPriority) {
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

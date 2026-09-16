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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link SourceDetectionHelper}.
 */
final class SourceDetectionHelperTest {

  @TempDir
  private Path directory;

  @Test
  void detectsExistingFiles() throws IOException {
    final Path file = this.directory.resolve("video.mp4");
    Files.createFile(file);
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final String raw = file.toString();
    final Optional<Source> detected = helper.detectSource(raw);
    final Source source = detected.orElseThrow();
    final FileSource fileSource = assertInstanceOf(FileSource.class, source);
    final Path path = fileSource.getPath();
    assertEquals(file, path);
  }

  @Test
  void detectsUrisDevicesAndFFmpegInputs() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> uri = helper.detectSource("https://example.com/video.mp4");
    final Optional<Source> device = helper.detectSource("2");
    final Optional<Source> ffmpeg = helper.detectSource("gdigrab||desktop");
    final Source uriSource = uri.orElseThrow();
    final Source deviceSource = device.orElseThrow();
    final Source ffmpegSource = ffmpeg.orElseThrow();
    assertInstanceOf(UriSource.class, uriSource);
    assertInstanceOf(DeviceSource.class, deviceSource);
    assertInstanceOf(FFmpegDirectSource.class, ffmpegSource);
  }

  @Test
  void detectsNothingForUnrecognizedText() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> text = helper.detectSource("not a source");
    final Optional<Source> negative = helper.detectSource("-1");
    assertTrue(text.isEmpty());
    assertTrue(negative.isEmpty());
  }

  @Test
  void prefersTheDetectorWithTheHighestPriority() {
    final Source lowSource = DeviceSource.device(1);
    final Source highSource = DeviceSource.device(2);
    final Source tiedSource = DeviceSource.device(3);
    final SourceDetector<Source> low = new FixedDetector(lowSource, SourceDetector.LOW_PRIORITY, true);
    final SourceDetector<Source> high = new FixedDetector(highSource, SourceDetector.HIGH_PRIORITY, true);
    final SourceDetector<Source> tied = new FixedDetector(tiedSource, SourceDetector.HIGH_PRIORITY, true);
    final SourceDetector<Source> refusing = new FixedDetector(tiedSource, Integer.MAX_VALUE, false);
    final List<SourceDetector<? extends Source>> detectors = List.of(low, refusing, high, tied);
    final SourceDetectionHelper helper = new SourceDetectionHelper(detectors);
    final Optional<Source> detected = helper.detectSource("anything");
    final Source source = detected.orElseThrow();
    assertSame(highSource, source, "the first detector with the highest priority wins");
  }

  @Test
  void rejectsMissingArguments() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    assertThrows(NullPointerException.class, () -> helper.detectSource(null));
    assertThrows(NullPointerException.class, () -> new SourceDetectionHelper(null));
  }

  /**
   * A detector that answers with a fixed decision and source.
   */
  private static final class FixedDetector implements SourceDetector<Source> {

    private final Source source;
    private final int priority;
    private final boolean accepts;

    FixedDetector(final Source source, final int priority, final boolean accepts) {
      this.source = source;
      this.priority = priority;
      this.accepts = accepts;
    }

    @Override
    public boolean isDetectedSource(final String raw) {
      return this.accepts;
    }

    @Override
    public Source createSource(final String raw) {
      return this.source;
    }

    @Override
    public int getPriority() {
      return this.priority;
    }
  }
}

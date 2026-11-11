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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceDetectionHelperTest {

  private static final String VIDEO_FILE_NAME = "video.mp4";

  private static final String ANY_RESOURCE = "anything";

  @TempDir
  private Path directory;

  @Test
  void shouldDetectExistingFiles() throws IOException {
    final Path file = this.directory.resolve(VIDEO_FILE_NAME);
    Files.createFile(file);
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final String resource = file.toString();
    final Optional<Source> detected = helper.detectSource(resource);
    final Source source = detected.orElseThrow();
    final FileSource fileSource = assertInstanceOf(FileSource.class, source);
    final Path path = fileSource.getPath();
    assertEquals(file, path);
  }

  @Test
  void shouldDetectUrisDevicesAndFfmpegInputs() {
    final String address = "https://example.com/video.mp4";
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> uri = helper.detectSource(address);
    final Optional<Source> device = helper.detectSource("2");
    final Optional<Source> ffmpeg = helper.detectSource("gdigrab||desktop");
    final Source uriSource = uri.orElseThrow();
    final Source deviceSource = device.orElseThrow();
    final Source ffmpegSource = ffmpeg.orElseThrow();
    final URI expectedUri = URI.create(address);
    final Source expectedUriSource = UriSource.uri(expectedUri);
    final Source expectedDeviceSource = DeviceSource.device(2);
    final Source expectedFfmpegSource = FfmpegDirectSource.mrl("desktop", "gdigrab");
    assertEquals(expectedUriSource, uriSource);
    assertEquals(expectedDeviceSource, deviceSource);
    assertEquals(expectedFfmpegSource, ffmpegSource);
  }

  @Test
  void shouldDetectNothingForUnrecognizedText() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> text = helper.detectSource("not a source");
    final Optional<Source> negative = helper.detectSource("-1");
    final boolean textMissing = text.isEmpty();
    assertTrue(textMissing);

    final boolean negativeMissing = negative.isEmpty();
    assertTrue(negativeMissing);
  }

  @Test
  void shouldPreferTheDetectorWithTheHighestPriority() {
    final Source lowSource = DeviceSource.device(1);
    final Source highSource = DeviceSource.device(2);
    final Source tiedSource = DeviceSource.device(3);
    final SourceDetector<Source> low = new FixedDetector(lowSource, SourceDetector.LOW_PRIORITY, Acceptance.ACCEPTS);
    final SourceDetector<Source> high = new FixedDetector(highSource, SourceDetector.HIGH_PRIORITY, Acceptance.ACCEPTS);
    final SourceDetector<Source> tied = new FixedDetector(tiedSource, SourceDetector.HIGH_PRIORITY, Acceptance.ACCEPTS);
    final SourceDetector<Source> refusing = new FixedDetector(tiedSource, Integer.MAX_VALUE, Acceptance.REFUSES);
    final List<SourceDetector<? extends Source>> detectors = List.of(low, refusing, high, tied);
    final SourceDetectionHelper helper = new SourceDetectionHelper(detectors);
    final Optional<Source> detected = helper.detectSource(ANY_RESOURCE);
    final Source source = detected.orElseThrow();
    assertSame(highSource, source, "the first detector with the highest priority wins");
  }

  @Test
  void shouldAcceptTheMinimumPriorityAndKeepTheFirstTie() {
    final Source first = DeviceSource.device(1);
    final Source second = DeviceSource.device(2);
    final SourceDetector<Source> low = new FixedDetector(first, Integer.MIN_VALUE, Acceptance.ACCEPTS);
    final SourceDetector<Source> tied = new FixedDetector(second, Integer.MIN_VALUE, Acceptance.ACCEPTS);
    final List<SourceDetector<? extends Source>> detectors = List.of(low, tied);
    final SourceDetectionHelper helper = new SourceDetectionHelper(detectors);
    final Optional<Source> detected = helper.detectSource(ANY_RESOURCE);
    final Source source = detected.orElseThrow();
    assertSame(first, source);
  }

  @Test
  void shouldKeepItsOwnCopyOfTheDetectors() {
    final Source source = DeviceSource.device(1);
    final SourceDetector<Source> detector = new FixedDetector(source, SourceDetector.NORMAL_PRIORITY, Acceptance.ACCEPTS);
    final List<SourceDetector<? extends Source>> detectors = new ArrayList<>();
    detectors.add(detector);
    final SourceDetectionHelper helper = new SourceDetectionHelper(detectors);
    detectors.clear();

    final Optional<Source> detected = helper.detectSource(ANY_RESOURCE);
    final Source detectedSource = detected.orElseThrow();
    assertSame(source, detectedSource);
  }

  @Test
  void shouldPropagateADetectorFailureInsteadOfFallingBack() {
    final Source source = DeviceSource.device(1);
    final SourceDetector<Source> fallback = new FixedDetector(source, SourceDetector.LOW_PRIORITY, Acceptance.ACCEPTS);
    final SourceDetector<Source> failingCheck = new FailingDetector(FailurePoint.CHECK);
    final SourceDetector<Source> failingCreation = new FailingDetector(FailurePoint.CREATION);
    final List<SourceDetector<? extends Source>> checkDetectors = List.of(fallback, failingCheck);
    final List<SourceDetector<? extends Source>> creationDetectors = List.of(fallback, failingCreation);
    final SourceDetectionHelper checkHelper = new SourceDetectionHelper(checkDetectors);
    final SourceDetectionHelper creationHelper = new SourceDetectionHelper(creationDetectors);
    assertThrows(IllegalStateException.class, () -> checkHelper.detectSource(ANY_RESOURCE));
    assertThrows(IllegalStateException.class, () -> creationHelper.detectSource(ANY_RESOURCE));
  }

  @Test
  void shouldRejectMissingArguments() {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    assertThrows(NullPointerException.class, () -> helper.detectSource(null));
    assertThrows(NullPointerException.class, () -> new SourceDetectionHelper(null));

    final Source source = DeviceSource.device(1);
    final SourceDetector<Source> detector = new FixedDetector(source, SourceDetector.NORMAL_PRIORITY, Acceptance.ACCEPTS);
    final List<SourceDetector<? extends Source>> withNull = Arrays.asList(detector, null);
    assertThrows(NullPointerException.class, () -> new SourceDetectionHelper(withNull));
  }

  private static final class FixedDetector implements SourceDetector<Source> {

    private final Source source;
    private final int priority;
    private final Acceptance acceptance;

    FixedDetector(final Source source, final int priority, final Acceptance acceptance) {
      this.source = source;
      this.priority = priority;
      this.acceptance = acceptance;
    }

    @Override
    public boolean isDetectedSource(final String resource) {
      return this.acceptance == Acceptance.ACCEPTS;
    }

    @Override
    public Source createSource(final String resource) {
      return this.source;
    }

    @Override
    public int getPriority() {
      return this.priority;
    }
  }

  private static final class FailingDetector implements SourceDetector<Source> {

    private final FailurePoint failurePoint;

    FailingDetector(final FailurePoint failurePoint) {
      this.failurePoint = failurePoint;
    }

    @Override
    public boolean isDetectedSource(final String resource) {
      if (this.failurePoint == FailurePoint.CHECK) {
        final String message = "Detector failed while checking %s!".formatted(resource);
        throw new IllegalStateException(message);
      }

      return true;
    }

    @Override
    public Source createSource(final String resource) {
      final String message = "Detector failed while creating %s!".formatted(resource);
      throw new IllegalStateException(message);
    }

    @Override
    public int getPriority() {
      return SourceDetector.HIGH_PRIORITY;
    }
  }

  private enum Acceptance {
    ACCEPTS,
    REFUSES,
  }

  private enum FailurePoint {
    CHECK,
    CREATION,
  }
}

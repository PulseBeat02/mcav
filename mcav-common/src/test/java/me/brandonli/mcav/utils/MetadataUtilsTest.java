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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class MetadataUtilsTest {

  private static final String FIXTURE_FILE_NAME = "metadata-fixture";
  private static final String MISSING_FILE_NAME = "missing.mp4";

  private static final int VIDEO_WIDTH = 16;
  private static final int VIDEO_HEIGHT = 8;
  private static final double FRAME_RATE = 24.0;
  private static final int VIDEO_BITRATE = 12_345;

  private static final String AUDIO_CODEC = "fixture-codec";
  private static final int AUDIO_BITRATE = 96_000;
  private static final int SAMPLE_RATE = 48_000;
  private static final int AUDIO_CHANNELS = 2;

  private static final int PROBE_FRAMES = 30;

  @TempDir
  private Path directory;

  @ParameterizedTest
  @EnumSource(MediaKind.class)
  void shouldReadMetadataThatAppearsOnTheLastAllowedProbeFrame(final MediaKind track) throws FrameGrabber.Exception {
    final boolean audio = track == MediaKind.AUDIO;
    final AtomicInteger frames = new AtomicInteger();
    final FileSource source = FileSource.path(Path.of(FIXTURE_FILE_NAME));
    try (
      final Frame frame = new Frame();
      final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) ->
        this.stubMetadataRevealedOnTheLastProbeFrame(grabber, frames, frame)
      )
    ) {
      if (audio) {
        final OriginalAudioMetadata actual = MetadataUtils.parseAudioMetadata(source);
        final OriginalAudioMetadata expected = OriginalAudioMetadata.of(AUDIO_CODEC, AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1);
        assertEquals(expected, actual);
      } else {
        final OriginalVideoMetadata actual = MetadataUtils.parseVideoMetadata(source);
        final OriginalVideoMetadata expected = OriginalVideoMetadata.of(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, (float) FRAME_RATE);
        assertEquals(expected, actual);
      }
      final int probedFrames = frames.get();
      assertEquals(PROBE_FRAMES, probedFrames, "the probe includes its last allowed frame and stops there");
      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber, times(PROBE_FRAMES)).grabFrame();
      verify(grabber).close();
    }
  }

  private void stubMetadataRevealedOnTheLastProbeFrame(final FFmpegFrameGrabber grabber, final AtomicInteger frames, final Frame frame)
    throws FrameGrabber.Exception {
    when(grabber.getImageWidth()).thenAnswer(_ -> frames.get() >= PROBE_FRAMES ? VIDEO_WIDTH : 0);
    when(grabber.getImageHeight()).thenReturn(VIDEO_HEIGHT);
    when(grabber.getFrameRate()).thenAnswer(_ -> frames.get() >= PROBE_FRAMES ? FRAME_RATE : 0.0);
    when(grabber.getVideoBitrate()).thenReturn(VIDEO_BITRATE);
    when(grabber.getSampleRate()).thenAnswer(_ -> frames.get() >= PROBE_FRAMES ? SAMPLE_RATE : 0);
    when(grabber.getAudioChannels()).thenReturn(AUDIO_CHANNELS);
    when(grabber.getAudioCodecName()).thenReturn(AUDIO_CODEC);
    when(grabber.getAudioBitrate()).thenReturn(AUDIO_BITRATE);
    when(grabber.getSampleFormat()).thenReturn(1);
    when(grabber.grabFrame()).thenAnswer(_ -> this.countProbedFrame(frames, frame));
  }

  private Frame countProbedFrame(final AtomicInteger frames, final Frame frame) {
    frames.incrementAndGet();

    return frame;
  }

  @ParameterizedTest
  @EnumSource(MediaKind.class)
  void shouldNotConsumeAnyFramesForDeclaredMetadata(final MediaKind track) throws FrameGrabber.Exception {
    final boolean audio = track == MediaKind.AUDIO;
    final FileSource source = FileSource.path(Path.of(FIXTURE_FILE_NAME));
    try (
      final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) ->
        this.stubDeclaredMetadata(grabber)
      )
    ) {
      if (audio) {
        final OriginalAudioMetadata actual = MetadataUtils.parseAudioMetadata(source);
        final OriginalAudioMetadata expected = OriginalAudioMetadata.of(AUDIO_CODEC, AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1);
        assertEquals(expected, actual);
      } else {
        final OriginalVideoMetadata actual = MetadataUtils.parseVideoMetadata(source);
        final OriginalVideoMetadata expected = OriginalVideoMetadata.of(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, (float) FRAME_RATE);
        assertEquals(expected, actual);
      }

      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber, never()).grabFrame();
      verify(grabber).close();
    }
  }

  private void stubDeclaredMetadata(final FFmpegFrameGrabber grabber) {
    when(grabber.getImageWidth()).thenReturn(VIDEO_WIDTH);
    when(grabber.getImageHeight()).thenReturn(VIDEO_HEIGHT);
    when(grabber.getFrameRate()).thenReturn(FRAME_RATE);
    when(grabber.getVideoBitrate()).thenReturn(VIDEO_BITRATE);
    when(grabber.getSampleRate()).thenReturn(SAMPLE_RATE);
    when(grabber.getAudioChannels()).thenReturn(AUDIO_CHANNELS);
    when(grabber.getAudioCodecName()).thenReturn(AUDIO_CODEC);
    when(grabber.getAudioBitrate()).thenReturn(AUDIO_BITRATE);
    when(grabber.getSampleFormat()).thenReturn(1);
  }

  @Test
  void shouldReportAnUnknownCodecWhenFfmpegNamesNone() {
    try (
      final MockedConstruction<FFmpegFrameGrabber> _ = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) ->
        this.stubMetadataWithoutCodecName(grabber)
      )
    ) {
      final OriginalAudioMetadata actual = MetadataUtils.parseAudioMetadata(FileSource.path(Path.of(FIXTURE_FILE_NAME)));
      final OriginalAudioMetadata expected = OriginalAudioMetadata.of("unknown", AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1);
      assertEquals(expected, actual);
    }
  }

  private void stubMetadataWithoutCodecName(final FFmpegFrameGrabber grabber) {
    this.stubDeclaredMetadata(grabber);
    when(grabber.getAudioCodecName()).thenReturn(null);
  }

  @ParameterizedTest
  @EnumSource(MediaKind.class)
  void shouldStopProbingImmediatelyAtTheEndOfTheSource(final MediaKind track) throws FrameGrabber.Exception {
    final boolean audio = track == MediaKind.AUDIO;
    final FileSource source = FileSource.path(Path.of(FIXTURE_FILE_NAME));
    try (final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class)) {
      final InputMetadataException failure = assertThrows(InputMetadataException.class, () -> this.parseTrackMetadata(source, track));
      final String message = failure.getMessage();
      final String expected = "Source " + source.getResource() + " has no " + (audio ? "audio" : "video") + " track!";
      assertEquals(expected, message);

      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber).grabFrame();
      verify(grabber).close();
    }
  }

  private void parseTrackMetadata(final FileSource source, final MediaKind track) {
    if (track == MediaKind.AUDIO) {
      MetadataUtils.parseAudioMetadata(source);
    } else {
      MetadataUtils.parseVideoMetadata(source);
    }
  }

  @Test
  void shouldReadTheVideoTrack() {
    final Path video = TestMedia.video();
    final FileSource source = FileSource.path(video);
    final OriginalVideoMetadata metadata = MetadataUtils.parseVideoMetadata(source);
    final int width = metadata.getVideoWidth();
    final int height = metadata.getVideoHeight();
    final float frameRate = metadata.getVideoFrameRate();
    assertEquals(TestMedia.VIDEO_WIDTH, width);
    assertEquals(TestMedia.VIDEO_HEIGHT, height);
    assertEquals(TestMedia.VIDEO_FRAME_RATE, frameRate, 0.01f);
  }

  @Test
  void shouldReadTheAudioTrack() {
    final Path video = TestMedia.video();
    final FileSource source = FileSource.path(video);
    final OriginalAudioMetadata metadata = MetadataUtils.parseAudioMetadata(source);
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    final String codec = metadata.getAudioCodec();
    assertEquals(TestMedia.AUDIO_SAMPLE_RATE, sampleRate);
    assertEquals(1, channels);
    assertEquals("aac", codec);
  }

  @Test
  void shouldReadTheLengthOfAFile() {
    final Path video = TestMedia.video();
    final FileSource source = FileSource.path(video);
    final Optional<Duration> declared = MetadataUtils.parseDuration(source);
    final Duration length = declared.orElseThrow();
    final double seconds = length.toMillis() / 1000.0;
    assertEquals(TestMedia.VIDEO_SECONDS, seconds, 0.1);
  }

  @Test
  void shouldReportNoLengthWhenTheContainerDeclaresNone() {
    final Optional<Duration> zero = this.parseDurationDeclaring(0L);
    assertEquals(Optional.empty(), zero);

    final Optional<Duration> unset = this.parseDurationDeclaring(Long.MIN_VALUE);
    assertEquals(Optional.empty(), unset);

    final Optional<Duration> shortest = this.parseDurationDeclaring(1L);
    final Duration oneMicrosecond = Duration.of(1L, ChronoUnit.MICROS);
    final Optional<Duration> expected = Optional.of(oneMicrosecond);
    assertEquals(expected, shortest);
  }

  private Optional<Duration> parseDurationDeclaring(final long lengthMicroseconds) {
    final FileSource source = FileSource.path(Path.of(FIXTURE_FILE_NAME));
    try (
      final MockedConstruction<FFmpegFrameGrabber> _ = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) ->
        when(grabber.getLengthInTime()).thenReturn(lengthMicroseconds)
      )
    ) {
      return MetadataUtils.parseDuration(source);
    }
  }

  @Test
  void shouldReportMissingVideoTracksAfterProbing() {
    final Path shortAudio = TestMedia.audio(0.5);
    final Path longAudio = TestMedia.audio(2.0);
    final FileSource shortSource = FileSource.path(shortAudio);
    final FileSource longSource = FileSource.path(longAudio);
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(shortSource));
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(longSource));
  }

  @Test
  void shouldReportMissingAudioTracksAfterProbing() {
    final Path silentVideo = TestMedia.silentVideo();
    final FileSource source = FileSource.path(silentVideo);
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseAudioMetadata(source));
  }

  @Test
  void shouldReportFilesThatCannotBeOpened() {
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final FileSource source = FileSource.path(missing);
    final String resource = source.getResource();
    final InputMetadataException videoFailure = assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(source));
    final Throwable videoFailureCause = videoFailure.getCause();
    final FrameGrabber.Exception videoCause = assertInstanceOf(FrameGrabber.Exception.class, videoFailureCause);
    final String videoReason = videoCause.getMessage();
    final String expectedVideoMessage = "Because of \"%s\", the video metadata of %s could not be read!".formatted(videoReason, resource);
    final String videoMessage = videoFailure.getMessage();
    assertEquals(expectedVideoMessage, videoMessage);

    final InputMetadataException audioFailure = assertThrows(InputMetadataException.class, () -> MetadataUtils.parseAudioMetadata(source));
    final Throwable audioFailureCause = audioFailure.getCause();
    final FrameGrabber.Exception audioCause = assertInstanceOf(FrameGrabber.Exception.class, audioFailureCause);
    final String audioReason = audioCause.getMessage();
    final String expectedAudioMessage = "Because of \"%s\", the audio metadata of %s could not be read!".formatted(audioReason, resource);
    final String audioMessage = audioFailure.getMessage();
    assertEquals(expectedAudioMessage, audioMessage);

    final InputMetadataException durationFailure = assertThrows(InputMetadataException.class, () -> MetadataUtils.parseDuration(source));
    final Throwable durationFailureCause = durationFailure.getCause();
    final FrameGrabber.Exception durationCause = assertInstanceOf(FrameGrabber.Exception.class, durationFailureCause);
    final String durationReason = durationCause.getMessage();
    final String expectedDurationMessage = "Because of \"%s\", the duration of %s could not be read!".formatted(durationReason, resource);
    final String durationMessage = durationFailure.getMessage();
    assertEquals(expectedDurationMessage, durationMessage);
  }

  @Test
  void shouldRejectMissingSources() {
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseVideoMetadata(null));
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseAudioMetadata(null));
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseDuration(null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MetadataUtils.class);
  }

  private enum MediaKind {
    VIDEO,
    AUDIO,
  }
}

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
        assertEquals(OriginalAudioMetadata.of(AUDIO_CODEC, AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1), actual);
      } else {
        final OriginalVideoMetadata actual = MetadataUtils.parseVideoMetadata(source);
        assertEquals(OriginalVideoMetadata.of(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, (float) FRAME_RATE), actual);
      }
      final int probedFrames = frames.get();
      assertEquals(30, probedFrames, "the probe includes its last allowed frame and stops there");
      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber, times(30)).grabFrame();
      verify(grabber).close();
    }
  }

  private void stubMetadataRevealedOnTheLastProbeFrame(final FFmpegFrameGrabber grabber, final AtomicInteger frames, final Frame frame)
    throws FrameGrabber.Exception {
    when(grabber.getImageWidth()).thenAnswer(_ -> frames.get() >= 30 ? VIDEO_WIDTH : 0);
    when(grabber.getImageHeight()).thenReturn(VIDEO_HEIGHT);
    when(grabber.getFrameRate()).thenAnswer(_ -> frames.get() >= 30 ? FRAME_RATE : 0.0);
    when(grabber.getVideoBitrate()).thenReturn(VIDEO_BITRATE);
    when(grabber.getSampleRate()).thenAnswer(_ -> frames.get() >= 30 ? SAMPLE_RATE : 0);
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
        assertEquals(OriginalAudioMetadata.of(AUDIO_CODEC, AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1), actual);
      } else {
        final OriginalVideoMetadata actual = MetadataUtils.parseVideoMetadata(source);
        assertEquals(OriginalVideoMetadata.of(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, (float) FRAME_RATE), actual);
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
      assertEquals(OriginalAudioMetadata.of("unknown", AUDIO_BITRATE, SAMPLE_RATE, AUDIO_CHANNELS, 1), actual);
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
      assertEquals("Source " + source.getResource() + " has no " + (audio ? "audio" : "video") + " track!", message);

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
    final FrameGrabber.Exception videoCause = assertInstanceOf(FrameGrabber.Exception.class, videoFailure.getCause());
    final String videoReason = videoCause.getMessage();
    final String expectedVideoMessage = "Video metadata of %s could not be read because of \"%s\"!".formatted(resource, videoReason);
    final String videoMessage = videoFailure.getMessage();
    assertEquals(expectedVideoMessage, videoMessage);

    final InputMetadataException audioFailure = assertThrows(InputMetadataException.class, () -> MetadataUtils.parseAudioMetadata(source));
    final FrameGrabber.Exception audioCause = assertInstanceOf(FrameGrabber.Exception.class, audioFailure.getCause());
    final String audioReason = audioCause.getMessage();
    final String expectedAudioMessage = "Audio metadata of %s could not be read because of \"%s\"!".formatted(resource, audioReason);
    final String audioMessage = audioFailure.getMessage();
    assertEquals(expectedAudioMessage, audioMessage);
  }

  @Test
  void shouldRejectMissingSources() {
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseVideoMetadata(null));
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseAudioMetadata(null));
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

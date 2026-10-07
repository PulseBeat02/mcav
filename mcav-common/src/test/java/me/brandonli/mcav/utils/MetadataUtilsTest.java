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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/**
 * Tests {@link MetadataUtils} against generated media files.
 */
final class MetadataUtilsTest {

  @TempDir
  private Path directory;

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void readsMetadataThatAppearsOnTheLastAllowedProbeFrame(final boolean audio) throws FrameGrabber.Exception {
    final AtomicInteger frames = new AtomicInteger();
    final FileSource source = FileSource.path(Path.of("metadata-fixture"));
    try (
      final Frame frame = new Frame();
      final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) -> {
        when(grabber.getImageWidth()).thenAnswer(_ -> frames.get() >= 30 ? 16 : 0);
        when(grabber.getImageHeight()).thenReturn(8);
        when(grabber.getFrameRate()).thenAnswer(_ -> frames.get() >= 30 ? 24.0 : 0.0);
        when(grabber.getVideoBitrate()).thenReturn(12_345);
        when(grabber.getSampleRate()).thenAnswer(_ -> frames.get() >= 30 ? 48_000 : 0);
        when(grabber.getAudioChannels()).thenReturn(2);
        when(grabber.getAudioCodecName()).thenReturn("fixture-codec");
        when(grabber.getAudioBitrate()).thenReturn(96_000);
        when(grabber.getSampleFormat()).thenReturn(1);
        when(grabber.grabFrame()).thenAnswer(_ -> {
          frames.incrementAndGet();
          return frame;
        });
      })
    ) {
      if (audio) {
        final OriginalAudioMetadata actual = MetadataUtils.parseAudioMetadata(source);
        assertEquals(OriginalAudioMetadata.of("fixture-codec", 96_000, 48_000, 2, 1), actual);
      } else {
        final OriginalVideoMetadata actual = MetadataUtils.parseVideoMetadata(source);
        assertEquals(OriginalVideoMetadata.of(16, 8, 12_345, 24.0f), actual);
      }
      assertEquals(30, frames.get(), "the probe includes its last allowed frame and stops there");
      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber, times(30)).grabFrame();
      verify(grabber).close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void declaredMetadataDoesNotConsumeAnyFrames(final boolean audio) throws FrameGrabber.Exception {
    final FileSource source = FileSource.path(Path.of("metadata-fixture"));
    try (
      final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class, (grabber, _) -> {
        when(grabber.getImageWidth()).thenReturn(16);
        when(grabber.getImageHeight()).thenReturn(8);
        when(grabber.getFrameRate()).thenReturn(24.0);
        when(grabber.getVideoBitrate()).thenReturn(12_345);
        when(grabber.getSampleRate()).thenReturn(48_000);
        when(grabber.getAudioChannels()).thenReturn(2);
        when(grabber.getAudioCodecName()).thenReturn("fixture-codec");
        when(grabber.getAudioBitrate()).thenReturn(96_000);
        when(grabber.getSampleFormat()).thenReturn(1);
      })
    ) {
      if (audio) {
        assertEquals(OriginalAudioMetadata.of("fixture-codec", 96_000, 48_000, 2, 1), MetadataUtils.parseAudioMetadata(source));
      } else {
        assertEquals(OriginalVideoMetadata.of(16, 8, 12_345, 24.0f), MetadataUtils.parseVideoMetadata(source));
      }
      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber, never()).grabFrame();
      verify(grabber).close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void probingStopsImmediatelyAtTheEndOfTheSource(final boolean audio) throws FrameGrabber.Exception {
    final FileSource source = FileSource.path(Path.of("metadata-fixture"));
    try (final MockedConstruction<FFmpegFrameGrabber> grabbers = Mockito.mockConstruction(FFmpegFrameGrabber.class)) {
      final InputMetadataException failure = assertThrows(InputMetadataException.class, () -> {
        if (audio) {
          MetadataUtils.parseAudioMetadata(source);
        } else {
          MetadataUtils.parseVideoMetadata(source);
        }
      });
      assertEquals("Source " + source.getResource() + " has no " + (audio ? "audio" : "video") + " track", failure.getMessage());
      final FFmpegFrameGrabber grabber = grabbers.constructed().getFirst();
      verify(grabber).grabFrame();
      verify(grabber).close();
    }
  }

  @Test
  void readsTheVideoTrack() {
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
  void readsTheAudioTrack() {
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
  void reportsMissingVideoTracksAfterProbing() {
    final Path shortAudio = TestMedia.audio(0.5);
    final Path longAudio = TestMedia.audio(2.0);
    final FileSource shortSource = FileSource.path(shortAudio);
    final FileSource longSource = FileSource.path(longAudio);
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(shortSource));
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(longSource));
  }

  @Test
  void reportsMissingAudioTracksAfterProbing() {
    final Path silentVideo = TestMedia.silentVideo();
    final FileSource source = FileSource.path(silentVideo);
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseAudioMetadata(source));
  }

  @Test
  void reportsFilesThatCannotBeOpened() {
    final Path missing = this.directory.resolve("missing.mp4");
    final FileSource source = FileSource.path(missing);
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseVideoMetadata(source));
    assertThrows(InputMetadataException.class, () -> MetadataUtils.parseAudioMetadata(source));
  }

  @Test
  void rejectsMissingSources() {
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseVideoMetadata(null));
    assertThrows(NullPointerException.class, () -> MetadataUtils.parseAudioMetadata(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MetadataUtils.class);
  }
}

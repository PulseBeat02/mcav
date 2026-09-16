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

import java.nio.file.Path;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link MetadataUtils} against generated media files.
 */
final class MetadataUtilsTest {

  @TempDir
  private Path directory;

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

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
package me.brandonli.mcav.media.player.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link OriginalAudioMetadata} and {@link OriginalAudioMetadataImpl}.
 */
final class OriginalAudioMetadataTest {

  @Test
  void storesEveryProperty() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("aac", 128_000, 44_100, 2, 1);
    final String codec = metadata.getAudioCodec();
    final int bitrate = metadata.getAudioBitrate();
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    final int samplingFormat = metadata.getSamplingFormat();
    assertEquals("aac", codec);
    assertEquals(128_000, bitrate);
    assertEquals(44_100, sampleRate);
    assertEquals(2, channels);
    assertEquals(1, samplingFormat);
  }

  @Test
  void followsTheEqualityContract() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("pcm", 1, 48_000, 2, 1);
    final OriginalAudioMetadata equalMetadata = OriginalAudioMetadata.of("pcm", 1, 48_000, 2, 1);
    final OriginalAudioMetadata otherCodec = OriginalAudioMetadata.of("opus", 1, 48_000, 2, 1);
    final OriginalAudioMetadata otherBitrate = OriginalAudioMetadata.of("pcm", 2, 48_000, 2, 1);
    final OriginalAudioMetadata otherSampleRate = OriginalAudioMetadata.of("pcm", 1, 44_100, 2, 1);
    final OriginalAudioMetadata otherChannels = OriginalAudioMetadata.of("pcm", 1, 48_000, 1, 1);
    final OriginalAudioMetadata otherFormat = OriginalAudioMetadata.of("pcm", 1, 48_000, 2, 2);
    EqualityAssertions.assertEqualityContract(
      metadata,
      equalMetadata,
      otherCodec,
      otherBitrate,
      otherSampleRate,
      otherChannels,
      otherFormat
    );
  }

  @Test
  void rejectsInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> OriginalAudioMetadata.of("pcm", 1, 0, 2, 1));
    assertThrows(IllegalArgumentException.class, () -> OriginalAudioMetadata.of("pcm", 1, 48_000, 0, 1));
    assertThrows(NullPointerException.class, () -> OriginalAudioMetadata.of(null, 1, 48_000, 2, 1));
  }

  @Test
  void rendersTheMainProperties() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("aac", 96_000, 44_100, 1, 1);
    final String text = metadata.toString();
    assertEquals("AudioMetadata[aac, 44100 Hz, 1 ch, 96000 bps]", text);
  }
}

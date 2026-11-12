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

import java.util.Objects;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class OriginalAudioMetadataTest {

  private static final String CODEC = "aac";
  private static final int BITRATE = 128_000;
  private static final int SAMPLE_RATE = 44_100;

  private static final String UNCOMPRESSED_CODEC = "pcm";
  private static final int UNCOMPRESSED_SAMPLE_RATE = 48_000;

  private static final int STEREO_CHANNELS = 2;

  @Test
  void shouldStoreEveryProperty() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(CODEC, BITRATE, SAMPLE_RATE, STEREO_CHANNELS, 1);
    final String codec = metadata.getAudioCodec();
    final int bitrate = metadata.getAudioBitrate();
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    final int samplingFormat = metadata.getSamplingFormat();
    assertEquals(CODEC, codec);
    assertEquals(BITRATE, bitrate);
    assertEquals(SAMPLE_RATE, sampleRate);
    assertEquals(STEREO_CHANNELS, channels);
    assertEquals(1, samplingFormat);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, UNCOMPRESSED_SAMPLE_RATE, STEREO_CHANNELS, 1);
    final OriginalAudioMetadata equalMetadata = OriginalAudioMetadata.of(
      UNCOMPRESSED_CODEC,
      1,
      UNCOMPRESSED_SAMPLE_RATE,
      STEREO_CHANNELS,
      1
    );
    final OriginalAudioMetadata otherCodec = OriginalAudioMetadata.of("opus", 1, UNCOMPRESSED_SAMPLE_RATE, STEREO_CHANNELS, 1);
    final OriginalAudioMetadata otherBitrate = OriginalAudioMetadata.of(
      UNCOMPRESSED_CODEC,
      2,
      UNCOMPRESSED_SAMPLE_RATE,
      STEREO_CHANNELS,
      1
    );
    final OriginalAudioMetadata otherSampleRate = OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, SAMPLE_RATE, STEREO_CHANNELS, 1);
    final OriginalAudioMetadata otherChannels = OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, UNCOMPRESSED_SAMPLE_RATE, 1, 1);
    final OriginalAudioMetadata otherFormat = OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, UNCOMPRESSED_SAMPLE_RATE, STEREO_CHANNELS, 2);
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
  void shouldHashEveryProperty() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(CODEC, BITRATE, SAMPLE_RATE, STEREO_CHANNELS, 1);

    final int hash = metadata.hashCode();
    final int expected = Objects.hash(CODEC, BITRATE, SAMPLE_RATE, STEREO_CHANNELS, 1);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRejectInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, 0, STEREO_CHANNELS, 1));
    assertThrows(IllegalArgumentException.class, () -> OriginalAudioMetadata.of(UNCOMPRESSED_CODEC, 1, UNCOMPRESSED_SAMPLE_RATE, 0, 1));
    assertThrows(NullPointerException.class, () -> OriginalAudioMetadata.of(null, 1, UNCOMPRESSED_SAMPLE_RATE, STEREO_CHANNELS, 1));
  }

  @Test
  void shouldAcceptAnUnknownBitrateAndSamplingFormat() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(
      CODEC,
      OriginalAudioMetadata.UNKNOWN,
      SAMPLE_RATE,
      STEREO_CHANNELS,
      OriginalAudioMetadata.UNKNOWN
    );
    final int bitrate = metadata.getAudioBitrate();
    final int samplingFormat = metadata.getSamplingFormat();
    assertEquals(OriginalAudioMetadata.UNKNOWN, bitrate);
    assertEquals(OriginalAudioMetadata.UNKNOWN, samplingFormat);
  }

  @Test
  void shouldRenderEveryProperty() {
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(CODEC, 96_000, SAMPLE_RATE, 1, 1);
    final String text = metadata.toString();
    assertEquals(
      "DefaultOriginalAudioMetadata{codec=aac, audioBitrate=96000, audioSampleRate=44100, audioChannels=1, samplingFormat=1}",
      text
    );
  }
}

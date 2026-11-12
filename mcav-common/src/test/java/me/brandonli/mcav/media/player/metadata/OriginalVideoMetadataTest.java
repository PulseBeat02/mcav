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

final class OriginalVideoMetadataTest {

  private static final int WIDTH = 4;
  private static final int HEIGHT = 3;
  private static final int BITRATE = 100;
  private static final float FRAME_RATE = 30.0f;

  @Test
  void shouldStoreEveryProperty() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(1920, 1080, 4_000_000, 29.97f);
    final int width = metadata.getVideoWidth();
    final int height = metadata.getVideoHeight();
    final int bitrate = metadata.getVideoBitrate();
    final float frameRate = metadata.getVideoFrameRate();
    assertEquals(1920, width);
    assertEquals(1080, height);
    assertEquals(4_000_000, bitrate);
    assertEquals(29.97f, frameRate);
  }

  @Test
  void shouldMarkOmittedPropertiesAsUnknown() {
    final OriginalVideoMetadata withFrameRate = OriginalVideoMetadata.of(640, 480, 25.0f);
    final OriginalVideoMetadata sizeOnly = OriginalVideoMetadata.of(640, 480);
    final int bitrate = withFrameRate.getVideoBitrate();
    final float frameRate = withFrameRate.getVideoFrameRate();
    final float unknownFrameRate = sizeOnly.getVideoFrameRate();
    assertEquals(OriginalVideoMetadata.UNKNOWN, bitrate);
    assertEquals(25.0f, frameRate);
    assertEquals(OriginalVideoMetadata.UNKNOWN, unknownFrameRate);

    final OriginalVideoMetadata expectedWithFrameRate = OriginalVideoMetadata.of(640, 480, OriginalVideoMetadata.UNKNOWN, 25.0f);
    final OriginalVideoMetadata expectedSizeOnly = OriginalVideoMetadata.of(
      640,
      480,
      OriginalVideoMetadata.UNKNOWN,
      OriginalVideoMetadata.UNKNOWN
    );
    assertEquals(expectedWithFrameRate, withFrameRate);
    assertEquals(expectedSizeOnly, sizeOnly);
  }

  @Test
  void shouldMakeEmptyMetadataUnknownEverywhere() {
    final OriginalVideoMetadata empty = OriginalVideoMetadata.EMPTY;
    final int width = empty.getVideoWidth();
    final int height = empty.getVideoHeight();
    final int bitrate = empty.getVideoBitrate();
    final float frameRate = empty.getVideoFrameRate();
    assertEquals(OriginalVideoMetadata.UNKNOWN, width);
    assertEquals(OriginalVideoMetadata.UNKNOWN, height);
    assertEquals(OriginalVideoMetadata.UNKNOWN, bitrate);
    assertEquals(OriginalVideoMetadata.UNKNOWN, frameRate);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(WIDTH, HEIGHT, BITRATE, FRAME_RATE);
    final OriginalVideoMetadata equalMetadata = OriginalVideoMetadata.of(WIDTH, HEIGHT, BITRATE, FRAME_RATE);
    final OriginalVideoMetadata otherWidth = OriginalVideoMetadata.of(5, HEIGHT, BITRATE, FRAME_RATE);
    final OriginalVideoMetadata otherHeight = OriginalVideoMetadata.of(WIDTH, 5, BITRATE, FRAME_RATE);
    final OriginalVideoMetadata otherBitrate = OriginalVideoMetadata.of(WIDTH, HEIGHT, 200, FRAME_RATE);
    final OriginalVideoMetadata otherFrameRate = OriginalVideoMetadata.of(WIDTH, HEIGHT, BITRATE, 60.0f);
    EqualityAssertions.assertEqualityContract(metadata, equalMetadata, otherWidth, otherHeight, otherBitrate, otherFrameRate);
  }

  @Test
  void shouldHashTheWidthThenTheHeightThenTheBitrateThenTheFrameRate() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(WIDTH, HEIGHT, BITRATE, FRAME_RATE);

    final int hash = metadata.hashCode();
    final int expected = Objects.hash(WIDTH, HEIGHT, BITRATE, FRAME_RATE);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRejectNonPositiveSizes() {
    assertThrows(IllegalArgumentException.class, () -> OriginalVideoMetadata.of(0, 10));
    assertThrows(IllegalArgumentException.class, () -> OriginalVideoMetadata.of(10, 0));
  }

  @Test
  void shouldRenderEveryProperty() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(320, 240, 1000, FRAME_RATE);
    final String text = metadata.toString();
    assertEquals("DefaultOriginalVideoMetadata{videoWidth=320, videoHeight=240, videoBitrate=1000, videoFrameRate=30.0}", text);
  }
}

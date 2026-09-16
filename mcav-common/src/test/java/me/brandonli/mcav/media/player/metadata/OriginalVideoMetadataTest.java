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
 * Tests {@link OriginalVideoMetadata} and {@link OriginalVideoMetadataImpl}.
 */
final class OriginalVideoMetadataTest {

  @Test
  void storesEveryProperty() {
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
  void marksOmittedPropertiesAsUnknown() {
    final OriginalVideoMetadata withFrameRate = OriginalVideoMetadata.of(640, 480, 25.0f);
    final OriginalVideoMetadata sizeOnly = OriginalVideoMetadata.of(640, 480);
    final int bitrate = withFrameRate.getVideoBitrate();
    final float frameRate = withFrameRate.getVideoFrameRate();
    final float unknownFrameRate = sizeOnly.getVideoFrameRate();
    assertEquals(OriginalVideoMetadata.UNKNOWN, bitrate);
    assertEquals(25.0f, frameRate);
    assertEquals(OriginalVideoMetadata.UNKNOWN, unknownFrameRate);
  }

  @Test
  void emptyMetadataIsUnknownEverywhere() {
    final OriginalVideoMetadata empty = OriginalVideoMetadata.EMPTY;
    final int width = empty.getVideoWidth();
    final int height = empty.getVideoHeight();
    assertEquals(OriginalVideoMetadata.UNKNOWN, width);
    assertEquals(OriginalVideoMetadata.UNKNOWN, height);
  }

  @Test
  void followsTheEqualityContract() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(4, 3, 100, 30.0f);
    final OriginalVideoMetadata equalMetadata = OriginalVideoMetadata.of(4, 3, 100, 30.0f);
    final OriginalVideoMetadata otherWidth = OriginalVideoMetadata.of(5, 3, 100, 30.0f);
    final OriginalVideoMetadata otherHeight = OriginalVideoMetadata.of(4, 5, 100, 30.0f);
    final OriginalVideoMetadata otherBitrate = OriginalVideoMetadata.of(4, 3, 200, 30.0f);
    final OriginalVideoMetadata otherFrameRate = OriginalVideoMetadata.of(4, 3, 100, 60.0f);
    EqualityAssertions.assertEqualityContract(metadata, equalMetadata, otherWidth, otherHeight, otherBitrate, otherFrameRate);
  }

  @Test
  void rejectsNonPositiveSizes() {
    assertThrows(IllegalArgumentException.class, () -> OriginalVideoMetadata.of(0, 10));
    assertThrows(IllegalArgumentException.class, () -> OriginalVideoMetadata.of(10, 0));
  }

  @Test
  void rendersEveryProperty() {
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(320, 240, 1000, 30.0f);
    final String text = metadata.toString();
    assertEquals("VideoMetadata[320x240, 30.0 fps, 1000 bps]", text);
  }
}

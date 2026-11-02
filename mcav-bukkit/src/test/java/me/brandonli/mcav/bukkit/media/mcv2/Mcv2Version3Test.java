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
package me.brandonli.mcav.bukkit.media.mcv2;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.block;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.frame;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.put;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.readUnsignedInt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class Mcv2Version3Test {

  @Test
  void shouldRefuseOldVersionsWithAReencodeMessage() {
    final byte[] data = block(32, 0, 0, new byte[0], Mcv2FrameKind.KEYFRAME);
    data[4] = 2;
    final String versionTwo = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data)).getMessage();
    final boolean namesVersionTwo = versionTwo.contains("version 2");
    assertTrue(namesVersionTwo);

    final boolean asksToReencodeVersionTwo = versionTwo.contains("re-encode");
    assertTrue(asksToReencodeVersionTwo);

    data[3] = '1';
    final String versionOne = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data)).getMessage();
    final boolean namesVersionOne = versionOne.contains("version 1");
    assertTrue(namesVersionOne);

    final boolean asksToReencodeVersionOne = versionOne.contains("re-encode");
    assertTrue(asksToReencodeVersionOne);
  }

  @Test
  void shouldDetermineKeyframesFromExactlyTheTwoIds() {
    final byte[] data = block(32, 0, 0, new byte[0], Mcv2FrameKind.PREDICTED);
    final boolean keyframeBefore = Mcv2Decoder.parse(data).isKeyframe();
    assertFalse(keyframeBefore);

    for (final long id : new long[] { 0, 1, 0xFFFFFFFFL }) {
      put(data, 12, id, 4);
      put(data, 16, id, 4);

      final boolean keyframe = Mcv2Decoder.parse(data).isKeyframe();
      assertTrue(keyframe);
      final byte[] expectedPicture = new byte[32 * 32 * 3];
      final byte[] picture = Mcv2Decoder.decode(data, null, 0);
      assertArrayEquals(expectedPicture, picture);
    }
  }

  @Test
  void shouldValidateEveryHeaderField() {
    final byte[] valid = block(32, 0, 0, new byte[0], Mcv2FrameKind.KEYFRAME);
    for (final int offset : new int[] { 0, 1, 2, 3, 4, 5, 6, 7 }) {
      final byte[] bad = valid.clone();
      bad[offset] ^= 2;
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad), "offset " + offset);
    }

    for (final int offset : new int[] { 8, 10 }) {
      for (final int dimension : new int[] { 0, 4097, 65535 }) {
        final byte[] bad = valid.clone();
        put(bad, offset, dimension, 2);
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
      }
    }

    assertThrows(NullPointerException.class, () -> Mcv2Decoder.parse(null));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[131072]));
  }

  @Test
  void shouldRefuseEveryTruncationAndTrailingBytes() {
    final byte[] valid = block(8, 3, 0, new byte[14], Mcv2FrameKind.KEYFRAME);
    for (int length = 0; length < valid.length; length++) {
      final byte[] bad = Arrays.copyOf(valid, length);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }

    final byte[] extra = Arrays.copyOf(valid, valid.length + 1);
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(extra));
  }

  @Test
  void shouldValidateTemporalKeyframeModes() {
    for (final int mode : new int[] { 1, 5 }) {
      final byte[] bad = block(32, mode, 0, new byte[] { 0, 0 }, Mcv2FrameKind.KEYFRAME);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
  }

  @Test
  void shouldValidateMasksDirectoryAndCounts() {
    final byte[] valid = block(32, 0, 0, new byte[0], Mcv2FrameKind.KEYFRAME);
    for (final int offset : new int[] { 20, 24, 28, 32, 36 }) {
      final byte[] bad = valid.clone();
      bad[offset]++;
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad), "offset " + offset);
    }

    for (final int offset : new int[] { 28, 32, 36 }) {
      final byte[] bad = valid.clone();
      put(bad, offset, 0xFFFFFFFFL, 4);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
  }

  @Test
  void shouldValidateAllModeAndQuantizerCombinations() {
    for (int descriptor = 0; descriptor < 256; descriptor++) {
      final int mode = descriptor & 31;
      final int quantizer = descriptor >> 5;
      final int length = switch (mode) {
        case 0 -> 0;
        case 1 -> 2;
        case 5 -> 10;
        case 2 -> 3;
        case 3 -> 134;
        case 4 -> 11;
        default -> 0;
      };
      final byte[] data = block(32, mode, quantizer, new byte[length], Mcv2FrameKind.PREDICTED);
      if (mode <= 5 && (quantizer == 0 || (mode == 5 && quantizer <= 2))) {
        Mcv2Decoder.parse(data);
      } else {
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
      }
    }
  }

  @Test
  void shouldValidateLevelCountsAndNoSplitBelowEight() {
    final byte[] data = block(8, 0, 0, new byte[0], Mcv2FrameKind.KEYFRAME);
    data[40 + 5] = 6;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));

    data[40 + 5] = 0;
    data[40] = 0;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
  }

  @Test
  void shouldValidateWalkCursorAndSplitCount() {
    final byte[] valid = block(8, 2, 0, new byte[] { 1, 2, 3 }, Mcv2FrameKind.KEYFRAME);
    for (final int walk : new int[] { 49, 53 }) {
      for (final int value : new int[] { 1, 1 << 17 }) {
        final byte[] bad = valid.clone();
        put(bad, walk, readUnsignedInt(bad, walk) ^ value, 4);
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
      }
    }
  }

  @Test
  void shouldDecodePatternSelectorsForAllSizes() {
    for (final int size : new int[] { 8, 16, 32 }) {
      for (int orientation = 0; orientation < 2; orientation++) {
        this.assertPatternDecodes(size, orientation);
      }
    }
  }

  private void assertPatternDecodes(final int size, final int orientation) {
    final byte[] record = new byte[7 + size / 8];
    record[0] = (byte) 255;
    record[5] = (byte) 255;
    record[6] = (byte) orientation;
    Arrays.fill(record, 7, record.length, (byte) 85);
    final byte[] data = block(size, 4, 0, record, Mcv2FrameKind.KEYFRAME);
    final byte[] decoded = Mcv2Decoder.decode(data, null, 0);
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final boolean blue = ((orientation == 0 ? column : row) & 1) == 0;
        this.pixel(decoded, size, column, row, blue ? 0 : 255, 0, blue ? 255 : 0);
      }
    }

    record[6] = 2;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(block(size, 4, 0, record, Mcv2FrameKind.KEYFRAME)));
  }

  @Test
  void shouldDecodeSolidPaletteAndBlackKeyframeSkipsExactly() {
    final byte[] solid = Mcv2Decoder.decode(block(8, 2, 0, new byte[] { 10, 20, 30 }, Mcv2FrameKind.KEYFRAME), null, 0);
    for (int offset = 0; offset < solid.length; offset += 3) {
      final byte[] expectedPixel = new byte[] { 10, 20, 30 };
      final byte[] decodedPixel = Arrays.copyOfRange(solid, offset, offset + 3);
      assertArrayEquals(expectedPixel, decodedPixel);
    }

    final byte[] palette = new byte[14];
    palette[0] = 10;
    palette[1] = 20;
    palette[2] = 30;
    palette[3] = 40;
    palette[4] = 50;
    palette[5] = 60;
    palette[6] = (byte) 129;
    final byte[] picture = Mcv2Decoder.decode(block(8, 3, 0, palette, Mcv2FrameKind.KEYFRAME), null, 0);
    this.pixel(picture, 8, 0, 0, 40, 50, 60);
    this.pixel(picture, 8, 1, 0, 10, 20, 30);
    this.pixel(picture, 8, 7, 0, 40, 50, 60);
    this.pixel(picture, 8, 0, 1, 10, 20, 30);
    final byte[] skipped = frame(3, 2, Mcv2FrameKind.KEYFRAME, new byte[0], new byte[0][], new int[3]);
    final byte[] expectedSkippedPicture = new byte[18];
    final byte[] skippedPicture = Mcv2Decoder.decode(skipped, null, 0);
    assertArrayEquals(expectedSkippedPicture, skippedPicture);

    final byte[] expectedBlackPicture = new byte[8 * 8 * 3];
    final byte[] blackPicture = Mcv2Decoder.decode(block(8, 0, 0, new byte[0], Mcv2FrameKind.KEYFRAME), null, 0);
    assertArrayEquals(expectedBlackPicture, blackPicture);
  }

  @Test
  void shouldPredictWholePixelMotionWithClampedEdgesAndSignedVectors() {
    final byte[] reference = new byte[8 * 8 * 3];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) index;
    }

    for (final byte[] record : new byte[][] { { -1, 1 }, { -1, 1, 0, 0, 0, 0, 0, 0, 0, 0 } }) {
      final byte[] decoded = Mcv2Decoder.decode(block(8, record.length == 2 ? 1 : 5, 0, record, Mcv2FrameKind.PREDICTED), reference, 0);
      this.pixel(decoded, 8, 0, 0, 24, 25, 26);
      this.pixel(decoded, 8, 7, 7, 186, 187, 188);
    }

    final byte[] skippedPicture = Mcv2Decoder.decode(block(8, 0, 0, new byte[0], Mcv2FrameKind.PREDICTED), reference, 0);
    assertArrayEquals(reference, skippedPicture);
  }

  @Test
  void shouldInterpolateSignedGridNodesAndRoundTiesUp() {
    for (final int size : new int[] { 8, 16, 32 }) {
      final byte[] reference = new byte[size * size * 3];
      Arrays.fill(reference, (byte) 100);
      // Nodes [-8, -4, 0, 4] form a horizontal plane at all four rows.
      final byte[] record = { 0, 0, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40 };
      final byte[] picture = Mcv2Decoder.decode(block(size, 5, 0, record, Mcv2FrameKind.PREDICTED), reference, 0);
      this.pixel(picture, size, 0, 0, 92, 92, 92);
      this.pixel(picture, size, size - 1, size - 1, 104, 104, 104);
      // At p=size/4 - 1, t=.5 - 2/size, so Y=-6 - 8/size.
      final int roundedLuma = (int) Math.floor(-6.0 - 8.0 / size + 0.5);
      this.pixel(picture, size, size / 4 - 1, 0, 100 + roundedLuma, 100 + roundedLuma, 100 + roundedLuma);
    }
  }

  @Test
  void shouldApplyAllQuantizersAndSaturatePositiveAndNegativeResiduals() {
    final byte[] reference = new byte[8 * 8 * 3];
    for (final int base : new int[] { 0, 6, 100, 250, 255 }) {
      Arrays.fill(reference, (byte) base);
      for (int quantizer = 0; quantizer <= 2; quantizer++) {
        this.assertResidualsSaturate(reference, base, quantizer);
      }
    }
  }

  private void assertResidualsSaturate(final byte[] reference, final int base, final int quantizer) {
    for (final int delta : new int[] { -8, -1, 0, 1, 7 }) {
      final int value = Math.min(255, Math.max(0, base + (delta << quantizer)));
      final byte[] record = new byte[10];
      Arrays.fill(record, 2, 10, (byte) ((delta & 15) * 17));
      final byte[] decoded = Mcv2Decoder.decode(block(8, 5, quantizer, record, Mcv2FrameKind.PREDICTED), reference, 0);
      this.pixel(decoded, 8, 0, 0, value, value, value);
      this.pixel(decoded, 8, 7, 7, value, value, value);
    }
  }

  @Test
  void shouldValidateInvisibleLeavesAndCropOutput() {
    final byte[] data = block(16, 2, 0, new byte[] { 10, 20, 30 }, Mcv2FrameKind.KEYFRAME);
    put(data, 8, 1, 2);
    put(data, 10, 1, 2);

    final byte[] expectedPicture = new byte[] { 10, 20, 30 };
    final byte[] picture = Mcv2Decoder.decode(data, null, 0);
    assertArrayEquals(expectedPicture, picture);

    data[42] = 31;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
  }

  @Test
  void shouldDecodeRowsAndAliasedWholePictures() {
    final byte[] data = block(8, 1, 0, new byte[] { -1, 1 }, Mcv2FrameKind.PREDICTED);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    final byte[] reference = new byte[8 * 8 * 3];
    new Random(7).nextBytes(reference);
    final byte[] expected = Mcv2Decoder.decode(frame, reference, 0);
    final byte[] rows = new byte[reference.length];
    for (int row = 0; row < 8; row++) {
      Mcv2Decoder.decodeRows(frame, reference, 0, rows, row, row + 1);
    }

    assertArrayEquals(expected, rows);

    Mcv2Decoder.decode(frame, reference, 0, reference);
    assertArrayEquals(expected, reference);
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decodeRows(frame, reference, 0, reference, 0, 8));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decodeRows(frame, reference, 0, rows, -1, 8));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decodeRows(frame, reference, 0, rows, 3, 2));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decodeRows(frame, reference, 0, rows, 0, 9));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decode(frame, reference, 0, new byte[1]));
  }

  @Test
  void shouldValidateReferencesAndOwnParsedData() {
    final byte[] data = block(8, 1, 0, new byte[] { 0, 0 }, Mcv2FrameKind.PREDICTED);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[192], 1));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[1], 0));

    Arrays.fill(data, (byte) 0);
    final byte[] copy = frame.getData();
    copy[0] = 0;

    final byte magic = frame.getData()[0];
    assertEquals('M', magic);

    final int width = frame.getWidth();
    assertEquals(8, width);

    final int height = frame.getHeight();
    assertEquals(8, height);

    final long frameId = frame.getFrameId();
    assertEquals(1, frameId);

    final long referenceId = frame.getReferenceId();
    assertEquals(0, referenceId);

    final int leafCount = frame.getLeafCount();
    assertEquals(7, leafCount);

    final int leafSize = frame.getLeaf(0).size();
    assertEquals(16, leafSize);
    assertThrows(IndexOutOfBoundsException.class, () -> frame.getLeaf(-1));
  }

  @Test
  void shouldAcceptMaximumDimensionsWithAllRootsAbsent() {
    final byte[] data = frame(4096, 4096, Mcv2FrameKind.KEYFRAME, new byte[0], new byte[0][], new int[3]);
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(data);

    final int leafCount = parsed.getLeafCount();
    assertEquals(16384, leafCount);

    final int payloadStart = parsed.getPayloadStart();
    assertEquals(2336, payloadStart);
  }

  @Test
  void shouldNeverLetArbitraryBytesEscapeAsUncheckedParserFailures() {
    final Random random = new Random(7);
    for (int iteration = 0; iteration < 10000; iteration++) {
      final byte[] data = new byte[random.nextInt(512)];
      random.nextBytes(data);
      if (data.length >= 20 && iteration % 2 == 0) {
        put(data, 0, 0x3256434D, 4);
        data[4] = 3;
        data[5] = 0;
        data[6] = 0;
        data[7] = 0;
      }
      try {
        Mcv2Decoder.parse(data);
      } catch (final Mcv2Exception exception) {
        /* Arbitrary input may be malformed. */
      }
    }
  }

  private void pixel(
    final byte[] picture,
    final int width,
    final int column,
    final int row,
    final int red,
    final int green,
    final int blue
  ) {
    final int offset = (row * width + column) * 3;
    final byte[] expectedPixel = new byte[] { (byte) red, (byte) green, (byte) blue };
    final byte[] decodedPixel = Arrays.copyOfRange(picture, offset, offset + 3);
    assertArrayEquals(expectedPixel, decodedPixel);
  }
}

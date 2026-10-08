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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.read;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2WireFrames.records;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class Mcv2Version3Test {

  @Test
  void refusesOldVersionsWithReencodeMessage() {
    final byte[] data = block(32, 0, 0, new byte[0], true);
    data[4] = 2;
    final String versionTwo = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data)).getMessage();
    assertTrue(versionTwo.contains("version 2"));
    assertTrue(versionTwo.contains("re-encode"));
    data[3] = '1';
    final String versionOne = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data)).getMessage();
    assertTrue(versionOne.contains("version 1"));
    assertTrue(versionOne.contains("re-encode"));
  }

  @Test
  void validatesEveryHeaderField() {
    final byte[] valid = block(32, 0, 0, new byte[0], true);
    for (final int offset : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 16, 20, 24, 31 }) {
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
  void refusesEveryTruncationAndTrailingBytes() {
    final byte[] valid = block(8, 3, 0, new byte[14], true);
    for (int length = 0; length < valid.length; length++) {
      final byte[] bad = Arrays.copyOf(valid, length);
      if (length >= 32) {
        put(bad, 24, length, 4);
      }
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
    final byte[] extra = Arrays.copyOf(valid, valid.length + 1);
    put(extra, 24, extra.length, 4);
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(extra));
  }

  @Test
  void validatesReferenceMetadataAndTemporalKeyframeModes() {
    for (final int mode : new int[] { 1, 5 }) {
      final byte[] bad = block(32, mode, 0, new byte[] { 0, 0 }, true);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
    final byte[] predicted = block(32, 0, 0, new byte[0], false);
    predicted[28] = 1;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(predicted));
    predicted[28] = 0;
    predicted[16] = predicted[12];
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(predicted));
  }

  @Test
  void validatesMasksDirectoryCountsAndPayloadStart() {
    final byte[] valid = block(32, 0, 0, new byte[0], true);
    for (final int offset : new int[] { 32, 36, 40, 44, 48, 20 }) {
      final byte[] bad = valid.clone();
      bad[offset]++;
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad), "offset " + offset);
    }
    for (final int offset : new int[] { 20, 40, 44, 48 }) {
      final byte[] bad = valid.clone();
      put(bad, offset, 0xFFFFFFFFL, 4);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
  }

  @Test
  void validatesAllModeAndQuantizerCombinations() throws Mcv2Exception {
    for (int descriptor = 0; descriptor < 256; descriptor++) {
      final int mode = descriptor & 31;
      final int quantizer = descriptor >> 5;
      final int length = switch (mode) {
        case 0 -> 0;
        case 1, 5 -> 2;
        case 2 -> 3;
        case 3 -> 134;
        case 4 -> 11;
        default -> 0;
      };
      final byte[] data = block(32, mode, quantizer, new byte[length], false);
      if (mode <= 5 && (quantizer == 0 || mode == 5)) {
        Mcv2Decoder.parse(data);
      } else {
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
      }
    }
  }

  @Test
  void validatesLevelCountsAndNoSplitBelowEight() {
    final byte[] data = block(8, 0, 0, new byte[0], true);
    data[52 + 5] = 6;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
    data[52 + 5] = 0;
    data[52] = 0;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
  }

  @Test
  void validatesWalkCursorAndSplitCount() {
    final byte[] valid = block(8, 2, 0, new byte[] { 1, 2, 3 }, true);
    for (final int walk : new int[] { 61, 65 }) {
      for (final int value : new int[] { 1, 1 << 17 }) {
        final byte[] bad = valid.clone();
        put(bad, walk, read(bad, walk) ^ value, 4);
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
      }
    }
  }

  @Test
  void validatesCompactClassAndFormButAcceptsNonminimalMotion() throws Mcv2Exception {
    for (int control = 0; control < 256; control++) {
      final int kind = control & 15;
      final int form = control >> 4;
      final int length = 1 + form + (kind == 0 ? 1 : kind == 1 ? 10 : 8);
      final byte[] record = new byte[length];
      record[0] = (byte) control;
      final byte[] data = block(32, 5, 7, record, false);
      if (kind <= 2 && form <= 2) {
        Mcv2Decoder.parse(data);
      } else {
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
      }
    }
  }

  @Test
  void validatesUnusedTableOrientationsDuplicatesAndIndexes() throws Mcv2Exception {
    final byte[] pattern = { 0, 0 };
    final byte[][] words = { { 0, 85 }, {}, {} };
    final byte[] endpoints = { 0, (byte) 248, 31, 0 };
    final byte[] valid = frame(
      8,
      8,
      true,
      new byte[] { 6, 6, 0, 0, 0, 4, 0, 0, 0 },
      records(9, 5, pattern),
      new int[] { 1, 4, 4 },
      endpoints,
      words
    );
    Mcv2Decoder.parse(valid);
    final int payload = (int) read(valid, 20);
    for (final int offset : new int[] { payload, payload + 1, valid.length - 6 }) {
      final byte[] bad = valid.clone();
      bad[offset] = 2;
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(bad));
    }
    assertThrows(Mcv2Exception.class, () ->
      Mcv2Decoder.parse(
        frame(8, 8, true, new byte[] { 0 }, records(1, 0, new byte[0]), new int[] { 1, 0, 0 }, new byte[8], new byte[][] { {}, {}, {} })
      )
    );
    assertThrows(Mcv2Exception.class, () ->
      Mcv2Decoder.parse(
        frame(
          8,
          8,
          true,
          new byte[] { 0 },
          records(1, 0, new byte[0]),
          new int[] { 1, 0, 0 },
          new byte[0],
          new byte[][] { { 1, 0, 1, 0 }, {}, {} }
        )
      )
    );
    // Tables need not be used, and a present root may explicitly SKIP.
    Mcv2Decoder.parse(frame(8, 8, true, new byte[] { 0 }, records(1, 0, new byte[0]), new int[] { 1, 0, 0 }, endpoints, words));
  }

  @Test
  void decodesPatternTablesAndRawSelectorsForAllSizes() throws Mcv2Exception {
    for (final int size : new int[] { 8, 16, 32 }) {
      for (int orientation = 0; orientation < 2; orientation++) {
        final byte[] record = new byte[7 + size / 8];
        record[0] = (byte) 255;
        record[5] = (byte) 255;
        record[6] = (byte) orientation;
        Arrays.fill(record, 7, record.length, (byte) 85);
        final byte[] data = block(size, 4, 0, record, true);
        final byte[] decoded = Mcv2Decoder.decode(data, null, 0);
        for (int row = 0; row < size; row++) {
          for (int column = 0; column < size; column++) {
            final boolean blue = ((orientation == 0 ? column : row) & 1) == 0;
            pixel(decoded, size, column, row, blue ? 0 : 255, 0, blue ? 255 : 0);
          }
        }
        record[6] = 2;
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(block(size, 4, 0, record, true)));
      }
    }
    final byte[][] words = { { 0, 85 }, {}, {} };
    final byte[] data = frame(
      8,
      8,
      true,
      new byte[] { 6, 6, 0, 0, 0, 4, 0, 0, 0 },
      records(9, 5, new byte[] { 0, 0 }),
      new int[] { 1, 4, 4 },
      new byte[] { 0, (byte) 248, 31, 0 },
      words
    );
    final byte[] decoded = Mcv2Decoder.decode(data, null, 0);
    pixel(decoded, 8, 0, 0, 0, 0, 255);
    pixel(decoded, 8, 1, 0, 255, 0, 0);
  }

  @Test
  void decodesSolidPaletteAndKeyframeDefaultExactly() throws Mcv2Exception {
    final byte[] solid = Mcv2Decoder.decode(block(8, 2, 0, new byte[] { 10, 20, 30 }, true), null, 0);
    for (int offset = 0; offset < solid.length; offset += 3) {
      assertArrayEquals(new byte[] { 10, 20, 30 }, Arrays.copyOfRange(solid, offset, offset + 3));
    }
    final byte[] palette = new byte[14];
    palette[0] = 10;
    palette[1] = 20;
    palette[2] = 30;
    palette[3] = 40;
    palette[4] = 50;
    palette[5] = 60;
    palette[6] = (byte) 129;
    final byte[] picture = Mcv2Decoder.decode(block(8, 3, 0, palette, true), null, 0);
    pixel(picture, 8, 0, 0, 40, 50, 60);
    pixel(picture, 8, 1, 0, 10, 20, 30);
    pixel(picture, 8, 7, 0, 40, 50, 60);
    pixel(picture, 8, 0, 1, 10, 20, 30);
    final byte[] skipped = frame(3, 2, true, new byte[0], new byte[0][], new int[3], new byte[0], new byte[][] { {}, {}, {} });
    skipped[28] = 10;
    skipped[29] = 20;
    skipped[30] = 30;
    assertArrayEquals(Arrays.copyOf(solid, 18), Mcv2Decoder.decode(skipped, null, 0));
  }

  @Test
  void predictsWholePixelMotionWithClampedEdgesAndSignedNibbles() throws Mcv2Exception {
    final byte[] reference = new byte[8 * 8 * 3];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) index;
    }
    for (final byte[] record : new byte[][] { { -1, 1 }, { 0x10, 0x1F, 0 }, { 0x20, -1, 1, 0 } }) {
      final byte[] decoded = Mcv2Decoder.decode(block(8, record.length == 2 ? 1 : 5, 0, record, false), reference, 0);
      pixel(decoded, 8, 0, 0, 24, 25, 26);
      pixel(decoded, 8, 7, 7, 186, 187, 188);
    }
    assertArrayEquals(reference, Mcv2Decoder.decode(block(8, 0, 0, new byte[0], false), reference, 0));
  }

  @Test
  void interpolatesSignedGridNodesRoundsTiesUpAndAppliesChroma() throws Mcv2Exception {
    for (final int size : new int[] { 8, 16, 32 }) {
      final byte[] reference = new byte[size * size * 3];
      Arrays.fill(reference, (byte) 100);
      // A horizontal plane: nodes [-8, -4, 0, 4] repeated in every row.
      final byte[] record = { 1, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40, (byte) 0xC8, 0x40, 3, -2 };
      final byte[] picture = Mcv2Decoder.decode(block(size, 5, 0, record, false), reference, 0);
      pixel(picture, size, 0, 0, 97, 90, 91);
      pixel(picture, size, size - 1, size - 1, 109, 102, 103);
      // At p=size/4 - 1, t=.5 - 2/size; Y=-6 - 8/size, exactly.
      final int roundedLuma = (int) Math.floor(-6.0 - 8.0 / size + 0.5);
      pixel(picture, size, size / 4 - 1, 0, 105 + roundedLuma, 98 + roundedLuma, 99 + roundedLuma);
      final byte[] luma = Arrays.copyOf(record, 9);
      luma[0] = 2;
      final byte[] gray = Mcv2Decoder.decode(block(size, 5, 0, luma, false), reference, 0);
      pixel(gray, size, 0, 0, 92, 92, 92);
      pixel(gray, size, size - 1, size - 1, 104, 104, 104);
    }
  }

  @Test
  void appliesAllQuantizersAndSaturatesPositiveAndNegativeResiduals() throws Mcv2Exception {
    final byte[] reference = new byte[8 * 8 * 3];
    Arrays.fill(reference, (byte) 100);
    for (int quantizer = 0; quantizer < 8; quantizer++) {
      for (final int delta : new int[] { -128, -1, 0, 1, 127 }) {
        final int value = Math.min(255, Math.max(0, 100 + (delta << quantizer)));
        final byte[] decoded = Mcv2Decoder.decode(block(8, 5, quantizer, new byte[] { 0, (byte) delta }, false), reference, 0);
        pixel(decoded, 8, 0, 0, value, value, value);
        pixel(decoded, 8, 7, 7, value, value, value);
      }
    }
  }

  @Test
  void validatesInvisibleLeavesAndCropsOutput() throws Mcv2Exception {
    final byte[] data = block(16, 2, 0, new byte[] { 10, 20, 30 }, true);
    put(data, 8, 1, 2);
    put(data, 10, 1, 2);
    assertArrayEquals(new byte[] { 10, 20, 30 }, Mcv2Decoder.decode(data, null, 0));
    data[54] = 31;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
  }

  @Test
  void decodesRowsAndAliasedWholePictures() throws Mcv2Exception {
    final byte[] data = block(8, 1, 0, new byte[] { -1, 1 }, false);
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
  void validatesReferencesAndOwnsParsedData() throws Mcv2Exception {
    final byte[] data = block(8, 1, 0, new byte[] { 0, 0 }, false);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[192], 1));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[1], 0));
    Arrays.fill(data, (byte) 0);
    final byte[] copy = frame.getData();
    copy[0] = 0;
    assertEquals('M', frame.getData()[0]);
    assertEquals(8, frame.getWidth());
    assertEquals(8, frame.getHeight());
    assertEquals(1, frame.getFrameId());
    assertEquals(0, frame.getReferenceId());
    assertEquals(0, frame.getDefaultColor());
    assertEquals(7, frame.getLeafCount());
    assertEquals(16, frame.getLeaf(0).size());
    assertEquals(0, frame.getEndpointTable().length);
    assertEquals(0, frame.getSelectorTable(8).length);
    assertThrows(IndexOutOfBoundsException.class, () -> frame.getLeaf(-1));
    assertThrows(IllegalArgumentException.class, () -> frame.getSelectorTable(4));
  }

  @Test
  void acceptsMaximumDimensionsWithAllRootsAbsent() throws Mcv2Exception {
    final byte[] data = frame(4096, 4096, true, new byte[0], new byte[0][], new int[3], new byte[0], new byte[][] { {}, {}, {} });
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(data);
    assertEquals(16384, parsed.getLeafCount());
    assertEquals(2352, parsed.getPayloadStart());
  }

  @Test
  void arbitraryBytesNeverEscapeAsUncheckedParserFailures() {
    final Random random = new Random(7);
    for (int iteration = 0; iteration < 10000; iteration++) {
      final byte[] data = new byte[random.nextInt(512)];
      random.nextBytes(data);
      if (data.length >= 32 && iteration % 2 == 0) {
        put(data, 0, 0x3256434D, 4);
        data[4] = 3;
        data[5] = 0;
        data[6] = 0;
        data[7] = 0;
        data[31] = 0;
        put(data, 24, data.length, 4);
      }
      try {
        Mcv2Decoder.parse(data);
      } catch (final Mcv2Exception rejected) {
        /* Arbitrary input may be malformed. */
      }
    }
  }

  private static void pixel(
    final byte[] picture,
    final int width,
    final int column,
    final int row,
    final int red,
    final int green,
    final int blue
  ) {
    final int at = (row * width + column) * 3;
    assertArrayEquals(new byte[] { (byte) red, (byte) green, (byte) blue }, Arrays.copyOfRange(picture, at, at + 3));
  }
}

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
package me.brandonli.mcav.media.mcv2.encode;

import static me.brandonli.mcav.media.mcv2.Mcv2Frames.DERIVED;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.SHORT;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.keyframe;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.motion;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.pattern;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.predicted;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.solid;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.split;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.PatternRecord;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** Rebuilding the block tree of a frame, and the lossless palette-to-pattern rewrite. */
final class TreeReaderTest {

  private static final byte[] ENDPOINTS = { 1, 2, 3, (byte) 250, (byte) 251, (byte) 252 };

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(TreeReader.class);
  }

  /** A full palette record of an 8x8 block whose selector at (x, y) is {@code bit(x, y)}. */
  private static byte[] palette(final java.util.function.IntBinaryOperator bit) {
    final byte[] record = new byte[6 + 8];
    System.arraycopy(ENDPOINTS, 0, record, 0, 6);
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        record[6 + y] |= (byte) (bit.applyAsInt(x, y) << x);
      }
    }
    return record;
  }

  @Test
  void rebuildsKeyframeDefaultsAndPatterns() throws Mcv2Exception {
    final TreeNode patterns = split(pattern(16, ENDPOINTS, 1, 0x5A));
    final byte[] frame = keyframe(64, 32, DERIVED, solid(4, 5, 6), patterns);
    final List<TreeNode> roots = TreeReader.roots(FrameParser.parse(frame));
    assertEquals(2, roots.size());
    // the default colour's root was written as a skip; it comes back as the solid leaf it stands for
    assertEquals(solid(4, 5, 6), roots.get(0));
    final TreeNode expanded = roots.get(1).getChild(2);
    assertEquals(Mcv2Format.MODE_PALETTE, expanded.getMode());
    final PatternRecord record = PatternRecord.expand(pattern(16, ENDPOINTS, 1, 0x5A).getRecord(), 0, 16, null, null);
    assertArrayEquals(TreeReader.fullPalette(record, 16), expanded.getRecord());
    // and the palette is rewritten as the same pattern
    assertEquals(patterns, TreeReader.withPatterns(roots.get(1), 32));
  }

  @Test
  void rebuildsImmediateMotionSkipsAndCompactRecords() throws Mcv2Exception {
    final TreeNode compact = TreeNode.leaf(Mcv2Format.MODE_COMPACT, 3, new byte[] { 0, 5 });
    final byte[] frame = predicted(
      96,
      32,
      0,
      0,
      SHORT,
      motion(-2, 3),
      TreeNode.skip(),
      TreeNode.split(compact, TreeNode.skip(), motion(1, 1), compact)
    );
    final List<TreeNode> roots = TreeReader.roots(FrameParser.parse(frame));
    assertEquals(List.of(motion(-2, 3), TreeNode.skip(), TreeNode.split(compact, TreeNode.skip(), motion(1, 1), compact)), roots);
  }

  @Test
  void expandsPatternsAlongEitherAxis() throws Mcv2Exception {
    final PatternRecord columns = PatternRecord.expand(pattern(8, ENDPOINTS, 0, 0x0F).getRecord(), 0, 8, null, null);
    assertArrayEquals(palette((x, y) -> x < 4 ? 1 : 0), TreeReader.fullPalette(columns, 8));
    final PatternRecord rows = PatternRecord.expand(pattern(8, ENDPOINTS, 1, 0x0F).getRecord(), 0, 8, null, null);
    assertArrayEquals(palette((x, y) -> y < 4 ? 1 : 0), TreeReader.fullPalette(rows, 8));
  }

  @Test
  void findsTheRepeatingAxisRowsFirst() {
    assertArrayEquals(pattern(8, ENDPOINTS, 0, 0x0F).getRecord(), TreeReader.patternRecord(palette((x, y) -> x < 4 ? 1 : 0), 8));
    assertArrayEquals(pattern(8, ENDPOINTS, 1, 0x0F).getRecord(), TreeReader.patternRecord(palette((x, y) -> y < 4 ? 1 : 0), 8));
    // a uniform block repeats along both axes and is written as rows
    assertArrayEquals(pattern(8, ENDPOINTS, 0, 0).getRecord(), TreeReader.patternRecord(palette((x, y) -> 0), 8));
    assertNull(TreeReader.patternRecord(palette((x, y) -> (x + y) & 1), 8));
    // the last row alone breaks the columns' repetition
    assertNull(TreeReader.patternRecord(palette((x, y) -> y == 7 && x == 7 ? 1 : 0), 8));
  }

  @Test
  void expandsEveryPatternOfATreeIntoItsPalette() throws Mcv2Exception {
    final TreeNode pattern = pattern(16, ENDPOINTS, 1, 0x5A);
    final TreeNode palette = TreeNode.leaf(
      Mcv2Format.MODE_PALETTE,
      0,
      TreeReader.fullPalette(PatternRecord.expand(pattern.getRecord(), 0, 16, null, null), 16)
    );
    final TreeNode solid = solid(1, 1, 1);
    assertEquals(
      TreeNode.split(palette, solid, palette, palette),
      TreeReader.withPalettes(TreeNode.split(pattern, solid, pattern, pattern), 32)
    );
    assertSame(solid, TreeReader.withPalettes(solid, 32));
    // the inverse of withPatterns
    assertEquals(
      TreeNode.split(pattern, solid, pattern, pattern),
      TreeReader.withPatterns(TreeReader.withPalettes(TreeNode.split(pattern, solid, pattern, pattern), 32), 32)
    );
  }

  @Test
  void refusesToExpandAnInvalidPattern() {
    final TreeNode invalid = TreeNode.leaf(Mcv2Format.MODE_PATTERN, 0, new byte[] { 1, 2, 3, 4, 5, 6, 2, 0 });
    assertThrows(Mcv2Exception.class, () -> TreeReader.withPalettes(invalid, 8));
  }

  @Test
  void rewritesOnlyRepeatingPalettes() {
    final TreeNode checker = TreeNode.leaf(Mcv2Format.MODE_PALETTE, 0, palette((x, y) -> (x + y) & 1));
    assertSame(checker, TreeReader.withPatterns(checker, 8));
    final TreeNode solid = solid(1, 1, 1);
    assertSame(solid, TreeReader.withPatterns(solid, 8));
    final TreeNode stripes = TreeNode.leaf(Mcv2Format.MODE_PALETTE, 0, palette((x, y) -> y & 1));
    assertEquals(
      TreeNode.leaf(Mcv2Format.MODE_PATTERN, 0, TreeReader.patternRecord(palette((x, y) -> y & 1), 8)),
      TreeReader.withPatterns(stripes, 8)
    );
  }
}

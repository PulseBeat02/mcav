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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.FrameParserTest.flagsOf;
import static me.brandonli.mcav.media.mcv2.FrameParserTest.message;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.mcv2.encode.FrameWriter;
import me.brandonli.mcav.media.mcv2.encode.TreeNode;
import org.junit.jupiter.api.Test;

/**
 * The parser's rules one by one, each broken in an otherwise valid frame: the stored index forms, the derived form's
 * symbol table, walk plane and tables, and the keyframe rules.
 */
final class FrameParserRulesTest {

  private static final byte[] ENDPOINTS = { 16, 32, 48, (byte) 240, (byte) 224, (byte) 208 };
  private static final byte[] OTHER_ENDPOINTS = { 24, 40, 56, (byte) 232, (byte) 216, (byte) 200 };

  /** Where the parts of a derived-form frame are, computed like the parser does. */
  private record Layout(int counts, int table, int plane, int walk, int twoLevel, int head, int start, int total) {
    static Layout of(final byte[] frame) {
      final int flags = flagsOf(frame);
      final int roots = (int) Mcv2Format.u32(frame, 24);
      final int groups = (roots + 31) / 32;
      final int counts = 48 + groups * 4 + ((groups + 7) / 8) * 4;
      final int descriptors = Mcv2Format.u16(frame, counts) + Mcv2Format.u16(frame, counts + 2) + Mcv2Format.u16(frame, counts + 4);
      int base = counts + 6;
      int width = 8;
      final int table = base;
      if ((flags & Mcv2Format.PACKED_SYMBOLS) != 0) {
        width = Mcv2Format.symbolWidth(frame[base]);
        base += 1 + frame[base];
      }
      final int plane = base;
      final int walk = plane + (descriptors * width + 7) / 8;
      final boolean twoLevel = (flags & Mcv2Format.TWO_LEVEL_WALK) != 0;
      final int head = walk + Mcv2Format.walkBytes((descriptors + 7) / 8, twoLevel);
      return new Layout(counts, table, plane, walk, twoLevel ? walk : -1, head, (int) Mcv2Format.u32(frame, 28), frame.length);
    }
  }

  private static TreeNode patterns8() {
    return split(split(pattern(8, ENDPOINTS, 1, 0x3C)));
  }

  /** A keyframe of two roots whose pattern leaves fill both tables. */
  private static byte[] tabled() {
    return keyframe(64, 32, DERIVED, patterns8(), split(split(pattern(8, OTHER_ENDPOINTS, 1, 0x3C))));
  }

  @Test
  void theDerivedFixturesHaveTheExpectedShape() throws Mcv2Exception {
    final byte[] frame = tabled();
    final int flags = flagsOf(frame);
    assertEquals(Mcv2Format.ENDPOINT_TABLE, flags & Mcv2Format.ENDPOINT_TABLE);
    assertEquals(Mcv2Format.SELECTOR_TABLE_8, flags & Mcv2Format.SELECTOR_TABLE);
    assertEquals(Mcv2Format.TWO_LEVEL_WALK, flags & Mcv2Format.TWO_LEVEL_WALK);
    FrameParser.parse(frame);
  }

  @Test
  void refusesAShortOrOversizedSymbolTable() {
    final byte[] frame = tabled();
    final Layout layout = Layout.of(frame);
    assertEquals("Short symbol table", message(withWord(frame, 28, layout.table())));
    assertEquals("Invalid symbol table size", message(withByte(frame, layout.table(), 0)));
    assertEquals("Invalid symbol table size", message(withByte(frame, layout.table(), 33)));
    // a P frame of one motion leaf ends a few bytes after its one-entry table, so a count of 32 overruns the frame
    final byte[] tiny = predicted(8, 8, 0, 0, DERIVED, motion(1, 1));
    final Layout small = Layout.of(tiny);
    assertTrue(small.table() + 1 + 32 > tiny.length);
    assertEquals("Invalid symbol table size", message(withByte(tiny, small.table(), 32)));
  }

  @Test
  void refusesAnUnsortedSymbolTable() {
    final byte[] frame = keyframe(64, 32, DERIVED, solid(1, 1, 1), split(leaf(Mcv2Format.MODE_PALETTE, 0, 16, 3)));
    final Layout layout = Layout.of(frame);
    assertTrue(frame[layout.table()] >= 2);
    final byte[] swapped = withByte(frame, layout.table() + 1, frame[layout.table() + 2]);
    assertEquals("Noncanonical symbol table", message(swapped));
  }

  @Test
  void refusesBrokenWalkWidths() {
    final byte[] frame = tabled();
    final Layout layout = Layout.of(frame);
    final int walk = layout.twoLevel();
    assertEquals("Short walk widths", message(withWord(frame, 28, walk + 1)));
    assertEquals("Invalid walk delta widths", message(withByte(frame, walk, 0)));
    assertEquals("Invalid walk delta widths", message(withByte(frame, walk, 17)));
    assertEquals("Invalid walk delta widths", message(withByte(frame, walk + 1, 0)));
    assertEquals("Invalid walk delta widths", message(withByte(frame, walk + 1, 17)));
    assertEquals("Invalid walk delta widths", message(withByte(withByte(frame, walk, 9), walk + 1, 8)));
  }

  @Test
  void refusesWalkWidthsWiderThanNeeded() {
    final byte[] frame = tabled();
    final int walk = Layout.of(frame).twoLevel();
    // a wider split field decodes every entry the same way, so only the minimality rule catches it
    assertEquals("Walk delta widths are not minimal", message(withByte(frame, walk + 1, frame[walk + 1] + 1)));
  }

  @Test
  void refusesAWiderCursorFieldEvenWhenItDecodesTheSame() {
    // twenty palette roots and no split: every split delta is zero, so a wider cursor field reads the same entries
    final byte[] frame = keyframe(160, 128, DERIVED, repeat(leaf(Mcv2Format.MODE_PALETTE, 0, 32, 3), 20));
    final int walk = Layout.of(frame).twoLevel();
    assertTrue(walk > 0);
    assertEquals(12, frame[walk]);
    assertEquals(1, frame[walk + 1]);
    assertEquals("Walk delta widths are not minimal", message(withByte(frame, walk, 13)));
  }

  @Test
  void refusesBrokenTableCounts() {
    final byte[] frame = tabled();
    final Layout layout = Layout.of(frame);
    assertEquals("Short endpoint table", message(withWord(frame, 28, layout.head())));
    assertEquals("Invalid endpoint table size", message(withByte(frame, layout.head(), 0)));
    assertEquals("Truncated endpoint table", message(withByte(frame, layout.head(), 255)));
    assertEquals("Short selector table sizes", message(withWord(frame, 28, layout.head() + 2)));
    assertEquals("Selector table size contradicts its flag", message(withByte(frame, layout.head() + 2, 1)));
    assertEquals("Selector table size contradicts its flag", message(withByte(frame, layout.head() + 1, 0)));
  }

  @Test
  void refusesATruncatedSelectorTable() {
    final List<TreeNode> leaves = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      leaves.add(pattern(8, new byte[] { (byte) i, 1, 2, 3, 4, 5 }, 0, 0x0F));
    }
    final TreeNode quarter1 = TreeNode.split(leaves.get(0), leaves.get(1), leaves.get(2), leaves.get(3));
    final TreeNode quarter2 = TreeNode.split(leaves.get(4), leaves.get(5), leaves.get(6), leaves.get(7));
    final TreeNode quarter3 = TreeNode.split(leaves.get(8), leaves.get(9), leaves.get(10), leaves.get(11));
    final TreeNode quarter4 = TreeNode.split(leaves.get(12), leaves.get(13), leaves.get(14), leaves.get(15));
    final byte[] frame = keyframe(32, 32, DERIVED, TreeNode.split(quarter1, quarter2, quarter3, quarter4));
    assertEquals(0, flagsOf(frame) & Mcv2Format.ENDPOINT_TABLE);
    assertEquals(Mcv2Format.SELECTOR_TABLE_8, flagsOf(frame) & Mcv2Format.SELECTOR_TABLE);
    assertEquals("Truncated selector table", message(withByte(frame, Layout.of(frame).head(), 255)));
  }

  @Test
  void refusesRepeatedTableEntries() {
    final byte[] frame = tabled();
    // the two endpoint pairs are the last twelve bytes, the one selector word the two before them
    final byte[] endpoints = frame.clone();
    System.arraycopy(frame, frame.length - 12, endpoints, frame.length - 6, 6);
    assertEquals("Noncanonical endpoint table", message(endpoints));
    final byte[] words = keyframe(64, 32, DERIVED, patterns8(), split(split(pattern(8, ENDPOINTS, 1, 0x3C + 1))));
    final byte[] repeated = words.clone();
    final int wordsAt = words.length - 6 - 4;
    System.arraycopy(words, wordsAt, repeated, wordsAt + 2, 2);
    assertEquals("Noncanonical selector table", message(repeated));
  }

  @Test
  void refusesRgb565EndpointsWithoutATable() {
    final byte[] frame = keyframe(8, 8, DERIVED, leaf(Mcv2Format.MODE_PALETTE, 0, 32, 1));
    assertEquals("565 endpoints without an endpoint table", message(withFlags(frame, flagsOf(frame) | Mcv2Format.ENDPOINT_565)));
  }

  @Test
  void refusesASkippedKeyframeLeafWithoutADefaultColour() {
    // a skipped root of the derived form, which has no descriptor
    assertEquals(
      "Temporal keyframe leaf",
      message(withoutDefault(keyframe(64, 32, DERIVED, solid(5, 5, 5), leaf(Mcv2Format.MODE_PALETTE, 0, 32, 1))))
    );
    // a skip descriptor of the stored form
    assertEquals(
      "Temporal keyframe leaf",
      message(withoutDefault(keyframe(64, 32, SHORT_PLAIN, solid(5, 5, 5), leaf(Mcv2Format.MODE_PALETTE, 0, 32, 1))))
    );
    // a skipped child of the derived form
    final TreeNode quarters = TreeNode.split(
      solid(5, 5, 5),
      leaf(Mcv2Format.MODE_PALETTE, 0, 16, 1),
      leaf(Mcv2Format.MODE_PALETTE, 0, 16, 2),
      leaf(Mcv2Format.MODE_PALETTE, 0, 16, 3)
    );
    assertEquals("Temporal keyframe leaf", message(withoutDefault(keyframe(64, 32, DERIVED, quarters, quarters))));
  }

  private static byte[] withoutDefault(final byte[] frame) {
    assertEquals(Mcv2Format.DEFAULT_SOLID, flagsOf(frame) & Mcv2Format.DEFAULT_SOLID);
    return withWord(withFlags(frame, flagsOf(frame) & ~Mcv2Format.DEFAULT_SOLID), 36, 0);
  }

  @Test
  void refusesADerivedDirectoryOverADenseStoredIndex() {
    final byte[] frame = predicted(32, 32, 0, 0, SHORT_PLAIN, motion(1, 1));
    assertEquals(
      "Derived directory requires the sparse root form",
      message(withFlags(frame, flagsOf(frame) | Mcv2Format.DERIVED_DIRECTORY))
    );
  }

  @Test
  void readsEveryStoredCheckpointOfALongDirectory() throws Mcv2Exception {
    final List<TreeNode> roots = new ArrayList<>();
    for (int i = 0; i < 33 * 9; i++) {
      roots.add(i % 7 == 0 ? motion(1, 1) : TreeNode.skip());
    }
    final byte[] frame = FrameWriter.write(32 * 33, 32 * 9, 1, 0, false, 0, 0, roots, WIDE_DIRECTORY);
    assertEquals(Mcv2Format.SPARSE | Mcv2Format.DERIVED_DIRECTORY, flagsOf(frame) & (Mcv2Format.SPARSE | Mcv2Format.DERIVED_DIRECTORY));
    assertEquals(33 * 9, FrameParser.parse(frame).getLeafCount());
    // the ninth group's checkpoint is the second one stored
    final int groups = (33 * 9 + 31) / 32;
    assertEquals("Bad root directory", message(withWord(frame, 48 + groups * 4 + 4, 0)));
  }

  @Test
  void refusesBrokenSparseChildren() {
    final TreeNode sparse = TreeNode.split(motion(1, 1), TreeNode.skip(), TreeNode.skip(), motion(2, 2));
    final byte[] frame = predicted(32, 32, 0, 0, SHORT, sparse);
    // dense root descriptor at 48, then the child mask at 51 and two three-byte child descriptors
    assertEquals(19, frame[50] & 31);
    assertEquals("Invalid sparse child mask or layout", message(withWord(frame, 28, 51)));
    assertEquals("Invalid sparse child mask or layout", message(withByte(frame, 51, 15)));
    assertEquals("Truncated sparse children", message(withWord(frame, 28, 52)));
    final byte[] zero = withByte(withByte(withByte(frame, 52, 0), 53, 0), 54, 0);
    assertEquals("Explicit sparse child skip", message(zero));
    final byte[] wide = predicted(32, 32, 0, 0, WIDE_IMMEDIATE, split(motion(1, 1)));
    assertEquals(16, wide[51]);
    assertEquals("Invalid sparse child mask or layout", message(withByte(wide, 51, 19)));
  }

  @Test
  void refusesTemporalLeavesInAKeyframe() {
    final byte[] immediate = predicted(32, 32, 0, 0, SHORT, motion(1, 1));
    assertEquals("Temporal keyframe leaf", message(asKeyframe(immediate)));
    final byte[] stored = predicted(32, 32, 0, 0, SHORT_PLAIN, motion(1, 1));
    assertEquals("Temporal keyframe leaf", message(asKeyframe(stored)));
    final byte[] compact = predicted(32, 32, 0, 0, SHORT_PLAIN, TreeNode.leaf(Mcv2Format.MODE_COMPACT, 0, new byte[] { 0, 5 }));
    assertEquals("Temporal keyframe leaf", message(asKeyframe(compact)));
    final byte[] residual = predicted(32, 32, 0, 0, SHORT_PLAIN, leaf(8, 3, 32, 1));
    assertEquals("Temporal keyframe leaf", message(asKeyframe(residual)));
  }

  private static byte[] asKeyframe(final byte[] frame) {
    return withWord(withFlags(frame, flagsOf(frame) | Mcv2Format.KEYFRAME), 16, 1);
  }

  /**
   * A 4096x1344 wide-index P frame of exactly {@link Mcv2Format#MAX_FRAME_BYTES}: 5,262 roots of sixteen 194-byte
   * residual leaves (84 index and 3,104 payload bytes each), one root worth 1,455 more bytes, 112 skipped roots and one
   * last root carrying a motion leaf. With immediate motion that leaf's record rides in its descriptor, which the
   * reference decoder materializes after the frame, so the frame has no room for it; stored normally it would not fit
   * the length limit at all.
   */
  private static List<TreeNode> fullFrame(final TreeNode last) {
    final List<TreeNode> roots = new ArrayList<>();
    final TreeNode heavy = split(split(leaf(11, 0, 8, 3)));
    for (int i = 0; i < 5262; i++) {
      roots.add(heavy);
    }
    // a split of four splits: 80 index bytes, then 7 x 194 + 5 + 4 x 3 = 1,375 payload bytes, and four skipped leaves
    final TreeNode big = leaf(11, 0, 8, 3);
    final TreeNode tuneA = TreeNode.split(big, big, big, big);
    final TreeNode tuneB = TreeNode.split(big, big, big, leaf(8, 0, 8, 1));
    final TreeNode tuneC = TreeNode.split(solid(1, 1, 1), solid(2, 2, 2), solid(3, 3, 3), solid(4, 4, 4));
    final TreeNode tuneD = TreeNode.split(TreeNode.skip(), TreeNode.skip(), TreeNode.skip(), TreeNode.skip());
    roots.add(TreeNode.split(tuneA, tuneB, tuneC, tuneD));
    while (roots.size() < 128 * 42 - 1) {
      roots.add(TreeNode.skip());
    }
    roots.add(last);
    return roots;
  }

  @Test
  void refusesImmediateRecordsBeyondTheAddressSpace() throws Mcv2Exception {
    final byte[] full = FrameWriter.write(4096, 1344, 1, 0, false, 0, 0, fullFrame(TreeNode.skip()), WIDE_IMMEDIATE);
    assertEquals(Mcv2Format.MAX_FRAME_BYTES, full.length);
    assertEquals(0, flagsOf(full) & Mcv2Format.SPARSE);
    FrameParser.parse(full);
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
      FrameWriter.write(4096, 1344, 1, 0, false, 0, 0, fullFrame(motion(1, 1)), WIDE_IMMEDIATE)
    );
    assertTrue(exception.getMessage().contains("Immediate records exceed the frame address range"), exception.getMessage());
  }
}

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
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.DERIVED_565;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.SHORT;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.keyframe;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.leaf;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.motion;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.pattern;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.predicted;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.repeat;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.solid;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.split;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.testing.EqualityAssertions;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * The frame writer's own rules and fallbacks; that it writes the reference's bytes for every conformance frame is
 * {@link FrameWriterConformanceTest}.
 */
final class FrameWriterTest {

  private static final byte[] ENDPOINTS = { 1, 2, 3, (byte) 250, (byte) 251, (byte) 252 };

  private static final byte[] EXACT_ENDPOINTS = { 0, 0, 0, (byte) 255, (byte) 255, (byte) 255 };

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FrameWriter.class);
  }

  @Test
  void comparesKeysByContent() {
    EqualityAssertions.assertEqualityContract(
      new FrameWriter.Key(new byte[] { 1, 2 }),
      new FrameWriter.Key(new byte[] { 1, 2 }),
      new FrameWriter.Key(new byte[] { 1, 3 }),
      new FrameWriter.Key(new byte[] { 1 })
    );
  }

  private static int flags(final byte[] frame) throws Mcv2Exception {
    return FrameParser.parse(frame).getFlags();
  }

  private static List<TreeNode> roots(final byte[] frame) throws Mcv2Exception {
    return TreeReader.roots(FrameParser.parse(frame));
  }

  @Test
  void refusesInvalidDimensionsAndRootCounts() {
    final List<TreeNode> one = List.of(solid(1, 1, 1));
    for (final int[] size : new int[][] { { 0, 32 }, { 4097, 32 }, { 32, 0 }, { 32, 4097 } }) {
      assertThrows(IllegalArgumentException.class, () -> FrameWriter.write(size[0], size[1], 0, 0, true, 0, 0, one, DERIVED));
    }
    assertThrows(IllegalArgumentException.class, () -> FrameWriter.write(64, 32, 0, 0, true, 0, 0, one, DERIVED));
    assertThrows(NullPointerException.class, () -> FrameWriter.write(32, 32, 0, 0, true, 0, 0, null, DERIVED));
    assertThrows(NullPointerException.class, () -> FrameWriter.write(32, 32, 0, 0, true, 0, 0, one, null));
  }

  @Test
  void refusesLeavesThatAreNotLeaves() {
    for (final FrameWriter.Options options : List.of(DERIVED, SHORT)) {
      for (final int mode : new int[] { Mcv2Format.MODE_SPARSE_SPLIT, Mcv2Format.MODE_IMMEDIATE_MOTION }) {
        final TreeNode node = TreeNode.leaf(mode, 0, new byte[2]);
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> predicted(32, 32, 0, 0, options, node)
        );
        assertEquals("Illegal leaf mode " + mode, exception.getMessage());
      }
      final TreeNode shortRecord = TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[2]);
      assertEquals(
        "Record length disagrees with its mode",
        assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, options, shortRecord)).getMessage()
      );
      final TreeNode deep = split(split(split(solid(1, 1, 1))));
      assertEquals(
        "Split below the bounded depth",
        assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, options, deep)).getMessage()
      );
    }
  }

  @Test
  void refusesATreeThatDoesNotSerializeToAValidFrame() {
    // a keyframe leaf that needs a reference is written faithfully, then refused by the parse-back
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, DERIVED, motion(1, 1)));
    assertTrue(exception.getMessage().startsWith("The tree does not serialize to a valid frame"), exception.getMessage());
  }

  @Test
  void keepsFullEndpointsWhenAPairIsNotRgb565() throws Mcv2Exception {
    final TreeNode exact = split(split(pattern(8, EXACT_ENDPOINTS, 0, 0x0F)));
    final byte[] coarse = keyframe(64, 32, DERIVED_565, exact, exact);
    assertEquals(
      Mcv2Format.ENDPOINT_TABLE | Mcv2Format.ENDPOINT_565,
      flags(coarse) & (Mcv2Format.ENDPOINT_TABLE | Mcv2Format.ENDPOINT_565)
    );
    final byte[] full = keyframe(64, 32, DERIVED_565, exact, split(split(pattern(8, ENDPOINTS, 0, 0x0F))));
    assertEquals(Mcv2Format.ENDPOINT_TABLE, flags(full) & (Mcv2Format.ENDPOINT_TABLE | Mcv2Format.ENDPOINT_565));
    assertEquals(roots(full), roots(keyframe(64, 32, DERIVED, exact, split(split(pattern(8, ENDPOINTS, 0, 0x0F))))));
  }

  @Test
  void leavesDescriptorsUnpackedBeyondThirtyTwoSymbols() throws Mcv2Exception {
    // six residual modes at eight quantizers are 48 distinct descriptors
    final int[] modes = { 8, 9, 10, 11, 13, 15 };
    final List<TreeNode> roots = new ArrayList<>();
    for (int root = 0; root < 3; root++) {
      final TreeNode[] quarters = new TreeNode[4];
      for (int quarter = 0; quarter < 4; quarter++) {
        final TreeNode[] leaves = new TreeNode[4];
        for (int i = 0; i < 4; i++) {
          final int n = root * 16 + quarter * 4 + i;
          leaves[i] = leaf(modes[n % 6], n / 6, 8, n);
        }
        quarters[quarter] = TreeNode.split(leaves[0], leaves[1], leaves[2], leaves[3]);
      }
      roots.add(TreeNode.split(quarters[0], quarters[1], quarters[2], quarters[3]));
    }
    final byte[] frame = FrameWriter.write(96, 32, 1, 0, false, 0, 0, roots, DERIVED);
    assertEquals(Mcv2Format.DERIVED_OFFSETS, flags(frame) & (Mcv2Format.DERIVED_OFFSETS | Mcv2Format.PACKED_SYMBOLS));
    assertEquals(roots, roots(frame));
  }

  @Test
  void namesNoSelectorWordsBeyondTheTableSize() throws Mcv2Exception {
    // 512 distinct eight-pixel words: too many for a table of that size
    final List<TreeNode> roots = new ArrayList<>();
    for (int root = 0; root < 32; root++) {
      final TreeNode[] leaves = new TreeNode[16];
      for (int i = 0; i < 16; i++) {
        final int n = root * 16 + i;
        leaves[i] = pattern(8, EXACT_ENDPOINTS, n % 2, n / 2);
      }
      final TreeNode[] quarters = new TreeNode[4];
      for (int quarter = 0; quarter < 4; quarter++) {
        quarters[quarter] = TreeNode.split(leaves[quarter * 4], leaves[quarter * 4 + 1], leaves[quarter * 4 + 2], leaves[quarter * 4 + 3]);
      }
      roots.add(TreeNode.split(quarters[0], quarters[1], quarters[2], quarters[3]));
    }
    final byte[] frame = FrameWriter.write(256, 128, 0, 0, true, 0, 0, roots, DERIVED);
    assertEquals(0, flags(frame) & Mcv2Format.SELECTOR_TABLE);
    assertEquals(Mcv2Format.ENDPOINT_TABLE, flags(frame) & Mcv2Format.ENDPOINT_TABLE);
    final List<TreeNode> expanded = new ArrayList<>();
    for (final TreeNode root : roots) {
      expanded.add(TreeReader.withPalettes(root, 32));
    }
    assertEquals(expanded, roots(frame));
  }

  @Test
  void acceptsAQuantizerOnlyWhereTheModeHasOne() throws Mcv2Exception {
    for (final TreeNode node : List.of(
      TreeNode.leaf(Mcv2Format.MODE_MOTION, 1, new byte[] { 1, 1 }),
      TreeNode.leaf(Mcv2Format.MODE_SOLID, 7, new byte[3])
    )) {
      assertEquals(
        "Quantizer on a mode without one",
        assertThrows(IllegalArgumentException.class, () -> predicted(32, 32, 0, 0, SHORT, node)).getMessage()
      );
    }
    final TreeNode residual = leaf(Mcv2Format.MODE_RESIDUAL, 7, 32, 1);
    final TreeNode compact = TreeNode.leaf(Mcv2Format.MODE_COMPACT, 4, new byte[] { 0, 5 });
    final Mcv2Frame frame = FrameParser.parse(predicted(64, 32, 0, 0, SHORT, residual, compact));
    assertEquals(7, frame.getLeaf(0).q());
    assertEquals(4, frame.getLeaf(1).q());
  }

  @Test
  void writesDenseChildTablesUnlessTheShortSparseFormApplies() throws Mcv2Exception {
    final TreeNode half = TreeNode.split(motion(1, 1), TreeNode.skip(), TreeNode.skip(), motion(2, 2));
    // sparse children need the three-byte descriptors
    final FrameWriter.Options wideSparse = new FrameWriter.Options(false, true, true, false, false, false, false, false, false, false);
    final byte[] wide = predicted(32, 32, 0, 0, wideSparse, half);
    assertEquals(Mcv2Format.MODE_SPLIT, wide[51] & 31);
    assertEquals(List.of(half), roots(wide));
    // a split without a skipped child gains nothing from a mask
    final TreeNode full = split(motion(1, 1));
    final byte[] dense = predicted(32, 32, 0, 0, SHORT, full);
    assertEquals(Mcv2Format.MODE_SPLIT, dense[50] & 31);
    final byte[] sparse = predicted(32, 32, 0, 0, SHORT, half);
    assertEquals(Mcv2Format.MODE_SPARSE_SPLIT, sparse[50] & 31);
  }

  @Test
  void retriesTheWideFormWithTheDefaultColourRestored() throws Mcv2Exception {
    // 128 intra roots of 768 bytes overflow sixteen-bit addresses; the 128 solid roots are the default colour
    final List<TreeNode> roots = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      roots.add(i % 2 == 0 ? solid(9, 9, 9) : split(leaf(Mcv2Format.MODE_INTRA + 3, 0, 16, i)));
    }
    final byte[] frame = FrameWriter.write(512, 512, 0, 0, true, 0, 0, roots, SHORT);
    final int flags = flags(frame);
    assertEquals(0, flags & Mcv2Format.SHORT_INDEX);
    assertEquals(Mcv2Format.DEFAULT_SOLID, flags & Mcv2Format.DEFAULT_SOLID);
    assertEquals(0x090909, FrameParser.parse(frame).getDefaultColor());
    assertEquals(roots, roots(frame));
  }

  @Test
  void fallsBackToTheStoredFormBeyondSixteenBitDescriptorCounts() throws Mcv2Exception {
    // 16,384 roots of 21 descriptors each: 344,064 descriptors cannot be counted in the derived form
    final List<TreeNode> roots = Collections.nCopies(128 * 128, split(split(motion(1, 1))));
    final byte[] frame = FrameWriter.write(4096, 4096, 1, 0, false, 0, 0, roots, DERIVED);
    final int flags = flags(frame);
    assertEquals(0, flags & (Mcv2Format.DERIVED_OFFSETS | Mcv2Format.SHORT_INDEX));
    assertEquals(128 * 128 * 16, FrameParser.parse(frame).getLeafCount());
  }

  @Test
  void writesNoSymbolTableWithoutDescriptors() throws Mcv2Exception {
    final byte[] skipped = predicted(64, 32, 0, 0, DERIVED, repeat(TreeNode.skip(), 2));
    assertEquals(0, flags(skipped) & Mcv2Format.PACKED_SYMBOLS);
    assertEquals(2, FrameParser.parse(skipped).getLeafCount());
    final byte[] uniform = keyframe(64, 32, DERIVED, solid(3, 3, 3), solid(3, 3, 3));
    assertEquals(0, flags(uniform) & Mcv2Format.PACKED_SYMBOLS);
    assertEquals(List.of(solid(3, 3, 3), solid(3, 3, 3)), roots(uniform));
  }
}

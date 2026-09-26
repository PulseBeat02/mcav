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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The live search's settings: what they accept, which modes and quantizers they try, and the live profile. */
final class LiveSearchTest {

  private static LiveSearch search(
    final int smallest,
    final double skip,
    final double split,
    final double fine,
    final double good,
    final int modes,
    final int keyModes,
    final int classes,
    final int quantizers,
    final int searchBlock,
    final int fast
  ) {
    return new LiveSearch(
      smallest,
      skip,
      split,
      split,
      fine,
      good,
      0,
      modes,
      modes,
      keyModes,
      classes,
      quantizers,
      true,
      searchBlock,
      true,
      fast
    );
  }

  private static LiveSearch valid() {
    return search(8, 26.5, 52.5, 52.5, 0, LiveSearch.ALL_MODES, LiveSearch.ALL_MODES, LiveSearch.ALL_CLASSES, 1, 8, 0);
  }

  @Test
  void acceptsTheExactAndLiveSearches() {
    assertEquals(8, valid().smallestBlock());
    assertEquals(LiveSearch.EXACT_SKIP, LiveSearch.EXACT.skipThreshold());
    assertEquals(LiveSearch.EXACT_SPLIT, LiveSearch.EXACT.splitThreshold());
    assertEquals(LiveSearch.EXACT_SPLIT, LiveSearch.EXACT.steadySplitThreshold());
    assertEquals(LiveSearch.EXACT_SPLIT, LiveSearch.EXACT.fineThreshold());
    assertEquals(0, LiveSearch.EXACT.goodThreshold());
    assertFalse(LiveSearch.EXACT.seededMotion());
    assertFalse(LiveSearch.EXACT.coarseEndpoints());
    assertEquals(0, LiveSearch.EXACT.shortcuts());
    assertSame(LiveSearch.LIVE, EncoderSettings.LIVE.live());
    assertEquals(120, EncoderSettings.LIVE.keyInterval());
    // the largest lambda that keeps the 1080p30 proxy at a VMAF mean of 75
    assertEquals(72, EncoderSettings.LIVE.lambda());
    assertTrue(LiveSearch.LIVE.seededMotion());
    assertTrue(LiveSearch.LIVE.coarseEndpoints());
    assertEquals(16, LiveSearch.LIVE.searchBlock());
    // a superblock the previous frame coded whole is split only above 450 lambda, one it split above 150
    assertEquals(150, LiveSearch.LIVE.splitThreshold());
    assertEquals(450, LiveSearch.LIVE.steadySplitThreshold());
    // P frames try the modes the reference chooses on gameplay: solid colours and the 2x2 and reduced intra grids, but
    // not the finer grids, which keyframes try
    assertTrue(LiveSearch.LIVE.tries(MODE_SOLID, false, 32));
    assertTrue(LiveSearch.LIVE.tries(MODE_INTRA + 1, false, 32));
    assertTrue(LiveSearch.LIVE.tries(MODE_INTRA_Y4C1, false, 16));
    assertFalse(LiveSearch.LIVE.tries(MODE_INTRA + 2, false, 16));
    assertFalse(LiveSearch.LIVE.tries(MODE_RESIDUAL, false, 16));
    assertTrue(LiveSearch.LIVE.tries(MODE_INTRA + 2, true, 32));
    assertTrue(LiveSearch.LIVE.tries(MODE_PATTERN, false, 8));
    // of the compact classes the reference chooses on gameplay, the ones that pay for their search: DC and both 4-bit
    // 4x4 grids, each at the quantizer its fitted values need; local motion first at half resolution
    assertEquals(0xD, LiveSearch.LIVE.compactClasses());
    assertEquals(LiveSearch.ALL_QUANTIZERS, LiveSearch.LIVE.quantizers());
    assertEquals(
      LiveSearch.FAST_GRIDS | LiveSearch.FAST_PALETTES | LiveSearch.ONE_PREDICTION | LiveSearch.FIT_ONE | LiveSearch.HALF_MOTION,
      LiveSearch.LIVE.shortcuts()
    );
  }

  @Test
  void refusesValuesOutOfRange() {
    final int all = LiveSearch.ALL_MODES;
    final int classes = LiveSearch.ALL_CLASSES;
    assertThrows(IllegalArgumentException.class, () -> search(4, 1, 1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(64, 1, 1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, -1, 1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, Double.NaN, 1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, Double.POSITIVE_INFINITY, 1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, Double.POSITIVE_INFINITY, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, Double.POSITIVE_INFINITY, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> new LiveSearch(8, 1, 1, 1, 1, 0, -1, all, all, all, classes, 1, true, 8, true, 0));
    assertThrows(IllegalArgumentException.class, () ->
      new LiveSearch(8, 1, 1, 1, 1, 0, Double.NaN, all, all, all, classes, 1, true, 8, true, 0)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new LiveSearch(8, 1, 1, 1, 1, 0, Double.POSITIVE_INFINITY, all, all, all, classes, 1, true, 8, true, 0)
    );
    assertThrows(IllegalArgumentException.class, () -> new LiveSearch(8, 1, 1, -1, 1, 0, 0, all, all, all, classes, 1, true, 8, true, 0));
    assertThrows(IllegalArgumentException.class, () ->
      new LiveSearch(8, 1, 1, Double.NaN, 1, 0, 0, all, all, all, classes, 1, true, 8, true, 0)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new LiveSearch(8, 1, 1, Double.POSITIVE_INFINITY, 1, 0, 0, all, all, all, classes, 1, true, 8, true, 0)
    );
    // the small blocks try a subset of the modes
    final int motion = 1 << MODE_MOTION;
    assertThrows(IllegalArgumentException.class, () -> new LiveSearch(8, 1, 1, 1, 1, 0, 0, motion, all, all, classes, 1, true, 8, true, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, -1, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, Double.POSITIVE_INFINITY, 1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, -1, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, Double.NaN, 0, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, -1, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, Double.NaN, all, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, 1 << 16, all, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, all, 1 << 16, classes, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, all, all, 1 << 5, 1, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, all, all, classes, 1 << 5, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, all, all, classes, 1, 12, 0));
    assertThrows(IllegalArgumentException.class, () -> search(8, 1, 1, 1, 0, all, all, classes, 1, 64, 0));
    // every block size is accepted
    assertEquals(16, search(16, 1, 1, 1, 0, all, all, classes, 1, 32, 0).smallestBlock());
    assertEquals(32, search(32, 1, 1, 1, 0, all, all, classes, 1, 16, 0).smallestBlock());
  }

  @Test
  void triesTheModesOfTheFrameType() {
    final LiveSearch live = search(8, 1, 1, 1, 0, 1 << MODE_MOTION, 1 << MODE_SOLID, 0, 1, 8, 0);
    assertTrue(live.tries(MODE_MOTION, false, 32));
    assertFalse(live.tries(MODE_SOLID, false, 32));
    assertTrue(live.tries(MODE_SOLID, true, 32));
    assertFalse(live.tries(MODE_MOTION, true, 32));
  }

  @Test
  void triesTheSmallBlockModesBelowTheRoot() {
    final int modes = (1 << MODE_MOTION) | (1 << MODE_PALETTE);
    final LiveSearch live = new LiveSearch(8, 1, 1, 1, 1, 0, 0, modes, 1 << MODE_MOTION, 1 << MODE_SOLID, 0, 1, true, 8, true, 0);
    assertTrue(live.tries(MODE_PALETTE, false, 32));
    assertFalse(live.tries(MODE_PALETTE, false, 16));
    assertFalse(live.tries(MODE_PALETTE, false, 8));
    assertTrue(live.tries(MODE_MOTION, false, 8));
    // keyframes try the keyframe modes at every size
    assertTrue(live.tries(MODE_SOLID, true, 8));
    assertEquals(1 << MODE_MOTION, live.smallModes());
    assertEquals(LiveSearch.LIVE.modes(), LiveSearch.LIVE.smallModes());
  }

  @Test
  void triesTheQuantizersOfItsSetOrTheOneOfLambda() {
    final LiveSearch set = search(8, 1, 1, 1, 0, 0, 0, 0, 0b101, 8, 0);
    assertTrue(set.triesQuantizer(0, 1000));
    assertFalse(set.triesQuantizer(1, 65));
    assertTrue(set.triesQuantizer(2, 65));
    final LiveSearch lambda = search(8, 1, 1, 1, 0, 0, 0, 0, LiveSearch.FROM_LAMBDA, 8, 0);
    assertTrue(lambda.triesQuantizer(1, 65.255994022));
    assertFalse(lambda.triesQuantizer(0, 65.255994022));
    assertTrue(lambda.triesQuantizer(0, 20));
  }

  @ParameterizedTest(name = "lambda {0} -> q{1}")
  @CsvSource({ "0, 0", "1, 0", "31, 0", "33, 1", "65.26, 1", "127, 1", "129, 2", "511, 2", "513, 3", "2047, 3", "2049, 4", "1e9, 4" })
  void derivesTheQuantizerFromLambda(final double lambda, final int q) {
    assertEquals(q, LiveSearch.quantizer(lambda));
  }
}

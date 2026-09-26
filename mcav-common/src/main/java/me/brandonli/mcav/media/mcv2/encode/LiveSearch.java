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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.mcv2.CompactRecord;

/**
 * The search of a live profile: cheaper than the reference's exhaustive search, which the shipped profiles keep, so a
 * frame can be encoded while the video plays. The bitstream is the same and the resource pack decodes it unchanged;
 * only which candidates the encoder tries differs, so a live stream spends more bits for the same picture.
 *
 * <p>A live frame is encoded once, with the estimated global vector and one endpoint precision, where the reference
 * encodes it up to four times. Blocks are searched from the top: a 32-pixel block is coded first, and its quarters
 * only when splitting could still pay. The two thresholds bound a cost, distortion plus lambda times the bits, in
 * units of lambda. At or below {@link #EXACT_SKIP} and {@link #EXACT_SPLIT} they only skip work that cannot change the
 * choice: every leaf but SKIP carries at least two bytes and a descriptor, {@code 26.5} bits, so a SKIP costing at most
 * {@code 26.5 lambda} is never beaten (SKIP is tried first and keeps a tie), and every split carries at least five
 * descriptors, {@code 52.5} bits, so a leaf costing at most {@code 52.5 lambda} is never split. Above them they trade
 * bandwidth for time.
 *
 * @param smallestBlock  the smallest leaf size tried: 8, 16 or 32
 * @param skipThreshold  a block whose SKIP costs at most this many times lambda is coded SKIP, and nothing else is tried
 *                       for it or inside it
 * @param splitThreshold a 32-pixel block whose best leaf costs at most this many times lambda is not split, in a
 *                       keyframe or where the previous frame split the superblock
 * @param steadySplitThreshold the split threshold of a 32-pixel block of a P frame whose superblock the previous frame
 *                       coded whole: a split there is rarer, so a higher threshold saves most of the searches of
 *                       its quarters for little bandwidth
 * @param fineThreshold  a 16-pixel block whose best leaf costs at most this many times lambda is not split
 * @param goodThreshold  a block whose best leaf after SKIP and local motion costs at most this many times lambda tries
 *                       no other leaf; at 0 every candidate the rate bound allows is tried
 * @param childGate      a quarter of a split block whose best leaf after SKIP and local motion costs at most this
 *                       fraction of a quarter of the block's best cost tries no other leaf; 0 turns the gate off
 * @param modes          the leaf modes tried in P frames besides SKIP, as a bit set of mode numbers
 *                       ({@code 1 << MODE_SOLID} and so on)
 * @param smallModes     the leaf modes tried at the 16- and 8-pixel blocks of P frames, a subset of {@code modes}
 * @param keyModes       the leaf modes tried in keyframes, as a bit set; only intra modes apply
 * @param compactClasses the compact classes tried when compact records are, as a bit set of class numbers
 * @param quantizers     the quantizers tried by residual and compact records, as a bit set, or {@link #FROM_LAMBDA} for
 *                       the one {@link #quantizer(double)} derives from lambda
 * @param seededMotion   whether the local motion search is a small diamond around the vectors of the previous frame,
 *                       refined to half pixels, instead of the reference's search of the whole range
 * @param searchBlock    the smallest block size that searches its own local motion: 8, 16 or 32; a smaller block
 *                       predicts with the vector of the block it splits from
 * @param coarseEndpoints whether pattern records may use RGB565 endpoints, the reference's second trial of each vector,
 *                       instead of full ones
 * @param shortcuts      the search's shortcuts, as a bit set: {@link #FAST_GRIDS} intra grid nodes as cell means,
 *                       {@link #FAST_PALETTES} two integer Lloyd iterations on sampled pixels ({@link FastFits}),
 *                       {@link #FAST_COMPACT} compact luma nodes as cell means, {@link #ONE_PREDICTION} compact
 *                       records tried on the closer of the global and the local prediction only, {@link #FIT_PAIR}
 *                       compact records at the two quantizers their fitted values suggest only, {@link #HALF_MOTION}
 *                       local motion searched at half resolution first
 */
public record LiveSearch(
  int smallestBlock,
  double skipThreshold,
  double splitThreshold,
  double steadySplitThreshold,
  double fineThreshold,
  double goodThreshold,
  double childGate,
  int modes,
  int smallModes,
  int keyModes,
  int compactClasses,
  int quantizers,
  boolean seededMotion,
  int searchBlock,
  boolean coarseEndpoints,
  int shortcuts
) {
  /** Intra grid nodes as the means of their cells. */
  public static final int FAST_GRIDS = 1;

  /** Palettes clustered with two integer Lloyd iterations on sampled pixels. */
  public static final int FAST_PALETTES = 2;

  /** Compact luma nodes as the means of their cells. */
  public static final int FAST_COMPACT = 4;

  /** Compact records tried on the closer of the global and the local prediction only. */
  public static final int ONE_PREDICTION = 8;

  /**
   * A compact record tries, of its class's quantizers, only the finest that holds the values fitted to the block without
   * clipping them and the one below it, which clips a few for finer steps: a record's length does not depend on its
   * quantizer, so a coarser one only adds error.
   */
  public static final int FIT_PAIR = 16;

  /**
   * The local motion of 32- and 16-pixel blocks is first searched on the pictures at half resolution, then refined at
   * full resolution around the vector found there.
   */
  public static final int HALF_MOTION = 32;

  /** The largest skip threshold that never changes a decision. */
  public static final double EXACT_SKIP = 26.5;

  /** The largest split threshold that never changes a decision. */
  public static final double EXACT_SPLIT = 52.5;

  /** Every leaf mode the encoder can choose. */
  public static final int ALL_MODES =
    (1 << MODE_MOTION) |
    (1 << MODE_SOLID) |
    (1 << MODE_PALETTE) |
    (0xF << MODE_INTRA) |
    (0xF << MODE_RESIDUAL) |
    (1 << MODE_INTRA_Y4C1) |
    (1 << MODE_RESIDUAL_Y4C1) |
    (1 << MODE_INTRA_Y8C2) |
    (1 << MODE_RESIDUAL_Y8C2) |
    (1 << MODE_COMPACT) |
    (1 << MODE_PATTERN);

  /** Every compact class the encoder can choose. */
  public static final int ALL_CLASSES = 0x11F;

  /** The coarsest quantizer the encoder tries. */
  static final int COARSEST_QUANTIZER = 4;

  /** Every quantizer the encoder tries. */
  public static final int ALL_QUANTIZERS = (1 << (COARSEST_QUANTIZER + 1)) - 1;

  /** {@link #LIVE}'s split thresholds, and the smallest block with its own local motion search. */
  private static final double LIVE_SPLIT = 150;

  private static final double LIVE_STEADY_SPLIT = 450;

  private static final double LIVE_FINE_SPLIT = 300;

  private static final int LIVE_SEARCH_BLOCK = 16;

  /** The quantizer set that stands for the single quantizer derived from lambda. */
  public static final int FROM_LAMBDA = 0;

  /**
   * The P-frame leaf modes of {@link #LIVE}: the ones the reference's search chooses on real gameplay - local motion,
   * solid colours, palettes, the 2x2 intra grid, the reduced intra grid, compact and patterns (in `ship`'s P frames of a
   * 1080p60 gameplay clip they cover all but 1.6% of the area). A set chosen on the SKIP-heavy 1080p60 proxy alone,
   * without the solid colours and intra grids, needed 80% more rate than `ship` for the same VMAF on that gameplay.
   */
  private static final int LIVE_MODES =
    (1 << MODE_MOTION) |
    (1 << MODE_SOLID) |
    (1 << MODE_PALETTE) |
    (1 << (MODE_INTRA + 1)) |
    (1 << MODE_INTRA_Y4C1) |
    (1 << MODE_COMPACT) |
    (1 << MODE_PATTERN);

  /**
   * The compact classes of {@link #LIVE}: of the five `ship` chooses on real gameplay, the three that pay for their
   * search. With the 2x2 grid and the low-frequency class as well, the search costs 18% more CPU per frame for the same
   * rate and quality on both 30 fps sources.
   */
  private static final int LIVE_CLASSES = (1 << CompactRecord.DC_Y) | (1 << CompactRecord.GRID4_N4_YC) | (1 << CompactRecord.GRID4_N4_Y);

  /** The reference's search, restricted to one trial and searched from the top with the exact thresholds only. */
  public static final LiveSearch EXACT = new LiveSearch(
    SMALLEST_BLOCK,
    EXACT_SKIP,
    EXACT_SPLIT,
    EXACT_SPLIT,
    EXACT_SPLIT,
    0,
    0,
    ALL_MODES,
    ALL_MODES,
    ALL_MODES,
    ALL_CLASSES,
    ALL_QUANTIZERS,
    false,
    SMALLEST_BLOCK,
    false,
    0
  );

  /**
   * The search of {@link EncoderSettings#LIVE}, chosen by measurement on the 1080p30 proxy and the 30 fps gameplay clip
   * (the report's lever table): leaves down to 8 pixels, a 16-pixel block only split above 300 lambda, a 32-pixel one
   * above 150 where the previous frame split its superblock and above 450 where it coded it whole, local motion searched
   * from the previous frame's vectors down to 16 pixels and first at half resolution, P frames choosing between local
   * motion, solid colours, palettes, the 2x2 and reduced intra grids, three compact classes at the two quantizers their
   * fitted values suggest, and patterns with RGB565 endpoints, keyframes from every intra mode, and the cheap fits of
   * intra grids and palettes.
   */
  public static final LiveSearch LIVE = new LiveSearch(
    SMALLEST_BLOCK,
    EXACT_SKIP,
    LIVE_SPLIT,
    LIVE_STEADY_SPLIT,
    LIVE_FINE_SPLIT,
    0,
    0,
    LIVE_MODES,
    LIVE_MODES,
    ALL_MODES,
    LIVE_CLASSES,
    ALL_QUANTIZERS,
    true,
    LIVE_SEARCH_BLOCK,
    true,
    FAST_GRIDS | FAST_PALETTES | ONE_PREDICTION | FIT_PAIR | HALF_MOTION
  );

  /**
   * Validates the search.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public LiveSearch {
    Preconditions.checkArgument(isBlockSize(smallestBlock), "Smallest block must be 8, 16 or 32");
    Preconditions.checkArgument(skipThreshold >= 0 && Double.isFinite(skipThreshold), "Skip threshold must be finite and non-negative");
    Preconditions.checkArgument(splitThreshold >= 0 && Double.isFinite(splitThreshold), "Split threshold must be finite and non-negative");
    Preconditions.checkArgument(
      steadySplitThreshold >= 0 && Double.isFinite(steadySplitThreshold),
      "Steady split threshold must be finite and non-negative"
    );
    Preconditions.checkArgument(fineThreshold >= 0 && Double.isFinite(fineThreshold), "Fine threshold must be finite and non-negative");
    Preconditions.checkArgument(goodThreshold >= 0 && Double.isFinite(goodThreshold), "Good threshold must be finite and non-negative");
    Preconditions.checkArgument(childGate >= 0 && Double.isFinite(childGate), "Child gate must be finite and non-negative");
    Preconditions.checkArgument(isBlockSize(searchBlock), "Search block must be 8, 16 or 32");
    Preconditions.checkArgument((modes & ~ALL_MODES) == 0, "Unknown leaf modes");
    Preconditions.checkArgument((smallModes & ~modes) == 0, "Small-block modes must be modes the search tries");
    Preconditions.checkArgument((keyModes & ~ALL_MODES) == 0, "Unknown keyframe leaf modes");
    Preconditions.checkArgument((compactClasses & ~ALL_CLASSES) == 0, "Unknown compact classes");
    Preconditions.checkArgument((quantizers & ~ALL_QUANTIZERS) == 0, "Quantizers must be a subset of 0 to 4");
  }

  /**
   * Checks whether a leaf mode is tried at a block size.
   *
   * @param mode     the mode
   * @param keyframe whether the frame is a keyframe
   * @param size     the block size
   * @return true if the search tries it there
   */
  public boolean tries(final int mode, final boolean keyframe, final int size) {
    final int set = keyframe ? this.keyModes : size < ROOT_SIZE ? this.smallModes : this.modes;
    return ((set >> mode) & 1) != 0;
  }

  /**
   * Checks whether a quantizer is tried at a lambda.
   *
   * @param q      the quantizer
   * @param lambda the rate-distortion trade
   * @return true if the search tries it
   */
  public boolean triesQuantizer(final int q, final double lambda) {
    return this.quantizers == FROM_LAMBDA ? q == quantizer(lambda) : ((this.quantizers >> q) & 1) != 0;
  }

  /**
   * The quantizer that suits a lambda. A uniform quantizer's rate-distortion optimal step grows with the square root of
   * lambda, and the step of quantizer q is {@code 2^q}, so q is half the binary logarithm of lambda, less two, rounded:
   * 0 below lambda 32, 1 up to 128 (the shipped 65), 2 up to 512, 3 up to 2048.
   *
   * @param lambda the rate-distortion trade
   * @return the quantizer, 0 to 4
   */
  public static int quantizer(final double lambda) {
    final long q = Math.round((0.5 * Math.log(Math.max(lambda, 1.0))) / Math.log(2) - 2);
    return (int) Math.min(Math.max(q, 0), COARSEST_QUANTIZER);
  }
}

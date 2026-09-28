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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The native kernels against the Java ones, the oracle: at every level this processor runs, every kernel computes
 * exactly what Java computes on the same inputs, and an encoder with native kernels writes exactly the bytes of one with
 * Java's, in every live search. A level the processor lacks is skipped, never forced; where no library loads there is
 * nothing to compare, and where one should load and does not, the test fails ({@link NativeTesting}).
 */
final class NativeKernelsPropertyTest {

  private static final String SEED = "20260927";

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();

  @Provide
  Arbitrary<NativeKernels.@Nullable Level> levels() {
    final List<NativeKernels.Level> levels = NativeTesting.levels();
    return levels.isEmpty() ? Arbitraries.just(null) : Arbitraries.of(levels);
  }

  @Property(seed = SEED, tries = 2000)
  boolean computesWhatJavaComputes(@ForAll("levels") final NativeKernels.@Nullable Level level, @ForAll final long seed) {
    if (level == null) {
      return true;
    }
    final String difference = KernelDifferential.compare(
      KernelDifferential.of(new Random(seed)),
      new JavaKernels(),
      NativeTesting.kernels(level)
    );
    if (difference != null) {
      throw new AssertionError(difference + " differs at " + level);
    }
    return true;
  }

  /** The live searches the encoder test: the profile, every shortcut and threshold, and the exact search. */
  private static List<LiveSearch> liveSearches() {
    final LiveSearch base = LiveSearch.LIVE;
    final int all = LiveSearch.LIVE.shortcuts() | LiveSearch.QUARTER_MOTION | LiveSearch.CELL_FITS | LiveSearch.FAST_COMPACT;
    return List.of(
      base,
      new LiveSearch(
        base.smallestBlock(),
        base.skipThreshold(),
        base.splitThreshold(),
        base.steadySplitThreshold(),
        base.fineThreshold(),
        base.goodThreshold(),
        base.childGate(),
        base.modes(),
        base.smallModes(),
        base.keyModes(),
        LiveSearch.ALL_CLASSES,
        base.quantizers(),
        base.seededMotion(),
        base.searchBlock(),
        base.coarseEndpoints(),
        all,
        3200,
        true
      ),
      LiveSearch.EXACT
    );
  }

  @Property(seed = SEED, tries = 30)
  boolean encodesWhatJavaEncodes(
    @ForAll("levels") final NativeKernels.@Nullable Level level,
    @ForAll final long seed,
    @ForAll("searches") final LiveSearch search
  ) {
    if (level == null) {
      return true;
    }
    final Random random = new Random(seed);
    final int width = 40 + random.nextInt(60);
    final int height = 30 + random.nextInt(50);
    final int panPerFrame = random.nextInt(7) - 3;
    final EncoderSettings settings = EncoderSettings.LIVE.withKeyInterval(3).withLive(search);
    final Mcv2Encoder java = new Mcv2Encoder(settings, POOL, 2, true, JavaKernels.FACTORY);
    final Mcv2Encoder other = new Mcv2Encoder(settings, POOL, 2, true, NativeTesting.factory(level));
    for (int frameNumber = 0; frameNumber < 4; frameNumber++) {
      final byte[] picture = LiveEncoderTest.scene(width, height, frameNumber + random.nextInt(3), panPerFrame);
      if (!Arrays.equals(java.encode(picture, width, height, frameNumber), other.encode(picture, width, height, frameNumber))) {
        throw new AssertionError("frame " + frameNumber + " differs at " + level);
      }
    }
    return true;
  }

  @Provide
  Arbitrary<LiveSearch> searches() {
    return Arbitraries.of(liveSearches());
  }
}

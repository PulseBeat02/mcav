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

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
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

  @Provide
  Arbitrary<@Nullable Level> levels() {
    final List<Level> levels = NativeTesting.levels();
    return levels.isEmpty() ? Arbitraries.just(null) : Arbitraries.of(levels);
  }

  @Property(seed = SEED, tries = 2000)
  boolean computesWhatJavaComputes(@ForAll("levels") final @Nullable Level level, @ForAll final long seed) {
    if (level == null) {
      return true;
    }
    final String difference = KernelDifferential.compare(
      KernelDifferential.of(new Random(seed)),
      Mcv2Internals.javaKernels(),
      NativeTesting.kernels(level)
    );
    if (difference != null) {
      throw new AssertionError(difference + " differs at " + level);
    }
    return true;
  }

  @Property(seed = SEED, tries = 30)
  boolean encodesWhatJavaEncodes(
    @ForAll("levels") final @Nullable Level level,
    @ForAll final long seed,
    @ForAll("searches") final Settings search
  ) {
    if (level == null) {
      return true;
    }
    final Random random = new Random(seed);
    final int width = 40 + random.nextInt(60);
    final int height = 30 + random.nextInt(50);
    final int panPerFrame = random.nextInt(7) - 3;
    final Settings settings = search;
    final MCV2 java = NativeTesting.encoder(settings, 2, true, NativeTesting.javaFactory());
    final MCV2 other = NativeTesting.encoder(settings, 2, true, NativeTesting.factory(level));
    for (int frameNumber = 0; frameNumber < 4; frameNumber++) {
      if (frameNumber == 3) {
        java.requestKeyframe();
        other.requestKeyframe();
      }
      final byte[] picture = Mcv2Pictures.scene(width, height, frameNumber + random.nextInt(3), panPerFrame);
      if (!Arrays.equals(java.encode(picture, width, height, frameNumber), other.encode(picture, width, height, frameNumber))) {
        throw new AssertionError("frame " + frameNumber + " differs at " + level);
      }
    }
    return true;
  }

  @Provide
  Arbitrary<Settings> searches() {
    return Arbitraries.of(Settings.DEFAULT, Settings.FAST);
  }
}

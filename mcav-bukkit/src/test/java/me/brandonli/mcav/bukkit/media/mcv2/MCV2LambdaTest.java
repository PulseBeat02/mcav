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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

/** Records the usable-cost boundary of finite lambdas admitted by the settings. */
final class MCV2LambdaTest {

  @Test
  void anAdmittedFiniteLambdaStillSelectsAKeyframe() {
    final Settings settings = Settings.DEFAULT.withLambda(Double.MAX_VALUE);
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(settings, pool, 1, true);
      final byte[] frame = assertDoesNotThrow(() -> encoder.encode(new byte[] { 10, 20, 30 }, 1, 1, 0));
      assertTrue(frame.length > 0);
    }
  }

  @Test
  void aLiveSearchRedoneForItsFrameLimitAtTheLargestLambdaStillEncodes() {
    final Settings settings = Settings.DEFAULT.withLambda(Double.MAX_VALUE);
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(settings, pool, 1, true);
      // a limit no frame meets, so every retry doubles the lambda
      encoder.setFrameLimit(1);
      final byte[] frame = assertDoesNotThrow(() -> encoder.encode(new byte[] { 10, 20, 30 }, 1, 1, 0));
      assertTrue(frame.length > 0);
    }
  }
}

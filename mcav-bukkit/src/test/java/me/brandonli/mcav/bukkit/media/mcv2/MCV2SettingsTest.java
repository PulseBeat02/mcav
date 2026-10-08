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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

final class MCV2SettingsTest {

  @Test
  void keepsTheThreeLivePresetsAsData() {
    assertEquals(new Settings(72, 120, false, false), Settings.DEFAULT);
    assertEquals(new Settings(55, 120, true, false), Settings.FAST);
    assertEquals(new Settings(72, 120, false, true), Settings.ADAPTIVE);
  }

  @Test
  void copiesWithOneValueChanged() {
    final Settings settings = new Settings(72, 1, true, true);
    assertEquals(new Settings(0, 1, true, true), settings.withLambda(0));
  }

  @Test
  void refusesInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(-1));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new Settings(1, 0, false, false));
  }

  @Test
  void codesAnAdaptiveProfileAsTheSearchOfItsMotion() {
    for (final double lambda : new double[] { 72, 137.730758207 }) {
      final MCV2 calm = new MCV2(Settings.ADAPTIVE.withLambda(lambda), ForkJoinPool.commonPool(), 2, true);
      final MCV2 normal = new MCV2(Settings.DEFAULT.withLambda(lambda), ForkJoinPool.commonPool(), 2, true);
      final MCV2 moving = new MCV2(Settings.ADAPTIVE.withLambda(lambda), ForkJoinPool.commonPool(), 2, true);
      final MCV2 switched = new MCV2(Settings.DEFAULT.withLambda(lambda), ForkJoinPool.commonPool(), 2, true);
      for (int frame = 0; frame < 6; frame++) {
        final byte[] still = new byte[96 * 64 * 3];
        assertArrayEquals(normal.encode(still, 96, 64, frame), calm.encode(still, 96, 64, frame));
        final byte[] picture = new byte[still.length];
        Arrays.fill(picture, (byte) (10 * frame));
        if (frame == 2) {
          switched.switchTo(Settings.FAST.withLambda((lambda * 55) / 72));
        }
        assertArrayEquals(switched.encode(picture, 96, 64, frame), moving.encode(picture, 96, 64, frame), "frame " + frame);
      }
    }
  }
}

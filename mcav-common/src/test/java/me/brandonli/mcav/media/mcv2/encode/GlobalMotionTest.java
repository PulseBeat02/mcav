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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The global motion estimate: phase correlation plus the exact refinement, on circularly shifted pictures. */
final class GlobalMotionTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(GlobalMotion.class);
  }

  /** A textured picture: smooth waves plus a little seeded noise, so every displacement is distinguishable. */
  private static byte[] texture(final int width, final int height) {
    final Random random = new Random(width * 31L + height);
    final byte[] rgb = new byte[width * height * 3];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int at = (y * width + x) * 3;
        final double wave = 50 * Math.sin(x / 7.0) * Math.cos(y / 5.0) + 30 * Math.sin((x - 2 * y) / 11.0);
        for (int c = 0; c < 3; c++) {
          rgb[at + c] = (byte) Math.min(255, Math.max(0, (int) (128 + wave + 20 * c + random.nextInt(24))));
        }
      }
    }
    return rgb;
  }

  /** The picture whose pixel (x, y) is the reference's (x + dx, y + dy), wrapping around. */
  private static byte[] shifted(final byte[] reference, final int width, final int height, final int dx, final int dy) {
    final byte[] rgb = new byte[reference.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int from = (Math.floorMod(y + dy, height) * width + Math.floorMod(x + dx, width)) * 3;
        System.arraycopy(reference, from, rgb, (y * width + x) * 3, 3);
      }
    }
    return rgb;
  }

  private static int estimate(final int width, final int height, final int dx, final int dy) {
    final byte[] reference = texture(width, height);
    return GlobalMotion.estimate(shifted(reference, width, height, dx, dy), reference, width, height);
  }

  @ParameterizedTest(name = "({0}, {1})")
  @CsvSource({ "0, 0", "8, -12", "-8, 12", "13, 2", "-21, -30" })
  void findsAPan(final int dx, final int dy) {
    assertEquals(((2 * dx) << 16) | ((2 * dy) & 0xFFFF), estimate(256, 160, dx, dy));
  }

  @Test
  void staysInsideTheLargestDisplacement() {
    final int vector = estimate(512, 512, 132, 132);
    final int x = vector >> 16;
    final int y = (short) vector;
    assertTrue(x <= 2 * GlobalMotion.MAX_RANGE && x >= 2 * (GlobalMotion.MAX_RANGE - 3), "x " + x);
    assertTrue(y <= 2 * GlobalMotion.MAX_RANGE && y >= 2 * (GlobalMotion.MAX_RANGE - 3), "y " + y);
  }

  @Test
  void handlesPicturesSmallerThanOneQuarterSample() {
    final byte[] picture = texture(3, 2);
    assertEquals(0, GlobalMotion.estimate(picture, picture, 3, 2));
  }
}

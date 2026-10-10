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

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

final class MCV2SettingsTest {

  @Test
  void onlyDefaultSearchesQuarterSizeMotion() throws ReflectiveOperationException {
    for (final Settings settings : new Settings[] { Settings.DEFAULT, Settings.FAST }) {
      final MCV2 encoder = new MCV2(settings, ForkJoinPool.commonPool(), 1, true);
      final Field factory = MCV2.class.getDeclaredField("kernels");
      factory.setAccessible(true);
      final Supplier<?> original = (Supplier<?>) factory.get(encoder);
      final Set<Integer> widths = new HashSet<>();
      final Class<?> contract = Mcv2Internals.nested("Kernels");
      factory.set(
        encoder,
        (Supplier<?>) () -> {
          final Object delegate = original.get();
          return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract }, (_, method, arguments) -> {
            if (method.getName().equals("seeded")) {
              widths.add((Integer) arguments[1]);
            }
            method.setAccessible(true);
            return method.invoke(delegate, arguments);
          });
        }
      );
      encoder.encode(Mcv2Pictures.scene(128, 96, 0, 3), 128, 96, 0);
      encoder.encode(Mcv2Pictures.scene(128, 96, 2, 3), 128, 96, 1);
      assertEquals(settings.fast() ? Set.of(64, 128) : Set.of(32, 64, 128), widths);
    }
  }

  @Test
  void bothPresetsTryPatternsInPredictedFrames() throws Mcv2Exception {
    final byte[] stripes = new byte[8 * 8 * 3];
    for (int pixel = 0; pixel < 8 * 8; pixel++) {
      Arrays.fill(stripes, pixel * 3, pixel * 3 + 3, (byte) ((pixel & 1) == 0 ? 16 : 240));
    }
    for (final Settings settings : new Settings[] { Settings.DEFAULT, Settings.FAST }) {
      final MCV2 encoder = new MCV2(settings, ForkJoinPool.commonPool(), 3, true);
      encoder.encode(new byte[stripes.length], 8, 8, 0);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(stripes, 8, 8, 1));
      assertEquals(false, frame.isKeyframe());
      assertEquals(1, frame.getLeafCount());
      assertEquals(Mcv2Decoder.MODE_PATTERN, frame.getLeaf(0).mode());
      assertArrayEquals(stripes, encoder.getReference());
    }
  }

  @Test
  void pinsThePresetSearchThresholds() {
    assertEquals(150, Mcv2Internals.field(MCV2.class, null, "ROOT_SPLIT_BITS"));
    assertEquals(450, Mcv2Internals.field(MCV2.class, null, "NORMAL_STEADY_SPLIT_BITS"));
    assertEquals(900, Mcv2Internals.field(MCV2.class, null, "FAST_STEADY_SPLIT_BITS"));
    assertEquals(300, Mcv2Internals.field(MCV2.class, null, "NORMAL_FINE_SPLIT_BITS"));
    assertEquals(600, Mcv2Internals.field(MCV2.class, null, "FAST_FINE_SPLIT_BITS"));
    assertEquals(28, Mcv2Internals.field(MCV2.class, null, "NORMAL_SKIP_BITS"));
    assertEquals(60, Mcv2Internals.field(MCV2.class, null, "FAST_SKIP_BITS"));
  }

  @Test
  void keepsTheTwoLivePresetsAsData() {
    assertEquals(new Settings(72, false), Settings.DEFAULT);
    assertEquals(new Settings(55, true), Settings.FAST);
  }

  @Test
  void copiesWithOneValueChanged() {
    final Settings settings = new Settings(72, true);
    assertEquals(new Settings(0, true), settings.withLambda(0));
  }

  @Test
  void refusesInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(-1));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.NaN));
  }
}

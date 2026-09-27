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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings.ReferencePolicy;
import org.junit.jupiter.api.Test;

/** The encoder profiles and their validation. */
final class EncoderSettingsTest {

  @Test
  void shipsTheRoundNineteenProfiles() {
    assertEquals(new EncoderSettings(65.255994022, 60, 24, true, true, 45.0, ReferencePolicy.PREVIOUS_FRAME), EncoderSettings.SHIP);
    assertEquals(EncoderSettings.SHIP.withLambda(137.730758207), EncoderSettings.LOW_BANDWIDTH);
  }

  @Test
  void stepsDownThePresetLadder() {
    assertEquals(EncoderSettings.SHIP.withLive(LiveSearch.LIVE), EncoderSettings.SHIP.faster());
    assertEquals(EncoderSettings.LIVE.withLive(LiveSearch.LIVE_FAST).withLambda(55), EncoderSettings.LIVE_FAST);
    assertEquals(
      EncoderSettings.LIVE.withAdaptive(new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 8, 6)),
      EncoderSettings.LIVE_ADAPTIVE
    );
    assertEquals(EncoderSettings.LIVE_ADAPTIVE, EncoderSettings.LIVE.faster());
    assertEquals(EncoderSettings.LIVE_FAST, EncoderSettings.LIVE_ADAPTIVE.faster());
    assertSame(LiveSearch.LIVE_FAST, EncoderSettings.LIVE_FAST.live());
    // the step to the live search keeps the lambda; the steps toward the live-fast search scale it to keep the quality;
    // nothing else changes
    final EncoderSettings low = EncoderSettings.LOW_BANDWIDTH.withReference(ReferencePolicy.LAST_KEYFRAME).withKeyInterval(9);
    assertEquals(low.withLive(LiveSearch.LIVE), low.faster());
    final EncoderSettings adaptive = low.withLive(LiveSearch.LIVE).faster();
    assertEquals(
      low.withLive(LiveSearch.LIVE).withAdaptive(new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, (137.730758207 * 55) / 72, 8, 6)),
      adaptive
    );
    assertEquals(low.withLive(LiveSearch.LIVE_FAST).withLambda((137.730758207 * 55) / 72), adaptive.faster());
    // the fastest search, and one off the ladder, have no rung below
    assertNull(EncoderSettings.LIVE_FAST.faster());
    assertNull(EncoderSettings.LIVE.withLive(LiveSearch.EXACT).faster());
  }

  @Test
  void codesAnAdaptiveProfilesFramesCalmOrInMotion() {
    final EncoderSettings adaptive = EncoderSettings.LIVE_ADAPTIVE;
    // calm: the profile's own search and lambda; in motion: the second search at its lambda; neither adapts again
    assertEquals(EncoderSettings.LIVE, adaptive.frame(false));
    assertEquals(EncoderSettings.LIVE_FAST, adaptive.frame(true));
    assertSame(EncoderSettings.LIVE, EncoderSettings.LIVE.frame(true));
    // the adaptive part follows the other copies, and a copy to the exhaustive search drops it
    assertEquals(new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 8, 6), adaptive.withLambda(80).withKeyInterval(9).adaptive());
    assertNull(adaptive.withLive(null).adaptive());
    assertNull(adaptive.withAdaptive(null).adaptive());
    // it needs a live search that measures the source's motion, and thresholds in order
    assertThrows(IllegalArgumentException.class, () -> EncoderSettings.SHIP.withAdaptive(adaptive.adaptive()));
    final LiveSearch unmeasured = new LiveSearch(
      8,
      26.5,
      150,
      450,
      300,
      0,
      0,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_CLASSES,
      LiveSearch.ALL_QUANTIZERS,
      true,
      16,
      true,
      0,
      0,
      false
    );
    assertThrows(IllegalArgumentException.class, () -> adaptive.withLive(unmeasured));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 6, 8));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 8, -1));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, Double.POSITIVE_INFINITY, 1));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, -1, 8, 6));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, Double.NaN, 8, 6));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, Double.POSITIVE_INFINITY, 8, 6));
    assertThrows(NullPointerException.class, () -> new EncoderSettings.Adaptive(null, 55, 8, 6));
  }

  @Test
  void copiesWithOneValueChanged() {
    final EncoderSettings settings = EncoderSettings.SHIP.withKeyInterval(1).withReference(ReferencePolicy.LAST_KEYFRAME).withLambda(0);
    assertEquals(new EncoderSettings(0, 1, 24, true, true, 45.0, ReferencePolicy.LAST_KEYFRAME), settings);
  }

  @Test
  void refusesInvalidValues() {
    final EncoderSettings ship = EncoderSettings.SHIP;
    assertThrows(IllegalArgumentException.class, () -> ship.withLambda(-1));
    assertThrows(IllegalArgumentException.class, () -> ship.withLambda(Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> ship.withLambda(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> ship.withKeyInterval(0));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings(1, 1, -1, true, true, 1, ReferencePolicy.PREVIOUS_FRAME));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings(1, 1, 64, true, true, 1, ReferencePolicy.PREVIOUS_FRAME));
    assertThrows(IllegalArgumentException.class, () -> new EncoderSettings(1, 1, 1, true, true, 0, ReferencePolicy.PREVIOUS_FRAME));
    assertThrows(IllegalArgumentException.class, () ->
      new EncoderSettings(1, 1, 1, true, true, Double.POSITIVE_INFINITY, ReferencePolicy.PREVIOUS_FRAME)
    );
    assertThrows(NullPointerException.class, () -> ship.withReference(null));
  }
}

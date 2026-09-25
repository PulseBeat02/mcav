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
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.mcv2.encode.EncoderSettings.ReferencePolicy;
import org.junit.jupiter.api.Test;

/** The encoder profiles and their validation. */
final class EncoderSettingsTest {

  @Test
  void shipsTheRoundNineteenProfiles() {
    assertEquals(new EncoderSettings(65.255994022, 60, 24, true, true, 45.0, ReferencePolicy.PREVIOUS_FRAME), EncoderSettings.SHIP);
    assertEquals(EncoderSettings.SHIP.withLambda(137.730758207), EncoderSettings.LOW_BANDWIDTH);
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

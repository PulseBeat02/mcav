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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;
import org.junit.jupiter.api.Test;

final class Mcv2ProfileTest {

  @Test
  void namesTheEncoderSettings() {
    assertEquals(EncoderSettings.SHIP, Mcv2Profile.SHIP.getSettings());
    assertEquals(EncoderSettings.LOW_BANDWIDTH, Mcv2Profile.LOW.getSettings());
    assertEquals(EncoderSettings.ReferencePolicy.LAST_KEYFRAME, Mcv2Profile.KEYFRAME.getSettings().reference());
    assertEquals(EncoderSettings.SHIP.lambda(), Mcv2Profile.KEYFRAME.getSettings().lambda());
    assertEquals(1, Mcv2Profile.INTRA.getSettings().keyInterval());
  }
}

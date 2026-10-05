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

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

final class FrameVerificationPropertyTest {

  @Property(seed = "20261003", tries = 1000)
  void matchesTheReferenceDecoderAndFindsAlteredPixelsAtEveryLevel(@ForAll final long seed) throws Mcv2Exception {
    FrameVerificationTest.compareRandom(seed);
  }
}

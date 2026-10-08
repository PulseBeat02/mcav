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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StripAnchorTest {

  @Test
  void readsItsFieldsAndTheTwelveBitStreamFromTheSymbolsAfterTheSignature() {
    final int stream = (21 << 6) | 42;
    final byte[] colours = MapFixtures.colours(MapFixtures.anchorSymbols(5, 6, 7, 8, 2, stream));
    assertEquals(new StripAnchor(5, 6, 7, 8, 2, stream, 1.5F, -2F, 3.25F), StripAnchor.of(colours, 1.5F, -2F, 3.25F));
  }
}

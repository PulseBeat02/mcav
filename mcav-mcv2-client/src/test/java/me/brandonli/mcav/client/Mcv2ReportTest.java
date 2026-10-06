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
package me.brandonli.mcav.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

final class Mcv2ReportTest {

  @Test
  void writesVersionOneAsFourBytes() {
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, new Mcv2Report(false, ShaderPack.NONE, false).toBytes());
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, new Mcv2Report(true, ShaderPack.IN_USE, false).toBytes());
    assertArrayEquals(new byte[] { 1, 1, 2, 1 }, new Mcv2Report(true, ShaderPack.UNKNOWN, true).toBytes());
  }
}

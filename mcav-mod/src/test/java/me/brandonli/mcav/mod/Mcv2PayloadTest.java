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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

final class Mcv2PayloadTest {

  @Test
  void travelsOnTheMcv2ChannelOfTheMcavNamespace() {
    assertEquals("mcav:mcv2", Mcv2Payload.TYPE.id().toString());
    assertSame(Mcv2Payload.TYPE, new Mcv2Payload(new byte[0]).type());
  }

  @Test
  void writesTheReportsBytesAsTheyAreAndReadsThemBack() {
    final ByteBuf buffer = Unpooled.buffer();
    Mcv2Payload.CODEC.encode(buffer, new Mcv2Payload(new byte[] { 1, 1, 1, 0 }));
    final byte[] written = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), written);
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, written);
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, Mcv2Payload.CODEC.decode(buffer).report());
    assertEquals(0, buffer.readableBytes());
  }

  @Test
  void keepsItsOwnCopyOfTheReport() {
    final byte[] report = { 1, 0, 0, 0 };
    final Mcv2Payload payload = new Mcv2Payload(report);
    report[1] = 1;
    payload.report()[2] = 1;
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, payload.report());
  }
}

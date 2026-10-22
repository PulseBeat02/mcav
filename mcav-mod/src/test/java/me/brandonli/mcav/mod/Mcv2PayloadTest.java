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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

final class Mcv2PayloadTest {

  @Test
  void shouldTravelOnTheMcv2ChannelOfTheMcavNamespace() {
    final String id = Mcv2Payload.TYPE.id().toString();
    final CustomPacketPayload.Type<Mcv2Payload> type = new Mcv2Payload(new byte[0]).type();
    assertEquals("mcav:mcv2", id);
    assertSame(Mcv2Payload.TYPE, type);
  }

  @Test
  void shouldWriteTheReportsBytesAsTheyAreAndReadThemBack() {
    final ByteBuf buffer = Unpooled.buffer();
    Mcv2Payload.CODEC.encode(buffer, new Mcv2Payload(new byte[] { 1, 1, 1, 0 }));
    final byte[] written = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), written);
    final byte[] decoded = Mcv2Payload.CODEC.decode(buffer).report();
    final int remaining = buffer.readableBytes();
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, written);
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, decoded);
    assertEquals(0, remaining);
  }

  @Test
  void shouldKeepItsOwnCopyOfTheReport() {
    final byte[] report = { 1, 0, 0, 0 };
    final Mcv2Payload payload = new Mcv2Payload(report);
    report[1] = 1;
    payload.report()[2] = 1;
    final byte[] kept = payload.report();
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, kept);
  }
}

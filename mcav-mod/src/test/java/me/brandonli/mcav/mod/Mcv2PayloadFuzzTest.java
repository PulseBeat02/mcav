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

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2PayloadFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldDecodeAndEncodeEveryReportAsItIs(final byte[] data) {
    final ByteBuf received = Unpooled.wrappedBuffer(data);
    final Mcv2Payload payload = Mcv2Payload.CODEC.decode(received);
    final byte[] report = payload.report();
    assertArrayEquals(data, report);

    final ByteBuf sent = Unpooled.buffer();
    Mcv2Payload.CODEC.encode(sent, payload);
    final byte[] encoded = ByteBufUtil.getBytes(sent);
    assertArrayEquals(data, encoded);
  }
}

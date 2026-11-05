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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.Arrays;
import java.util.Objects;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class TransportPageTest {

  private static final int FRAME_BYTES = 48;

  private TransportPage page(
    final long stream,
    final long frame,
    final int number,
    final int count,
    final long reference,
    final int bytes,
    final int flags,
    final int bits,
    final byte... payload
  ) {
    return new TransportPage(stream, frame, number, count, reference, bytes, flags, bits, payload);
  }

  @Test
  void shouldCompareEveryField() {
    final TransportPage value = this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5);
    EqualityAssertions.assertEqualityContract(
      value,
      this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(9, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(1, 9, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(1, 2, 9, 1, 2, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(1, 2, 0, 9, 2, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(1, 2, 0, 1, 9, FRAME_BYTES, 1, 6, (byte) 5),
      this.page(1, 2, 0, 1, 2, 49, 1, 6, (byte) 5),
      this.page(1, 2, 0, 1, 2, FRAME_BYTES, 0, 6, (byte) 5),
      this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 7, (byte) 5),
      this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 6)
    );
    final byte[] firstPayload = value.getPayload();
    final byte[] secondPayload = value.getPayload();
    assertNotSame(firstPayload, secondPayload);
  }

  @Test
  void shouldHashThePageByItsFramePageNumberAndPayload() {
    final TransportPage value = this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5);

    final int hash = value.hashCode();
    final byte[] payload = { 5 };
    final int payloadHash = Arrays.hashCode(payload);
    final int expected = Objects.hash(2L, 0, payloadHash);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeEveryHeaderFieldAndThePayloadLength() {
    final TransportPage value = this.page(1, 2, 0, 1, 2, FRAME_BYTES, 1, 6, (byte) 5);

    final String description = value.toString();
    final String expected =
      "TransportPage{streamId=1, frameId=2, number=0, count=1, referenceId=2, frameBytes=48, flags=1, symbolBits=6, payloadBytes=1}";
    assertEquals(expected, description);
  }
}

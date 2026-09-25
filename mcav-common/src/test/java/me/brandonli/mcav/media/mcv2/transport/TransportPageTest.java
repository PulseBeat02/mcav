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
package me.brandonli.mcav.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertNotSame;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/** Pages compare by every header field and every payload byte, which the assembler's duplicate rule relies on. */
final class TransportPageTest {

  private static TransportPage page(
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
  void comparesEveryField() {
    final TransportPage value = page(1, 2, 0, 1, 2, 48, 1, 6, (byte) 5);
    EqualityAssertions.assertEqualityContract(
      value,
      page(1, 2, 0, 1, 2, 48, 1, 6, (byte) 5),
      page(9, 2, 0, 1, 2, 48, 1, 6, (byte) 5),
      page(1, 9, 0, 1, 2, 48, 1, 6, (byte) 5),
      page(1, 2, 9, 1, 2, 48, 1, 6, (byte) 5),
      page(1, 2, 0, 9, 2, 48, 1, 6, (byte) 5),
      page(1, 2, 0, 1, 9, 48, 1, 6, (byte) 5),
      page(1, 2, 0, 1, 2, 49, 1, 6, (byte) 5),
      page(1, 2, 0, 1, 2, 48, 0, 6, (byte) 5),
      page(1, 2, 0, 1, 2, 48, 1, 7, (byte) 5),
      page(1, 2, 0, 1, 2, 48, 1, 6, (byte) 6)
    );
    assertNotSame(value.getPayload(), value.getPayload());
  }
}

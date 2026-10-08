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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class Mcv2CompactTest {

  @Test
  void parsesTheThreeMotionForms() throws Mcv2Exception {
    final byte[][] records = {
      { 0, 5 },
      { 0x11, (byte) 0xF9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
      { 0x22, (byte) 0x80, 127, 0, 0, 0, 0, 0, 0, 0, 0 },
    };
    final int[] motionX = { 0, -7, -128 };
    final int[] motionY = { 0, -1, 127 };
    for (int form = 0; form < records.length; form++) {
      final byte[] data = Mcv2WireFrames.block(32, 5, 7, records[form], false);
      final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(data);
      final int offset = parsed.getLeaf(0).offset();
      assertEquals(motionX[form], Mcv2Decoder.compactX(data, offset));
      assertEquals(motionY[form], Mcv2Decoder.compactY(data, offset));
      assertEquals(form, (data[offset] & 255) >> 4);
      assertEquals(records[form].length, data.length - offset);
    }
  }

  @Test
  void knowsEveryBodyLength() throws Mcv2Exception {
    final int[] expected = { 1, 10, 8 };
    for (int kind = 0; kind < expected.length; kind++) {
      for (int form = 0; form < 3; form++) {
        final byte[] record = new byte[1 + form + expected[kind]];
        record[0] = (byte) (kind | (form << 4));
        final byte[] data = Mcv2WireFrames.block(32, 5, 0, record, false);
        assertEquals(record.length, data.length - Mcv2Decoder.parse(data).getLeaf(0).offset());
        final byte[] shortFrame = Arrays.copyOf(data, data.length - 1);
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(shortFrame));
      }
    }
  }

  @Test
  void rejectsBrokenRecords() {
    for (int control = 0; control < 256; control++) {
      if ((control & 15) <= 2 && control >> 4 <= 2) {
        continue;
      }
      final byte[] record = new byte[13];
      record[0] = (byte) control;
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(32, 5, 0, record, false)));
    }
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(32, 5, 0, new byte[0], false)));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(32, 5, 0, new byte[] { 1, 0 }, false)));
  }
}

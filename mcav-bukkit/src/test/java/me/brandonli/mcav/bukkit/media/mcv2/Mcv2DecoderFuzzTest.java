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

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2DecoderFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void decodesOrRejectsAnyFrame(final byte[] data) {
    final Mcv2Decoder.Frame frame;
    try {
      frame = Mcv2Decoder.parse(data);
    } catch (final Mcv2Exception rejected) {
      return;
    }
    final int size = frame.getWidth() * frame.getHeight() * 3;
    // A nonuniform reference exposes incorrect motion or residual reads.
    final byte[] reference = new byte[size];
    for (int index = 0; index < size; index++) {
      reference[index] = (byte) (index * 37);
    }
    final byte[] picture;
    try {
      picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
    } catch (final Mcv2Exception rejected) {
      return;
    }
    assertEquals(size, picture.length);
  }
}

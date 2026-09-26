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
package me.brandonli.mcav.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the frame parser and the decoder behind it, which read the frames of a stream a server is handed or a file it
 * is pointed at: whatever the bytes, parsing either succeeds or throws {@link Mcv2Exception}, and a frame that parses
 * decodes against a reference picture of its size to a picture of its size or throws that exception - never an
 * unchecked exception, an out-of-bounds read or a hang. The seeds are the first keyframe and P frame of every committed
 * edge stream, which together use every index form and table the format has, and the frames of the syntax mcav refuses.
 */
@Tag("fuzz")
final class FrameParserFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void decodesOrRejectsAnyFrame(final byte[] data) {
    final Mcv2Frame frame;
    try {
      frame = FrameParser.parse(data);
    } catch (final Mcv2Exception rejected) {
      return;
    }
    final int size = frame.getWidth() * frame.getHeight() * 3;
    // a reference that is not flat, so motion and residuals read values that differ from pixel to pixel
    final byte[] reference = new byte[size];
    for (int i = 0; i < size; i++) {
      reference[i] = (byte) (i * 37);
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

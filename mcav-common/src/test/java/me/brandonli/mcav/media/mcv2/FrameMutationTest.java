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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every single-byte corruption of valid frames of every index form, deterministically: the parser accepts the result
 * or throws {@link Mcv2Exception}, never anything else, and an accepted frame decodes without failing, against a
 * matching reference when it is a P frame. The frames are the first keyframe and P frame of each committed edge
 * stream, which together use every index form and table the format has; every byte of the first 4 KB, where headers
 * and indexes live, is corrupted, and every 61st byte after it.
 */
final class FrameMutationTest {

  private static final int[] MASKS = { 0x01, 0x80, 0xFF };

  /** Every offset of the first bytes, where the header and index live; a sample of the payload after them. */
  private static final int DENSE = 4096;

  private static final int STRIDE = 61;

  static Stream<Arguments> frames() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final String stream : Mcv2Fixtures.digests("edge").keySet()) {
      final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/" + stream));
      arguments.add(Arguments.of(stream, frames.get(0), frames.size() > 1 ? frames.get(1) : frames.get(0)));
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("frames")
  void everyCorruptionIsAcceptedOrDeclared(final String stream, final byte[] keyframe, final byte[] next) throws Mcv2Exception {
    final Mcv2Frame reference = FrameParser.parse(keyframe);
    final byte[] picture = Mcv2Decoder.decode(reference, null, 0);
    int accepted = 0;
    int rejected = 0;
    for (final byte[] frame : List.of(keyframe, next)) {
      for (int offset = 0; offset < frame.length; offset += offset < DENSE ? 1 : STRIDE) {
        for (final int mask : MASKS) {
          final byte[] corrupted = frame.clone();
          corrupted[offset] ^= (byte) mask;
          final Mcv2Frame parsed;
          try {
            parsed = FrameParser.parse(corrupted);
          } catch (final Mcv2Exception expected) {
            rejected++;
            continue;
          }
          accepted++;
          final byte[] decoded = decodeAgainst(parsed, picture, reference.getFrameId());
          assertEquals(parsed.getWidth() * parsed.getHeight() * 3, decoded.length);
        }
      }
    }
    assertTrue(rejected > 0, stream + " rejected nothing");
    assertTrue(accepted > 0, stream + " accepted nothing");
  }

  private static byte[] decodeAgainst(final Mcv2Frame parsed, final byte[] picture, final long id) throws Mcv2Exception {
    if (parsed.isKeyframe()) {
      return Mcv2Decoder.decode(parsed, null, 0);
    }
    if (parsed.getReferenceId() != id || parsed.getWidth() * parsed.getHeight() * 3 != picture.length) {
      return new byte[parsed.getWidth() * parsed.getHeight() * 3];
    }
    return Mcv2Decoder.decode(parsed, picture, id);
  }
}

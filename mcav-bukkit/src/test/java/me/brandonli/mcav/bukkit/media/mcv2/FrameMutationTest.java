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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class FrameMutationTest {

  private static final int[] MASKS = { 0x01, 0x80, 0xFF };

  private static final String PATH_SEPARATOR = "/";

  private static final String PROXY_DEFAULT_STREAM_PATH = "conformance/proxy-default.mcs";

  private static final int DENSE = 4096;

  private static final int STRIDE = 61;

  private static Stream<Arguments> frames() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final String folder : List.of("conformance", "edge")) {
      for (final String stream : Mcv2Fixtures.readDigests(folder).keySet()) {
        final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(folder + PATH_SEPARATOR + stream));
        arguments.add(Arguments.of(stream, frames.subList(0, Math.min(2, frames.size()))));
      }
    }

    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("frames")
  void shouldAcceptOrDeclareEveryCorruption(final String stream, final List<byte[]> frames) {
    final Mcv2Decoder.Frame reference = Mcv2Decoder.parse(frames.getFirst());
    final byte[] picture = Mcv2Decoder.decode(reference, null, 0);
    final List<Boolean> acceptances = new ArrayList<>();
    for (final byte[] frame : frames) {
      this.corruptEveryOffset(frame, picture, reference.getFrameId(), acceptances);
    }

    final boolean rejectedAny = acceptances.contains(false);
    assertTrue(rejectedAny, stream + " rejected nothing");

    final boolean acceptedAny = acceptances.contains(true);
    assertTrue(acceptedAny, stream + " accepted nothing");
  }

  private void corruptEveryOffset(final byte[] frame, final byte[] picture, final long frameId, final List<Boolean> acceptances) {
    for (int offset = 0; offset < frame.length; offset += offset < DENSE ? 1 : STRIDE) {
      for (final int mask : MASKS) {
        final boolean accepted = this.isCorruptionAccepted(frame, offset, mask, picture, frameId);
        acceptances.add(accepted);
      }
    }
  }

  private boolean isCorruptionAccepted(final byte[] frame, final int offset, final int mask, final byte[] picture, final long frameId) {
    final byte[] corrupted = frame.clone();
    corrupted[offset] ^= (byte) mask;
    final Mcv2Decoder.Frame parsed;
    try {
      parsed = Mcv2Decoder.parse(corrupted);
    } catch (final Mcv2Exception exception) {
      return false;
    }

    this.decodeAgainst(parsed, picture, frameId);

    return true;
  }

  @Test
  void shouldCheckAReferenceMismatchThroughTheDecoder() {
    final List<byte[]> stream = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(PROXY_DEFAULT_STREAM_PATH));
    final Mcv2Decoder.Frame keyframe = Mcv2Decoder.parse(stream.getFirst());
    final byte[] reference = Mcv2Decoder.decode(keyframe, null, 0);
    final Mcv2Decoder.Frame interFrame = Mcv2Decoder.parse(stream.get(1));
    final boolean notKeyframe = !interFrame.isKeyframe();
    assertTrue(notKeyframe, "the fixture contains an inter frame");

    final long wrongReference = interFrame.getReferenceId() + 1;
    this.decodeAgainst(interFrame, reference, wrongReference);
  }

  private void decodeAgainst(final Mcv2Decoder.Frame parsed, final byte[] picture, final long id) {
    final int expectedLength = parsed.getWidth() * parsed.getHeight() * 3;
    if (!parsed.isKeyframe() && (parsed.getReferenceId() != id || expectedLength != picture.length)) {
      final Mcv2Exception mismatch = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(parsed, picture, id));
      final String message = mismatch.getMessage();
      assertEquals("Reference frame mismatch!", message);

      return;
    }

    final byte[] decoded = parsed.isKeyframe() ? Mcv2Decoder.decode(parsed, null, 0) : Mcv2Decoder.decode(parsed, picture, id);
    final int decodedLength = decoded.length;
    assertEquals(expectedLength, decodedLength);
  }
}

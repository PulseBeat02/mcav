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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Decoder conformance: every frame of the committed streams decodes to exactly the RGB the Python reference decoder
 * produced, compared by SHA-256.
 *
 * <p>The {@code conformance} streams are the last kept frontier round (round 19) and the two shipped 1080p30 streams;
 * streams over 1 MB are committed as a prefix of whole frames from the first keyframe. The {@code edge} streams are
 * random trees built with the reference's own serializer to reach every leaf mode, every compact class, quantizers up
 * to 7, cropped edges and every index form.
 */
final class ConformanceTest {

  static Stream<Arguments> streams() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final String folder : List.of("conformance", "edge")) {
      for (final Map.Entry<String, List<String>> entry : Mcv2Fixtures.digests(folder).entrySet()) {
        arguments.add(Arguments.of(folder, entry.getKey(), entry.getValue()));
      }
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}/{1}")
  @MethodSource("streams")
  void decodesEveryFrameBitExactly(final String folder, final String stream, final List<String> digests) throws Mcv2Exception {
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read(folder + "/" + stream));
    assertEquals(digests.size(), frames.size(), "frame count");
    final Mcv2Receiver receiver = new Mcv2Receiver();
    for (int i = 0; i < frames.size(); i++) {
      final byte[] picture = receiver.accept(frames.get(i));
      assertEquals(digests.get(i), Mcv2Fixtures.sha256(picture), "frame " + i);
    }
  }

  static Stream<Arguments> rejected() {
    final String text = new String(Mcv2Fixtures.read("edge/rejected.json"), StandardCharsets.UTF_8);
    final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
    final List<Arguments> arguments = new ArrayList<>();
    for (final Map.Entry<String, JsonElement> entry : root.entrySet()) {
      arguments.add(Arguments.of(entry.getKey(), HexFormat.of().parseHex(entry.getValue().getAsString())));
    }
    assertFalse(arguments.isEmpty());
    return arguments.stream();
  }

  /** MCV1 and the syntax of the reverted rounds 3 and 15, all accepted by the reference, are refused on purpose. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("rejected")
  void rejectsSyntaxThatIsNotPorted(final String name, final byte[] frame) {
    assertThrows(UnsupportedSyntaxException.class, () -> FrameParser.parse(frame), name);
  }
}

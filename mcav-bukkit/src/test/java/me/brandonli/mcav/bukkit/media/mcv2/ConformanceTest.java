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

final class ConformanceTest {

  private static final String PATH_SEPARATOR = "/";

  private static final String REJECTED_STREAMS_PATH = "edge/rejected.json";

  private static Stream<Arguments> streams() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final String folder : List.of("conformance", "edge")) {
      for (final Map.Entry<String, List<String>> entry : Mcv2Fixtures.readDigests(folder).entrySet()) {
        arguments.add(Arguments.of(folder, entry.getKey(), entry.getValue()));
      }
    }

    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}/{1}")
  @MethodSource("streams")
  void shouldDecodeEveryFrameBitExactly(final String folder, final String stream, final List<String> digests) {
    final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(folder + PATH_SEPARATOR + stream));
    final int expectedFrameCount = digests.size();
    final int frameCount = frames.size();
    assertEquals(expectedFrameCount, frameCount, "frame count");

    final Mcv2Receiver receiver = new Mcv2Receiver();
    for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
      final byte[] data = frames.get(frameIndex);
      final byte[] picture = receiver.accept(data);
      final String expectedDigest = digests.get(frameIndex);
      final String digest = Mcv2Fixtures.sha256(picture);
      assertEquals(expectedDigest, digest, "frame " + frameIndex);
    }
  }

  private static Stream<Arguments> rejected() {
    final String text = new String(Mcv2Fixtures.read(REJECTED_STREAMS_PATH), StandardCharsets.UTF_8);
    final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
    final List<Arguments> arguments = new ArrayList<>();
    for (final Map.Entry<String, JsonElement> entry : root.entrySet()) {
      arguments.add(Arguments.of(entry.getKey(), HexFormat.of().parseHex(entry.getValue().getAsJsonObject().get("frame").getAsString())));
    }

    final int argumentCount = arguments.size();
    assertEquals(78, argumentCount);

    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejected")
  void shouldRefuseEveryFrameOfTheRejectionCorpus(final String name, final byte[] frame) {
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame), name);
  }
}

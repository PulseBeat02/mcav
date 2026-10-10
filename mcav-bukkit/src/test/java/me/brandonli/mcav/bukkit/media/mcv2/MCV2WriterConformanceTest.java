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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class MCV2WriterConformanceTest {

  private static Stream<String> streams() {
    return Stream.of("conformance", "edge").flatMap(folder ->
      Mcv2Fixtures.digests(folder)
        .keySet()
        .stream()
        .map(name -> folder + "/" + name)
    );
  }

  @ParameterizedTest
  @MethodSource("streams")
  void rebuildsEveryCommittedFrameByteForByte(final String name) throws Mcv2Exception {
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read(name));
    final List<byte[]> canonical =
      name.startsWith("edge/") &&
      List.of(
        "edge-cropped.mcs",
        "edge-directory.mcs",
        "edge-modes.mcs",
        "edge-patterns.mcs",
        "edge-tiny.mcs",
        "edge-vertical.mcs"
      ).contains(name.substring(5))
        ? Mcv2Fixtures.frames(Mcv2Fixtures.read("writer-canonical/" + name.substring(5)))
        : frames;
    for (int index = 0; index < frames.size(); index++) {
      final byte[] data = frames.get(index);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
      assertArrayEquals(
        canonical.get(index),
        Mcv2Trees.write(
          frame.getWidth(),
          frame.getHeight(),
          frame.getFrameId(),
          frame.getReferenceId(),
          frame.isKeyframe(),
          Mcv2Trees.read(frame)
        )
      );
    }
  }
}

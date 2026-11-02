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

final class Mcv2WriterConformanceTest {

  private static final String PATH_SEPARATOR = "/";
  private static final String EDGE_FOLDER = "edge/";
  private static final String WRITER_CANONICAL_FOLDER = "writer-canonical/";

  private static final String EDGE_CROPPED_STREAM = "edge-cropped.mcs";
  private static final String EDGE_DIRECTORY_STREAM = "edge-directory.mcs";
  private static final String EDGE_MODES_STREAM = "edge-modes.mcs";
  private static final String EDGE_PATTERNS_STREAM = "edge-patterns.mcs";
  private static final String EDGE_TINY_STREAM = "edge-tiny.mcs";
  private static final String EDGE_VERTICAL_STREAM = "edge-vertical.mcs";

  private static Stream<String> streams() {
    return Stream.of("conformance", "edge").flatMap(folder ->
      Mcv2Fixtures.readDigests(folder)
        .keySet()
        .stream()
        .map(name -> folder + PATH_SEPARATOR + name)
    );
  }

  @ParameterizedTest
  @MethodSource("streams")
  void shouldRebuildEveryCommittedFrameByteForByte(final String name) {
    final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(name));
    final List<byte[]> canonical =
      name.startsWith(EDGE_FOLDER) &&
      List.of(
        EDGE_CROPPED_STREAM,
        EDGE_DIRECTORY_STREAM,
        EDGE_MODES_STREAM,
        EDGE_PATTERNS_STREAM,
        EDGE_TINY_STREAM,
        EDGE_VERTICAL_STREAM
      ).contains(name.substring(5))
        ? Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(WRITER_CANONICAL_FOLDER + name.substring(5)))
        : frames;
    for (int index = 0; index < frames.size(); index++) {
      final byte[] data = frames.get(index);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
      final byte[] expected = canonical.get(index);
      final Mcv2FrameKind kind = frame.isKeyframe() ? Mcv2FrameKind.KEYFRAME : Mcv2FrameKind.PREDICTED;
      final byte[] rebuilt = Mcv2Trees.write(
        frame.getWidth(),
        frame.getHeight(),
        frame.getFrameId(),
        frame.getReferenceId(),
        kind,
        Mcv2Trees.read(frame)
      );
      assertArrayEquals(expected, rebuilt);
    }
  }
}

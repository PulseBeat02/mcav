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
package me.brandonli.mcav.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Serializer conformance: rebuilding every committed frame's block tree and serializing it with the options it was
 * written with reproduces the frame byte for byte, so the Java serializer and the reference's {@code pack_frame} agree
 * on every index form, table and tie-break.
 */
final class FrameWriterConformanceTest {

  private static final FrameWriter.Options DERIVED = new FrameWriter.Options(true, true, true, true, true, true, true, true, true, false);

  private static final FrameWriter.Options DERIVED_565 = new FrameWriter.Options(
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true
  );

  private static final Map<String, FrameWriter.Options> EDGE_OPTIONS = Map.ofEntries(
    Map.entry("edge-derived-tables.mcs", DERIVED),
    Map.entry("edge-derived-565.mcs", DERIVED_565),
    Map.entry("edge-derived-patterns.mcs", DERIVED_565),
    Map.entry("edge-derived-plain.mcs", new FrameWriter.Options(false, false, false, true, true, false, false, false, false, false)),
    Map.entry("edge-stored-short.mcs", new FrameWriter.Options(true, true, true, true, false, false, false, false, false, false)),
    Map.entry("edge-stored-short-sparse.mcs", new FrameWriter.Options(true, true, true, true, false, false, false, false, false, false)),
    Map.entry("edge-stored-short-groups.mcs", new FrameWriter.Options(true, false, true, false, false, false, false, false, false, false)),
    Map.entry("edge-stored-short-dense.mcs", new FrameWriter.Options(true, false, false, false, false, false, false, false, false, false)),
    Map.entry("edge-stored-wide.mcs", new FrameWriter.Options(false, false, true, false, false, false, false, false, false, false)),
    Map.entry("edge-stored-wide-sparse.mcs", new FrameWriter.Options(false, false, true, false, false, false, false, false, false, false)),
    Map.entry(
      "edge-stored-wide-directory.mcs",
      new FrameWriter.Options(false, false, false, true, false, false, false, false, false, false)
    ),
    Map.entry(
      "edge-stored-wide-directory-sparse.mcs",
      new FrameWriter.Options(false, false, false, true, false, false, false, false, false, false)
    ),
    Map.entry("edge-tiny.mcs", DERIVED),
    Map.entry("edge-wide-fallback.mcs", DERIVED)
  );

  static Stream<Arguments> streams() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final String stream : Mcv2Fixtures.digests("conformance").keySet()) {
      arguments.add(Arguments.of("conformance/" + stream, null));
    }
    for (final Map.Entry<String, FrameWriter.Options> entry : EDGE_OPTIONS.entrySet()) {
      arguments.add(Arguments.of("edge/" + entry.getKey(), entry.getValue()));
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("streams")
  void rewritesEveryFrameByteForByte(final String stream, final FrameWriter.Options edgeOptions) throws Mcv2Exception {
    for (final byte[] frame : Mcv2Fixtures.frames(Mcv2Fixtures.read(stream))) {
      final Mcv2Frame parsed = FrameParser.parse(frame);
      final List<TreeNode> roots = new ArrayList<>();
      for (final TreeNode root : TreeReader.roots(parsed)) {
        // the reader expands patterns into the palettes they stand for; rewriting them restores the stored leaves
        roots.add(TreeReader.withPatterns(root, Mcv2Format.ROOT_SIZE));
      }
      final FrameWriter.Options options = edgeOptions == null
        ? FrameWriter.Options.production((parsed.getFlags() & Mcv2Format.ENDPOINT_565) != 0)
        : edgeOptions;
      final byte[] written = FrameWriter.write(
        parsed.getWidth(),
        parsed.getHeight(),
        parsed.getFrameId(),
        parsed.getReferenceId(),
        parsed.isKeyframe(),
        parsed.getGlobalX(),
        parsed.getGlobalY(),
        roots,
        options
      );
      assertArrayEquals(frame, written, stream + " frame " + parsed.getFrameId());
    }
  }
}

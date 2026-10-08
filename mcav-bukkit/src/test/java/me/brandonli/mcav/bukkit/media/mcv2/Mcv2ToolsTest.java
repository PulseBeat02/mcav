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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Natives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2ToolsTest {

  @TempDir
  Path folder;

  @AfterEach
  void restoreNatives() {
    MCV2.installNatives(NativeTesting.FOLDER, MCV2.NATIVE_AUTO);
  }

  @Test
  void benchmarkPreservesGoldenBytesAndReportsTheKernels() throws Exception {
    final Path source = folder.resolve("source.rgb");
    Files.write(source, Mcv2Fixtures.read("encoder/crop-320x180x4.rgb"));
    for (final String mode : List.of("off", "auto")) {
      final Path archive = folder.resolve(mode + ".mcs");
      final String output = run(
        "bench",
        "source=" + source,
        "width=320",
        "height=180",
        "frames=4",
        "warm=1",
        "threads=1",
        "verify=true",
        mode.equals("off") ? "natives=off" : "profile=DEFAULT",
        "out=" + archive
      );
      assertArrayEquals(Mcv2Fixtures.read("encoder/crop-default.mcs"), Files.readAllBytes(archive));
      final String[] lines = output.strip().split("\\R");
      final JsonObject result = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
      assertEquals(4, result.get("frames").getAsInt());
      assertEquals(3, result.get("warm").getAsInt());
      assertEquals(1, result.get("keyframes").getAsInt());
      assertEquals(MCV2.describeNatives(), result.get("natives").getAsString());
      if (mode.equals("off") && System.getProperty(MCV2.NATIVE_PROPERTY) == null) {
        assertTrue(result.get("natives").getAsString().startsWith("Java,"));
      }
      if (mode.equals("auto") && NativeTesting.expected() && System.getProperty(MCV2.NATIVE_PROPERTY) == null) {
        assertTrue(result.get("natives").getAsString().startsWith("native "));
        assertTrue(Natives.resolved().binding() != null);
      }
      for (final String field : List.of(
        "mean_ms",
        "p50_ms",
        "p95_ms",
        "max_ms",
        "cpu_ms",
        "alloc_mb_per_frame",
        "logical_mbps",
        "map_mbps",
        "zlib_mbps",
        "psnr_mean",
        "psnr_global",
        "fps",
        "threads",
        "processors",
        "encoder_heap_mb"
      )) {
        assertTrue(result.has(field), field);
      }
    }
  }

  @Test
  void digestsRetainExactTokensAndRgbOutput() throws Exception {
    final Path archive = folder.resolve("stream.mcs");
    final Path pictures = folder.resolve("pictures.rgb");
    Files.write(archive, Mcv2Fixtures.read("conformance/proxy-default.mcs"));
    final List<String> expected = Mcv2Fixtures.digests("conformance").get("proxy-default.mcs");
    assertEquals(archive + " " + String.join(" ", expected), run("digests", "--rgb", pictures.toString(), archive.toString()).strip());
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final ByteArrayOutputStream decoded = new ByteArrayOutputStream();
    for (final byte[] frame : Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/proxy-default.mcs"))) {
      decoded.writeBytes(receiver.accept(frame));
    }
    assertArrayEquals(decoded.toByteArray(), Files.readAllBytes(pictures));
  }

  @Test
  void rejectsUnknownCommandsAndNativeModes() {
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[0]));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[] { "unknown" }));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[] { "bench", "natives=unknown" }));
  }

  private static String run(final String... arguments) throws Exception {
    final PrintStream original = System.out;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (final PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      Mcv2Tools.main(arguments);
    } finally {
      System.setOut(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }
}

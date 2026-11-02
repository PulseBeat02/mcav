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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Natives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2ToolsTest {

  private static final String BENCH_COMMAND = "bench";
  private static final String DIGESTS_COMMAND = "digests";

  private static final String CROP_SOURCE_PATH = "encoder/crop-320x180x4.rgb";
  private static final String CROP_DEFAULT_PATH = "encoder/crop-default.mcs";
  private static final String PROXY_DEFAULT_PATH = "conformance/proxy-default.mcs";
  private static final String PROXY_DEFAULT_STREAM = "proxy-default.mcs";
  private static final String POST_SHADER_FOLDER = "assets/mcav/shaders/post";

  private static final String ARCHIVE_EXTENSION = ".mcs";
  private static final String STREAM_FILE_NAME = "stream.mcs";
  private static final String REJECTED_FILE_NAME = "rejected.mcs";
  private static final String CLASSPATH_FILE_NAME = "mcv2-tools-classpath.txt";

  @TempDir
  Path folder;

  @AfterEach
  void restoreNatives() {
    Mcv2.installNatives(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_AUTO);
  }

  @Test
  void shouldWriteTheGoldenStreamAndReportTheKernelsOnBenchmark() throws Exception {
    final Path source = folder.resolve("source.rgb");
    Files.write(source, Mcv2Fixtures.read(CROP_SOURCE_PATH));
    for (final String mode : List.of("off", "auto")) {
      final Path archive = folder.resolve(mode + ARCHIVE_EXTENSION);
      final String output = this.run(
        BENCH_COMMAND,
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
      final byte[] expectedArchive = Mcv2Fixtures.read(CROP_DEFAULT_PATH);
      final byte[] archiveBytes = Files.readAllBytes(archive);
      assertArrayEquals(expectedArchive, archiveBytes);

      final List<String> lines = output.lines().toList();
      final JsonObject result = JsonParser.parseString(lines.getLast()).getAsJsonObject();
      final boolean escaped = lines.getLast().contains("\\u003d");
      assertFalse(escaped);
      final int frames = result.get("frames").getAsInt();
      assertEquals(4, frames);
      final int warm = result.get("warm").getAsInt();
      assertEquals(3, warm);
      final int keyframes = result.get("keyframes").getAsInt();
      assertEquals(1, keyframes);
      final String expectedNatives = Mcv2.describeNatives();
      final String natives = result.get("natives").getAsString();
      assertEquals(expectedNatives, natives);
      if (mode.equals("off") && System.getProperty(Mcv2.NATIVE_PROPERTY) == null) {
        final boolean describesJava = result.get("natives").getAsString().startsWith("Java,");
        assertTrue(describesJava);
      }
      if (mode.equals("auto") && NativeTesting.areNativesExpected() && System.getProperty(Mcv2.NATIVE_PROPERTY) == null) {
        final boolean describesNative = result.get("natives").getAsString().startsWith("native ");
        assertTrue(describesNative);

        final boolean bound = Natives.resolved().binding() != null;
        assertTrue(bound);
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
        final boolean present = result.has(field);
        assertTrue(present, field);
      }
    }
  }

  @Test
  void shouldPrintTheReferenceDigestsAndWriteTheDecodedPicturesOnDigests() throws Exception {
    final Path archive = folder.resolve(STREAM_FILE_NAME);
    final Path pictures = folder.resolve("pictures.rgb");
    Files.write(archive, Mcv2Fixtures.read(PROXY_DEFAULT_PATH));
    final List<String> expected = Mcv2Fixtures.readDigests("conformance").get(PROXY_DEFAULT_STREAM);
    final String expectedOutput = archive + " " + String.join(" ", expected);
    final String output = this.run(DIGESTS_COMMAND, "--rgb", pictures.toString(), archive.toString()).strip();
    assertEquals(expectedOutput, output);

    final Mcv2Receiver receiver = new Mcv2Receiver();
    final ByteArrayOutputStream decoded = new ByteArrayOutputStream();
    for (final byte[] frame : Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(PROXY_DEFAULT_PATH))) {
      decoded.writeBytes(receiver.accept(frame));
    }

    final byte[] expectedPictures = decoded.toByteArray();
    final byte[] writtenPictures = Files.readAllBytes(pictures);
    assertArrayEquals(expectedPictures, writtenPictures);
  }

  @Test
  void shouldRejectUnknownCommandsAndNativeModes() {
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[0]));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[] { "unknown" }));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Tools.main(new String[] { BENCH_COMMAND, "natives=unknown" }));
  }

  @Test
  void shouldDistinguishRejectedFramesFromTruncatedArchivesOnDigests() throws Exception {
    final Path archive = folder.resolve(REJECTED_FILE_NAME);
    final byte[] frame = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(PROXY_DEFAULT_PATH)).getFirst();
    final byte[] stream = new byte[Integer.BYTES + frame.length];
    Mcv2Decoder.putUnsignedInt(stream, 0, frame.length);
    System.arraycopy(frame, 0, stream, Integer.BYTES, frame.length);
    stream[Integer.BYTES] = 0;
    Files.write(archive, stream);
    final String expectedRejection = archive + " reject";
    final String rejection = this.run(DIGESTS_COMMAND, archive.toString()).strip();
    assertEquals(expectedRejection, rejection);

    for (final byte[] truncated : List.of(new byte[] { 1 }, Arrays.copyOf(stream, stream.length - 1))) {
      Files.write(archive, truncated);
      final String expectedTruncation = archive + " truncated";
      final String truncation = this.run(DIGESTS_COMMAND, archive.toString()).strip();
      assertEquals(expectedTruncation, truncation);
    }
  }

  @Test
  void shouldPreserveSamplerNamesAndReturnTheFailedStageCountInTheShaderCompiler() throws Exception {
    final Path pack = folder.resolve("pack");
    final Path post = Files.createDirectories(pack.resolve(POST_SHADER_FOLDER));
    final Path generated = Files.createDirectories(folder.resolve("generated"));
    final Path output = folder.resolve("compiled");
    Files.writeString(
      post.resolve("sampler.fsh"),
      """
      #version 330
      layout(location = 0) out vec4 color;
      uniform sampler2D Sampler0;
      void main() { color = texture(Sampler0, vec2(0.5)); }
      """
    );
    final String[] arguments = { pack.toString(), generated.toString(), output.toString(), "--post-only" };
    final int validFailures = this.compileShaders(arguments);
    assertEquals(0, validFailures);

    final String glsl = Files.readString(output.resolve("sampler.fsh"));
    final boolean keepsSampler = glsl.contains("uniform sampler2D Sampler0;");
    assertTrue(keepsSampler);

    final boolean renamesSampler = glsl.contains("_uniform_00_03");
    assertFalse(renamesSampler);

    Files.writeString(post.resolve("invalid.fsh"), "#version 330\nvoid main() { invalid shader; }\n");
    final int invalidFailures = this.compileShaders(arguments);
    assertEquals(1, invalidFailures);

    final boolean invalidWritten = Files.exists(output.resolve("invalid.fsh"));
    assertFalse(invalidWritten);

    final String samplerAfterFailure = Files.readString(output.resolve("sampler.fsh"));
    assertEquals(glsl, samplerAfterFailure);
  }

  private int compileShaders(final String[] arguments) throws Exception {
    // JaCoCo's synthetic methods on LWJGL interfaces confuse its FFM callback selection.
    final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    final Path java = Path.of(System.getProperty("java.home"), "bin", executable);
    final String classpath = Files.readString(Path.of("build", CLASSPATH_FILE_NAME));
    final List<String> command = new ArrayList<>(
      List.of(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classpath, Mcv2Tools.class.getName(), "shader-compile")
    );
    command.addAll(Arrays.asList(arguments));
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    final String output;
    try (final var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    System.out.print(output);
    final int failures = process.waitFor();
    final String summary = failures == 0 ? "every stage compiled" : failures + " stages failed";
    final boolean summarized = output.lines().anyMatch(summary::equals);
    assertTrue(summarized, output);

    return failures;
  }

  private String run(final String... arguments) throws Exception {
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

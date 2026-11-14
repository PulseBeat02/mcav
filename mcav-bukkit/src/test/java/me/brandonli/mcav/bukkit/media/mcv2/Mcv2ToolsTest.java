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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Natives;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2ToolsTest {

  private static final String BENCH_COMMAND = "bench";
  private static final String DIGESTS_COMMAND = "digests";
  private static final String POST_ONLY_OPTION = "--post-only";

  private static final String CROP_SOURCE_PATH = "encoder/crop-320x180x4.rgb";
  private static final String CROP_DEFAULT_PATH = "encoder/crop-default.mcs";
  private static final String PROXY_DEFAULT_PATH = "conformance/proxy-default.mcs";
  private static final String PROXY_DEFAULT_STREAM = "proxy-default.mcs";
  private static final String POST_SHADER_FOLDER = "assets/mcav/shaders/post";

  private static final String ARCHIVE_EXTENSION = ".mcs";
  private static final String STREAM_FILE_NAME = "stream.mcs";
  private static final String REJECTED_FILE_NAME = "rejected.mcs";
  private static final String CLASSPATH_FILE_NAME = "mcv2-tools-classpath.txt";
  private static final String PACK_ARCHIVE_FILE_NAME = "pack.zip";

  private static final String PACK_DIRECTORY = "pack";
  private static final String GENERATED_DIRECTORY = "generated";
  private static final String COMPILED_DIRECTORY = "compiled";

  private static final String SAMPLER_SHADER_FILE_NAME = "sampler.fsh";
  private static final String INVALID_SHADER_FILE_NAME = "invalid.fsh";

  private static final String MCV2_RESOURCE_DIRECTORY = "src/main/resources/mcav/mcv2";
  private static final String CHAIN_FILE_NAME = "chain.json";

  private static final String MCAV_POST_PREFIX = "mcav:post/";
  private static final String SCREEN_FOLDER = "s@S@/";

  private static final String BUILT_CHAIN_PATH = "assets/minecraft/post_effect/entity_outline.json";
  private static final String PASSES_KEY = "passes";
  private static final String INPUTS_KEY = "inputs";
  private static final String SAMPLER_NAME_KEY = "sampler_name";
  private static final String UNIFORMS_KEY = "uniforms";
  private static final String VERTEX_SHADER_KEY = "vertex_shader";
  private static final String FRAGMENT_SHADER_KEY = "fragment_shader";

  private static final String SAMPLER_SUFFIX = "Sampler";
  private static final String CHAIN_PATH_SEPARATOR = "/";
  private static final String COMPILED_PATH_SEPARATOR = "_";

  private static final Pattern SAMPLER_DECLARATION = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;");
  private static final Pattern UNIFORM_BLOCK_DECLARATION = Pattern.compile("uniform\\s+(\\w+)\\s*(\\{|$)", Pattern.MULTILINE);
  private static final int DECLARED_NAME_GROUP = 1;

  private static final Map<String, String> STAGE_EXTENSIONS = Map.of(VERTEX_SHADER_KEY, ".vsh", FRAGMENT_SHADER_KEY, ".fsh");

  private static final String WINDOWS_JAVA_EXECUTABLE_FILE_NAME = "java.exe";
  private static final String BINARY_DIRECTORY = "bin";
  private static final String BUILD_DIRECTORY = "build";

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
        "source=%s".formatted(source),
        "width=320",
        "height=180",
        "frames=4",
        "warm=1",
        "threads=1",
        "verify=true",
        mode.equals("off") ? "natives=off" : "profile=DEFAULT",
        "out=%s".formatted(archive)
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
    final String expectedOutput = "%s %s".formatted(archive, String.join(" ", expected));
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
    final String expectedRejection = "%s reject".formatted(archive);
    final String rejection = this.run(DIGESTS_COMMAND, archive.toString()).strip();
    assertEquals(expectedRejection, rejection);

    for (final byte[] truncated : List.of(new byte[] { 1 }, Arrays.copyOf(stream, stream.length - 1))) {
      Files.write(archive, truncated);
      final String expectedTruncation = "%s truncated".formatted(archive);
      final String truncation = this.run(DIGESTS_COMMAND, archive.toString()).strip();
      assertEquals(expectedTruncation, truncation);
    }
  }

  @Test
  void shouldPreserveSamplerNamesAndReturnTheFailedStageCountInTheShaderCompiler() throws Exception {
    final Path pack = folder.resolve(PACK_DIRECTORY);
    final Path post = Files.createDirectories(pack.resolve(POST_SHADER_FOLDER));
    final Path generated = Files.createDirectories(folder.resolve(GENERATED_DIRECTORY));
    final Path output = folder.resolve(COMPILED_DIRECTORY);
    Files.writeString(
      post.resolve(SAMPLER_SHADER_FILE_NAME),
      """
      #version 330
      layout(location = 0) out vec4 color;
      uniform sampler2D Sampler0;
      void main() { color = texture(Sampler0, vec2(0.5)); }
      """
    );
    final String[] arguments = { pack.toString(), generated.toString(), output.toString(), POST_ONLY_OPTION };
    final int validFailures = this.compileShaders(arguments);
    assertEquals(0, validFailures);

    final String glsl = Files.readString(output.resolve(SAMPLER_SHADER_FILE_NAME));
    final boolean keepsSampler = glsl.contains("uniform sampler2D Sampler0;");
    assertTrue(keepsSampler);

    final boolean renamesSampler = glsl.contains("_uniform_00_03");
    assertFalse(renamesSampler);

    Files.writeString(post.resolve(INVALID_SHADER_FILE_NAME), "#version 330\nvoid main() { invalid shader; }\n");
    final int invalidFailures = this.compileShaders(arguments);
    assertEquals(1, invalidFailures);

    final boolean invalidWritten = Files.exists(output.resolve(INVALID_SHADER_FILE_NAME));
    assertFalse(invalidWritten);

    final String samplerAfterFailure = Files.readString(output.resolve(SAMPLER_SHADER_FILE_NAME));
    assertEquals(glsl, samplerAfterFailure);
  }

  @Test
  void shouldShipExactlyThePostShadersTheChainNames() throws Exception {
    final Set<String> named = this.findChainShaderFiles();
    final Set<String> shipped = this.listPostShaderFiles();
    assertEquals(shipped, named);
  }

  private Set<String> findChainShaderFiles() throws IOException {
    final String chain = Files.readString(Path.of(MCV2_RESOURCE_DIRECTORY, CHAIN_FILE_NAME));
    final JsonElement root = JsonParser.parseString(chain);
    final Set<String> files = new HashSet<>();
    this.collectShaderFiles(root, files);
    return files;
  }

  private void collectShaderFiles(final JsonElement element, final Set<String> files) {
    if (element.isJsonArray()) {
      final JsonArray array = element.getAsJsonArray();
      array.forEach(child -> this.collectShaderFiles(child, files));
      return;
    }

    if (!element.isJsonObject()) {
      return;
    }

    final JsonObject object = element.getAsJsonObject();
    for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
      final String key = entry.getKey();
      final JsonElement value = entry.getValue();
      this.addShaderFile(key, value, files);
      this.collectShaderFiles(value, files);
    }
  }

  private void addShaderFile(final String key, final JsonElement value, final Set<String> files) {
    final String extension = STAGE_EXTENSIONS.getOrDefault(key, "");
    if (extension.isEmpty()) {
      return;
    }

    final String name = value.getAsString();
    if (!name.startsWith(MCAV_POST_PREFIX)) {
      return;
    }

    final String path = name.substring(MCAV_POST_PREFIX.length());
    final String file = path.replace(SCREEN_FOLDER, "");
    files.add("%s%s".formatted(file, extension));
  }

  private Set<String> listPostShaderFiles() throws IOException {
    final Path post = Path.of(MCV2_RESOURCE_DIRECTORY, PACK_DIRECTORY, POST_SHADER_FOLDER);
    try (final Stream<Path> files = Files.list(post)) {
      final List<String> names = files.map(Path::getFileName).map(Path::toString).toList();
      return Set.copyOf(names);
    }
  }

  @Test
  void shouldCompileEveryPostShaderOfABuiltPack() throws Exception {
    final int failures = this.compileBuiltPack();
    assertEquals(0, failures);
  }

  private int compileBuiltPack() throws Exception {
    final Mcv2Configuration configuration = Mcv2ConfigurationTest.complete().build();
    final Path archive = this.folder.resolve(PACK_ARCHIVE_FILE_NAME);
    Mcv2Pack.write(configuration, Mcv2Pack.DebugView.SHOWN, archive);

    final Path pack = this.folder.resolve(PACK_DIRECTORY);
    this.extract(archive, pack);

    final Path generated = Files.createDirectories(this.folder.resolve(GENERATED_DIRECTORY));
    final Path compiled = this.folder.resolve(COMPILED_DIRECTORY);
    final String[] arguments = { pack.toString(), generated.toString(), compiled.toString(), POST_ONLY_OPTION };
    return this.compileShaders(arguments);
  }

  private void extract(final Path archive, final Path target) throws IOException {
    try (final InputStream input = Files.newInputStream(archive); final ZipInputStream stream = new ZipInputStream(input)) {
      @Nullable ZipEntry entry;
      while ((entry = stream.getNextEntry()) != null) {
        this.extractEntry(stream, entry, target);
      }
    }
  }

  private void extractEntry(final ZipInputStream stream, final ZipEntry entry, final Path target) throws IOException {
    if (entry.isDirectory()) {
      return;
    }

    final Path file = target.resolve(entry.getName());
    final Path parent = requireNonNull(file.getParent(), "A pack entry must have a directory!");
    Files.createDirectories(parent);
    Files.copy(stream, file);
  }

  @Test
  void shouldBindExactlyTheSamplersAndUniformBlocksTheShadersOfEachPassOfABuiltPackDeclare() throws Exception {
    final int failures = this.compileBuiltPack();
    assertEquals(0, failures);

    final List<JsonObject> passes = this.findMcavPasses();
    for (final JsonObject pass : passes) {
      final Set<String> bound = this.findBoundNames(pass);
      final Set<String> declared = this.findDeclaredNames(pass);
      assertEquals(bound, declared);
    }
  }

  private List<JsonObject> findMcavPasses() throws IOException {
    final Path chainFile = this.folder.resolve(PACK_DIRECTORY).resolve(BUILT_CHAIN_PATH);
    final String text = Files.readString(chainFile);
    final JsonElement root = JsonParser.parseString(text);
    final JsonObject chain = root.getAsJsonObject();
    final JsonArray passes = chain.getAsJsonArray(PASSES_KEY);
    return passes.asList().stream().map(JsonElement::getAsJsonObject).filter(this::isMcavPass).toList();
  }

  private boolean isMcavPass(final JsonObject pass) {
    final JsonElement fragment = pass.get(FRAGMENT_SHADER_KEY);
    final String name = fragment.getAsString();
    return name.startsWith(MCAV_POST_PREFIX);
  }

  private Set<String> findBoundNames(final JsonObject pass) {
    final Set<String> names = new HashSet<>();
    final JsonArray inputs = pass.getAsJsonArray(INPUTS_KEY);
    for (final JsonElement input : inputs) {
      final JsonObject binding = input.getAsJsonObject();
      final JsonElement samplerName = binding.get(SAMPLER_NAME_KEY);
      names.add(samplerName.getAsString() + SAMPLER_SUFFIX);
    }

    if (!pass.has(UNIFORMS_KEY)) {
      return names;
    }

    final JsonObject uniforms = pass.getAsJsonObject(UNIFORMS_KEY);
    names.addAll(uniforms.keySet());

    return names;
  }

  private Set<String> findDeclaredNames(final JsonObject pass) throws IOException {
    final Set<String> names = new HashSet<>();
    for (final Map.Entry<String, String> stage : STAGE_EXTENSIONS.entrySet()) {
      final JsonElement shader = pass.get(stage.getKey());
      final String name = shader.getAsString();
      if (!name.startsWith(MCAV_POST_PREFIX)) {
        continue;
      }

      final String glsl = this.readCompiledStage(name, stage.getValue());
      names.addAll(this.findDeclarations(SAMPLER_DECLARATION, glsl));
      names.addAll(this.findDeclarations(UNIFORM_BLOCK_DECLARATION, glsl));
    }

    return names;
  }

  private String readCompiledStage(final String name, final String extension) throws IOException {
    final String path = name.substring(MCAV_POST_PREFIX.length());
    final String file = path.replace(CHAIN_PATH_SEPARATOR, COMPILED_PATH_SEPARATOR);
    final Path compiled = this.folder.resolve(COMPILED_DIRECTORY).resolve(file + extension);
    return Files.readString(compiled);
  }

  private List<String> findDeclarations(final Pattern pattern, final String glsl) {
    final Matcher matcher = pattern.matcher(glsl);
    return matcher
      .results()
      .map(result -> result.group(DECLARED_NAME_GROUP))
      .toList();
  }

  private int compileShaders(final String[] arguments) throws Exception {
    // JaCoCo's synthetic methods on LWJGL interfaces confuse its FFM callback selection.
    final String executable = System.getProperty("os.name").startsWith("Windows") ? WINDOWS_JAVA_EXECUTABLE_FILE_NAME : "java";
    final Path java = Path.of(System.getProperty("java.home"), BINARY_DIRECTORY, executable);
    final String classpath = Files.readString(Path.of(BUILD_DIRECTORY, CLASSPATH_FILE_NAME));
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
    final String summary = failures == 0 ? "every stage compiled" : "%s stages failed".formatted(failures);
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

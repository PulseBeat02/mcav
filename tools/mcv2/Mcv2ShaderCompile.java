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

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spv.SpvDecorationLocation;
import static org.lwjgl.util.spvc.Spvc.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * Compiles the MCV2 resource pack's shaders the way Minecraft 26.3's OpenGL backend does, so a pack change can be
 * checked without starting the game: GLSL through shaderc into SPIR-V for Vulkan 1.2 (uniforms bound automatically,
 * {@code #include <namespace:path>} read from {@code assets/<namespace>/shaders/include/<path>}, the renderer's
 * macros defined), then SPIR-V through SPIRV-Cross back into GLSL 330 with the options and interface names the game
 * uses outside its shader debug mode. Every stage of every pipeline the pack takes part in is compiled: the text
 * shaders with each define set the game uses for text, and the post passes of {@code entity_outline.json}.
 *
 * <p>One difference is left in on purpose: the game renames every sampler and uniform block after its descriptor set
 * and binding ({@code _uniform_00_03}); here they keep their GLSL names, so shader_check.py can bind its textures by
 * name. A name is all that differs.
 *
 * <p>Run with a JDK, the LWJGL 3.4.3 jars the 26.3 client uses (lwjgl, lwjgl-shaderc, lwjgl-spvc and their
 * natives) on the class path:
 * {@code java -cp <jars> tools/mcv2/Mcv2ShaderCompile.java <pack folder> <generated includes folder> <output folder>
 * [--vanilla <extracted 26.3 client jar>] [--post-only]}. The generated includes (mcv2_config, mcv2_alphabet,
 * mcv2_screen) are read from the second folder, as Mcv2Pack writes them; the text shaders include Minecraft's own
 * includes, read from the extracted client jar. Each stage's GLSL is written to the output folder; the exit code is
 * the number of stages that failed.
 */
public final class Mcv2ShaderCompile {

  private static final int VULKAN_1_2 = (1 << 22) | (2 << 12);

  // the macros GlslCompiler defines for every shader on a device with a zero-to-one depth range; it also defines
  // RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE on devices with shader draw parameters, which no shader reads
  private static final Map<String, String> RENDERER_MACROS = Map.of("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");

  // what GlslCompiler defines on drivers that need depth invariance spelled out; only the transparency includes read it
  private static final String EXPLICIT_DEPTH_INVARIANCE = "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE";

  // RenderPipelines' OIT snippet: a wavelet of rank 2, LevelRenderer.OIT_COEFFICIENT_COUNT = 2^3 coefficients, a
  // quarter of them per transmittance target
  private static final Map<String, String> OIT = Map.of("OIT", "", "OIT_WAVELET_RANK", "2", "OIT_COEFF_COUNT", "8", "OIT_COEFF_ATTACHMENT_COUNT", "2");

  // the define sets of the text pipelines of RenderPipelines: world text, grayscale, see-through, GUI, and the three
  // stages of improved transparency, those also as drivers that need explicit depth invariance compile them
  private static final Map<String, Map<String, String>> TEXT_VARIANTS = new LinkedHashMap<>();

  static {
    TEXT_VARIANTS.put("text", Map.of());
    TEXT_VARIANTS.put("text_grayscale", Map.of("IS_GRAYSCALE", "1"));
    TEXT_VARIANTS.put("text_see_through", Map.of("IS_SEE_THROUGH", "1"));
    TEXT_VARIANTS.put("text_grayscale_see_through", Map.of("IS_GRAYSCALE", "1", "IS_SEE_THROUGH", "1"));
    TEXT_VARIANTS.put("gui_text", Map.of("IS_GUI", "1"));
    TEXT_VARIANTS.put("oit_text_depth_bounds", with(OIT, "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS"));
    TEXT_VARIANTS.put("oit_text_transmittance", with(OIT, "OIT_ALPHA_ONLY", "OIT_TRANSMITTANCE"));
    TEXT_VARIANTS.put("oit_text_accumulate", with(OIT, "OIT_ACCUMULATE"));
    TEXT_VARIANTS.put("oit_text_depth_bounds_invariant", with(OIT, "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS", EXPLICIT_DEPTH_INVARIANCE));
    TEXT_VARIANTS.put("oit_text_transmittance_invariant", with(OIT, "OIT_ALPHA_ONLY", "OIT_TRANSMITTANCE", EXPLICIT_DEPTH_INVARIANCE));
    TEXT_VARIANTS.put("oit_text_accumulate_invariant", with(OIT, "OIT_ACCUMULATE", EXPLICIT_DEPTH_INVARIANCE));
  }

  private static Map<String, String> with(final Map<String, String> base, final String... flags) {
    final Map<String, String> defines = new LinkedHashMap<>(base);
    for (final String flag : flags) {
      defines.put(flag, "");
    }
    return defines;
  }

  private Mcv2ShaderCompile() {}

  public static void main(final String[] arguments) throws IOException {
    final Path pack = Path.of(arguments[0]);
    final Path generated = Path.of(arguments[1]);
    final Path output = Files.createDirectories(Path.of(arguments[2]));
    final List<String> options = List.of(arguments).subList(3, arguments.length);
    final int vanillaOption = options.indexOf("--vanilla");
    final Path vanilla = vanillaOption < 0 ? null : Path.of(options.get(vanillaOption + 1));
    final boolean postOnly = options.contains("--post-only");
    int failures = 0;
    for (final Map.Entry<String, Map<String, String>> variant : postOnly ? Map.<String, Map<String, String>>of().entrySet() : TEXT_VARIANTS.entrySet()) {
      for (final String stage : List.of("vsh", "fsh")) {
        final Path source = pack.resolve("assets/minecraft/shaders/core/text." + stage);
        failures += compile(pack, generated, vanilla, source, stage, variant.getValue(), output.resolve(variant.getKey() + "." + stage));
      }
    }
    // a generated pack has each screen's copy of its passes in post/s<screen>/
    final Path post = pack.resolve("assets/mcav/shaders/post");
    try (final var files = Files.walk(post)) {
      for (final Path source : files.filter(Files::isRegularFile).sorted().toList()) {
        final String name = post.relativize(source).toString().replace(post.getFileSystem().getSeparator(), "_");
        final String stage = name.substring(name.lastIndexOf('.') + 1);
        failures += compile(pack, generated, vanilla, source, stage, Map.of(), output.resolve(name));
      }
    }
    System.out.println(failures == 0 ? "every stage compiled" : failures + " stages failed");
    System.exit(failures);
  }

  private static int compile(
    final Path pack,
    final Path generated,
    final Path vanilla,
    final Path source,
    final String stage,
    final Map<String, String> defines,
    final Path target
  ) throws IOException {
    final String text = Files.readString(source);
    final long compiler = shaderc_compiler_initialize();
    final long options = shaderc_compile_options_initialize();
    try (MemoryStack stack = stackPush()) {
      shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, VULKAN_1_2);
      shaderc_compile_options_set_auto_bind_uniforms(options, true);
      shaderc_compile_options_set_preserve_bindings(options, false);
      shaderc_compile_options_set_generate_debug_info(options);
      shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
      for (final Map.Entry<String, String> macro : RENDERER_MACROS.entrySet()) {
        shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
      }
      for (final Map.Entry<String, String> define : defines.entrySet()) {
        shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
      }
      final List<ByteBuffer> keep = new ArrayList<>();
      shaderc_compile_options_set_include_callbacks(
        options,
        ShadercIncludeResolve.create((user, requested, type, requesting, depth) -> include(pack, generated, vanilla, MemoryUtil.memUTF8(requested), keep)),
        ShadercIncludeResultRelease.create((user, result) -> {}),
        0L
      );
      final int kind = stage.equals("vsh") ? shaderc_vertex_shader : shaderc_fragment_shader;
      final long result = shaderc_compile_into_spv(compiler, text, kind, source.getFileName().toString(), "main", options);
      if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
        System.out.println("FAIL " + target.getFileName() + ": " + shaderc_result_get_error_message(result));
        shaderc_result_release(result);
        return 1;
      }
      final ByteBuffer spirv = shaderc_result_get_bytes(result);
      final String glsl = crossCompile(spirv, kind == shaderc_vertex_shader);
      shaderc_result_release(result);
      Files.writeString(target, glsl);
      System.out.println("ok   " + target.getFileName());
      return 0;
    } finally {
      shaderc_compile_options_release(options);
      shaderc_compiler_release(compiler);
    }
  }

  private static long include(final Path pack, final Path generated, final Path vanilla, final String requested, final List<ByteBuffer> keep) {
    final int colon = requested.indexOf(':');
    final String namespace = requested.substring(0, colon);
    final String path = requested.substring(colon + 1);
    Path file = generated.resolve(path);
    if (!Files.exists(file)) {
      file = pack.resolve("assets/" + namespace + "/shaders/include/" + path);
    }
    if (!Files.exists(file) && vanilla != null) {
      file = vanilla.resolve("assets/" + namespace + "/shaders/include/" + path);
    }
    final ShadercIncludeResult result = ShadercIncludeResult.calloc();
    try {
      final ByteBuffer content = MemoryUtil.memUTF8(Files.readString(file), false);
      final ByteBuffer name = MemoryUtil.memUTF8(requested, false);
      keep.add(content);
      keep.add(name);
      result.content(content).source_name(name);
    } catch (final IOException exception) {
      final ByteBuffer message = MemoryUtil.memUTF8("cannot read " + file, false);
      keep.add(message);
      result.content(message).source_name(MemoryUtil.memUTF8("", false));
    }
    return result.address();
  }

  private static String crossCompile(final ByteBuffer spirv, final boolean vertex) {
    try (MemoryStack stack = stackPush()) {
      final PointerBuffer pointer = stack.callocPointer(1);
      check(spvc_context_create(pointer), "context");
      final long context = pointer.get(0);
      try {
        check(spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, pointer), "parse");
        final long ir = pointer.get(0);
        check(spvc_context_create_compiler(context, SPVC_BACKEND_GLSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "compiler");
        final long compiler = pointer.get(0);
        spvc_compiler_create_compiler_options(compiler, pointer);
        final long options = pointer.get(0);
        spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, 330);
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER, true);
        // the game sets this to whether the device has shader draw parameters; no shader here reads gl_InstanceIndex
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE, false);
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLATTEN_MULTIDIMENSIONAL_ARRAYS, true);
        check(spvc_compiler_create_shader_resources(compiler, pointer), "resources");
        final long resources = pointer.get(0);
        // without separate shader objects the stages link by name, so the game names every stage input and output
        // after its location
        final int attributes = vertex ? SPVC_RESOURCE_TYPE_STAGE_INPUT : SPVC_RESOURCE_TYPE_STAGE_OUTPUT;
        final int varyings = vertex ? SPVC_RESOURCE_TYPE_STAGE_OUTPUT : SPVC_RESOURCE_TYPE_STAGE_INPUT;
        renameByLocation(compiler, resources, attributes, vertex ? "_vert_input_%02d" : "_frag_output_%02d");
        renameByLocation(compiler, resources, varyings, "_interface_variable_%02d");
        spvc_compiler_install_compiler_options(compiler, options);
        final PointerBuffer source = stack.callocPointer(1);
        check(spvc_compiler_compile(compiler, source), "compile");
        return MemoryUtil.memUTF8(source.get(0));
      } finally {
        spvc_context_destroy(context);
      }
    }
  }

  private static void renameByLocation(final long compiler, final long resources, final int type, final String format) {
    try (MemoryStack stack = stackPush()) {
      final PointerBuffer list = stack.callocPointer(1);
      final PointerBuffer count = stack.callocPointer(1);
      check(spvc_resources_get_resource_list_for_type(resources, type, list, count), "interface");
      for (final SpvcReflectedResource variable : SpvcReflectedResource.create(list.get(0), (int) count.get(0))) {
        final int location = spvc_compiler_get_decoration(compiler, variable.id(), SpvDecorationLocation);
        spvc_compiler_set_name(compiler, variable.id(), String.format(Locale.ROOT, format, location));
      }
    }
  }

  private static void check(final int result, final String step) {
    if (result != SPVC_SUCCESS) {
      throw new IllegalStateException("SPIRV-Cross failed at " + step + ": " + result);
    }
  }
}

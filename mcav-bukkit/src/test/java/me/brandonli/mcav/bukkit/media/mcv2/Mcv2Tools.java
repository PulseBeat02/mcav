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

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.spvc.Spv.SpvDecorationLocation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

final class Mcv2Tools {

  private static final String PROXY_DEFAULT_STREAM = "proxy-default.mcs";
  private static final String PROXY_KEYFRAMES_STREAM = "proxy-keyframes.mcs";
  private static final String PROXY_ODD_STREAM = "proxy-odd.mcs";
  private static final String GAMEPLAY_DEFAULT_STREAM = "gameplay-default.mcs";
  private static final String GAMEPLAY_FAST_STREAM = "gameplay-fast.mcs";
  private static final String CROP_DEFAULT_STREAM = "crop-default.mcs";
  private static final String CROP_FAST_STREAM = "crop-fast.mcs";

  private static final String CONFORMANCE_FOLDER = "conformance/";
  private static final String DIGESTS_PATH = "conformance/digests.json";
  private static final String PAGES_PATH = "conformance/pages.json";
  private static final String ENCODER_FOLDER = "encoder/";
  private static final String CROP_SOURCE_PATH = "encoder/crop-320x180x4.rgb";

  private static final String PROXY_SOURCE = "proxy";
  private static final String GAMEPLAY_SOURCE = "gameplay";
  private static final String SOURCE_SUFFIX = "30_pp600_1920x1080.rgb";

  private static final String ASSETS_FOLDER = "assets/";
  private static final String INCLUDE_FOLDER = "/shaders/include/";
  private static final String TEXT_SHADER_PREFIX = "assets/minecraft/shaders/core/text.";
  private static final String POST_SHADER_FOLDER = "assets/mcav/shaders/post";

  private static final String VERTEX_STAGE = "vsh";
  private static final String FRAGMENT_STAGE = "fsh";

  private static final String READ_MODE = "r";

  private static final String NATIVES_FOLDER_PREFIX = "mcv2-bench-natives-";

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private static final String FRAME_BUDGET_OPTION = "framebudget";
  private static final String LAMBDA_OPTION = "lambda";
  private static final String OUTPUT_OPTION = "out";
  private static final String DECODED_OPTION = "decoded";

  private static final char OPTION_SEPARATOR = '=';

  private static final String DEFAULT_FRAMES = "60";
  private static final String DEFAULT_THREADS = "12";
  private static final String DEFAULT_FRAME_RATE = "60";
  private static final String DEFAULT_PROFILE = "DEFAULT";

  private static final int SINGLE_THREAD = 1;

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;
  private static final double BYTES_PER_MEGABYTE = 1048576.0;
  private static final double BITS_PER_MEGABIT = 1e6;

  private static final int DEFLATE_BUFFER = 1 << 16;

  private static final double MAXIMUM_SAMPLE = 255.0;

  private static final double EXACT_PSNR = 100.0;

  private static final double NINETY_FIFTH_PERCENTILE = 0.95;

  private static final int STREAM_ID = 1;
  private static final int CONFORMANCE_STREAM_ID = 7;

  private static final int PAGE_VECTOR_FRAMES = 4;

  private static final int CONFORMANCE_SYMBOL_BITS = 6;

  private static final int LENGTH_BYTES = 4;

  private static final String TRUNCATED = "truncated";

  private static final String REJECTED = "reject";

  private static final int SOURCE_WIDTH = 1920;
  private static final int SOURCE_HEIGHT = 1080;

  private static final int FIXTURE_WIDTH = 320;
  private static final int FIXTURE_HEIGHT = 180;

  private static final int ODD_WIDTH = 319;
  private static final int ODD_HEIGHT = 179;

  private static final int FIXTURE_FRAMES = 8;
  private static final int KEYFRAME_FIXTURE_FRAMES = 4;
  private static final int ODD_FIXTURE_FRAMES = 6;
  private static final int GOLDEN_FRAMES = 4;

  private static final int CHANNELS = 3;

  private static final int MAXIMUM_ARCHIVE_BYTES = 1_000_000;

  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  private static final int VULKAN_1_2 = (1 << 22) | (2 << 12);

  private static final int GLSL_VERSION = 330;

  private static final String ENTRY_POINT = "main";

  private static final long NO_USER_DATA = 0L;

  private static final char NAMESPACE_SEPARATOR = ':';

  private static final String VERTEX_INPUT_NAME_FORMAT = "_vert_input_%02d";
  private static final String FRAGMENT_OUTPUT_NAME_FORMAT = "_frag_output_%02d";
  private static final String INTERFACE_VARIABLE_NAME_FORMAT = "_interface_variable_%02d";

  // the macros GlslCompiler defines for every shader on a device with a zero-to-one depth range; it also defines
  // RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE on devices with shader draw parameters, which no shader reads
  private static final Map<String, String> RENDERER_MACROS = Map.of("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");

  // what GlslCompiler defines on drivers that need depth invariance spelled out; only the transparency includes read it
  private static final String EXPLICIT_DEPTH_INVARIANCE = "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE";

  private static final String GRAYSCALE_MACRO = "IS_GRAYSCALE";
  private static final String SEE_THROUGH_MACRO = "IS_SEE_THROUGH";
  private static final String ALPHA_ONLY_MACRO = "OIT_ALPHA_ONLY";
  private static final String DEPTH_BOUNDS_MACRO = "OIT_DEPTH_BOUNDS";
  private static final String TRANSMITTANCE_MACRO = "OIT_TRANSMITTANCE";
  private static final String ACCUMULATE_MACRO = "OIT_ACCUMULATE";

  private static final String MACRO_ENABLED = "1";

  // RenderPipelines' OIT snippet: a wavelet of rank 2, LevelRenderer.OIT_COEFFICIENT_COUNT = 2^3 coefficients, a
  // quarter of them per transmittance target
  private static final Map<String, String> OIT = Map.of(
    "OIT",
    "",
    "OIT_WAVELET_RANK",
    "2",
    "OIT_COEFF_COUNT",
    "8",
    "OIT_COEFF_ATTACHMENT_COUNT",
    "2"
  );

  private static final Map<String, Map<String, String>> TEXT_VARIANTS = createTextVariants();

  private Mcv2Tools() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  private static Map<String, Map<String, String>> createTextVariants() {
    final Map<String, Map<String, String>> variants = new LinkedHashMap<>();
    variants.put("text", Map.of());
    variants.put("text_grayscale", Map.of(GRAYSCALE_MACRO, MACRO_ENABLED));
    variants.put("text_see_through", Map.of(SEE_THROUGH_MACRO, MACRO_ENABLED));
    variants.put("text_grayscale_see_through", Map.of(GRAYSCALE_MACRO, MACRO_ENABLED, SEE_THROUGH_MACRO, MACRO_ENABLED));
    variants.put("gui_text", Map.of("IS_GUI", MACRO_ENABLED));
    variants.put("oit_text_depth_bounds", withFlags(OIT, ALPHA_ONLY_MACRO, DEPTH_BOUNDS_MACRO));
    variants.put("oit_text_transmittance", withFlags(OIT, ALPHA_ONLY_MACRO, TRANSMITTANCE_MACRO));
    variants.put("oit_text_accumulate", withFlags(OIT, ACCUMULATE_MACRO));
    variants.put("oit_text_depth_bounds_invariant", withFlags(OIT, ALPHA_ONLY_MACRO, DEPTH_BOUNDS_MACRO, EXPLICIT_DEPTH_INVARIANCE));
    variants.put("oit_text_transmittance_invariant", withFlags(OIT, ALPHA_ONLY_MACRO, TRANSMITTANCE_MACRO, EXPLICIT_DEPTH_INVARIANCE));
    variants.put("oit_text_accumulate_invariant", withFlags(OIT, ACCUMULATE_MACRO, EXPLICIT_DEPTH_INVARIANCE));

    return Collections.unmodifiableMap(variants);
  }

  static void main(final String[] arguments) throws Exception {
    if (arguments.length == 0) {
      final String message = "Expected bench, digests, generate-fixtures or shader-compile!";
      throw new IllegalArgumentException(message);
    }

    final String[] options = Arrays.copyOfRange(arguments, 1, arguments.length);
    switch (arguments[0]) {
      case "bench" -> bench(options);
      case "digests" -> printDigests(options);
      case "generate-fixtures" -> generateFixtures(options);
      case "shader-compile" -> System.exit(shaderCompile(options));
      default -> {
        final String message = "Subcommand %s is unknown!".formatted(arguments[0]);
        throw new IllegalArgumentException(message);
      }
    }
  }

  // on Windows, loaded DLLs and their folder may outlive the JVM
  private static void bench(final String[] arguments) throws Exception {
    final Map<String, String> options = parseOptions(arguments);
    final Path nativeFolder = installNatives(options.getOrDefault("natives", Mcv2.NATIVE_AUTO));
    try {
      final int width = Integer.parseInt(options.getOrDefault("width", String.valueOf(SOURCE_WIDTH)));
      final int height = Integer.parseInt(options.getOrDefault("height", String.valueOf(SOURCE_HEIGHT)));
      final int frames = Integer.parseInt(options.getOrDefault("frames", DEFAULT_FRAMES));
      final int threads = Integer.parseInt(options.getOrDefault("threads", DEFAULT_THREADS));
      final int warmUpFrames = Integer.parseInt(options.getOrDefault("warm", "0"));
      final double frameRate = Double.parseDouble(options.getOrDefault("fps", DEFAULT_FRAME_RATE));
      final Path source = Path.of(options.get("source"));

      System.gc();
      final long heapBefore = usedHeap();
      final Pool pool = new Pool(threads);
      final boolean verify = Boolean.parseBoolean(options.getOrDefault("verify", "false"));
      final Mcv2.Verification verification = verify ? Mcv2.Verification.VERIFIED : Mcv2.Verification.UNVERIFIED;
      final Mcv2 encoder = pool.encoder(parseSettings(options), verification);
      if (options.containsKey(FRAME_BUDGET_OPTION)) {
        encoder.setFrameBudget((long) (Double.parseDouble(options.get(FRAME_BUDGET_OPTION)) * NANOSECONDS_PER_MILLISECOND));
      }

      final Measurement measured = encode(options, source, width, height, frames, pool, encoder);
      final long heapAfter;
      try {
        System.gc();
        heapAfter = usedHeap();
      } finally {
        Reference.reachabilityFence(encoder);
      }

      pool.close();
      report(measured, frames, warmUpFrames, frameRate, threads, (heapAfter - heapBefore) / BYTES_PER_MEGABYTE);
    } finally {
      try (final Stream<Path> paths = Files.walk(nativeFolder)) {
        paths.forEach(path -> path.toFile().deleteOnExit());
      }
    }
  }

  private static Path installNatives(final String mode) throws IOException {
    if (!mode.equals(Mcv2.NATIVE_AUTO) && !mode.equals(Mcv2.NATIVE_OFF)) {
      final String message = "Natives mode %s must be auto or off!".formatted(mode);
      throw new IllegalArgumentException(message);
    }

    final Path folder = Files.createTempDirectory(NATIVES_FOLDER_PREFIX);
    Mcv2.installNatives(folder, mode);

    return folder;
  }

  private static Map<String, String> parseOptions(final String[] arguments) {
    final Map<String, String> options = new HashMap<>();
    for (final String argument : arguments) {
      final int separator = argument.indexOf(OPTION_SEPARATOR);
      options.put(argument.substring(0, separator), argument.substring(separator + 1));
    }

    return options;
  }

  private static long usedHeap() {
    final Runtime runtime = Runtime.getRuntime();
    final long total = runtime.totalMemory();
    final long free = runtime.freeMemory();
    return total - free;
  }

  private static Settings parseSettings(final Map<String, String> options) {
    final String profile = options.getOrDefault("profile", DEFAULT_PROFILE);
    Settings settings = switch (profile.toUpperCase(Locale.ROOT)) {
      case DEFAULT_PROFILE -> Settings.DEFAULT;
      case "FAST" -> Settings.FAST;
      default -> {
        final String message = "Profile %s is unknown, the valid profiles are DEFAULT and FAST!".formatted(profile);
        throw new IllegalArgumentException(message);
      }
    };

    if (options.containsKey(LAMBDA_OPTION)) {
      settings = settings.withLambda(Double.parseDouble(options.get(LAMBDA_OPTION)));
    }

    return settings;
  }

  private static Measurement encode(
    final Map<String, String> options,
    final Path source,
    final int width,
    final int height,
    final int frames,
    final Pool pool,
    final Mcv2 encoder
  ) throws Exception {
    final long frameBytes = (long) width * height * CHANNELS;
    final int sourceFrames = (int) (Files.size(source) / frameBytes);
    final String loop = options.getOrDefault("loop", "none");
    final FrameMeter meter = new FrameMeter(options, encoder, pool, width, height, frames);
    try (
      final RandomAccessFile input = new RandomAccessFile(source.toFile(), READ_MODE);
      final @Nullable DataOutputStream output = openFrameOutput(options);
      final @Nullable OutputStream decoded = openDecodedOutput(options)
    ) {
      final byte[] rgb = new byte[(int) frameBytes];
      for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
        input.seek(sourceIndex(frameIndex, loop, sourceFrames) * frameBytes);
        input.readFully(rgb);
        final EncodedFrame frame = meter.encode(rgb, frameIndex);
        writeFrame(output, frame.getData());
        writeDecoded(decoded, frame.getPicture());
      }
    }

    return meter.getMeasurement();
  }

  private static @Nullable DataOutputStream openFrameOutput(final Map<String, String> options) throws IOException {
    if (!options.containsKey(OUTPUT_OPTION)) {
      return null;
    }

    final Path path = Path.of(options.get(OUTPUT_OPTION));
    final OutputStream file = Files.newOutputStream(path);
    final OutputStream buffered = new BufferedOutputStream(file);
    return new DataOutputStream(buffered);
  }

  private static @Nullable OutputStream openDecodedOutput(final Map<String, String> options) throws IOException {
    if (!options.containsKey(DECODED_OPTION)) {
      return null;
    }

    final Path path = Path.of(options.get(DECODED_OPTION));
    final OutputStream file = Files.newOutputStream(path);
    return new BufferedOutputStream(file);
  }

  private static void writeFrame(final @Nullable DataOutputStream output, final byte[] data) throws IOException {
    if (output == null) {
      return;
    }

    output.writeInt(Integer.reverseBytes(data.length));
    output.write(data);
  }

  private static void writeDecoded(final @Nullable OutputStream decoded, final byte[] picture) throws IOException {
    if (decoded == null) {
      return;
    }

    decoded.write(picture);
  }

  private static byte[] decode(final byte[] data, final byte[] predictFrom) {
    final Frame frame = Mcv2Decoder.parse(data);
    return Mcv2Decoder.decode(frame, predictFrom, frame.getReferenceId());
  }

  private static int sourceIndex(final int frame, final String loop, final int sourceFrames) {
    return switch (loop) {
      case "pingpong" -> findPingPongIndex(frame, sourceFrames);
      case "wrap" -> frame % sourceFrames;
      default -> frame;
    };
  }

  private static int findPingPongIndex(final int frame, final int sourceFrames) {
    final int period = 2 * (sourceFrames - 1);
    if (period == 0) {
      return 0;
    }

    final int phase = frame % period;
    if (phase < sourceFrames) {
      return phase;
    }

    return period - phase;
  }

  private static long zlibBytes(final List<byte[]> pages, final Deflater deflater, final byte[] buffer) {
    long bytes = 0;
    for (final byte[] page : pages) {
      deflater.reset();
      deflater.setInput(MapAlphabet.toMapColors(page));
      deflater.finish();
      int compressed = 0;
      while (!deflater.finished()) {
        compressed += deflater.deflate(buffer);
      }

      bytes += compressed + TransportPages.PACKET_OVERHEAD;
    }

    return bytes;
  }

  private static long squaredError(final byte[] rgb, final byte[] picture) {
    long sum = 0;
    for (int index = 0; index < rgb.length; index++) {
      final int difference = Byte.toUnsignedInt(rgb[index]) - Byte.toUnsignedInt(picture[index]);
      sum += (long) difference * difference;
    }

    return sum;
  }

  private static double psnr(final double mse) {
    return 10 * Math.log10((MAXIMUM_SAMPLE * MAXIMUM_SAMPLE) / mse);
  }

  private static void report(
    final Measurement measured,
    final int frames,
    final int warmUpFrames,
    final double frameRate,
    final int threads,
    final double encoderHeapMegabytes
  ) {
    final Runtime runtime = Runtime.getRuntime();
    final int from = Math.min(warmUpFrames, frames - 1);
    final long[] warmTimes = Arrays.copyOfRange(measured.times, from, frames);
    final long[] sorted = warmTimes.clone();
    Arrays.sort(sorted);
    final double seconds = frames / frameRate;
    System.out.printf(
      Locale.ROOT,
      "{\"frames\":%d,\"warm\":%d,\"keyframes\":%d,\"mean_ms\":%.3f,\"p50_ms\":%.3f,\"p95_ms\":%.3f,\"max_ms\":%.3f,\"cpu_ms\":%.3f," +
        "\"alloc_mb_per_frame\":%.2f,\"logical_mbps\":%.6f,\"map_mbps\":%.6f,\"zlib_mbps\":%.6f,\"psnr_mean\":%.4f,\"psnr_global\":%.4f," +
        "\"fps\":%.1f,\"threads\":%d,\"processors\":%d,\"encoder_heap_mb\":%.1f,\"natives\":%s}%n",
      frames,
      warmTimes.length,
      measured.keyframes,
      Arrays.stream(warmTimes).average().orElse(0) / NANOSECONDS_PER_MILLISECOND,
      sorted[sorted.length / 2] / NANOSECONDS_PER_MILLISECOND,
      sorted[(int) Math.ceil(sorted.length * NINETY_FIFTH_PERCENTILE) - 1] / NANOSECONDS_PER_MILLISECOND,
      sorted[sorted.length - 1] / NANOSECONDS_PER_MILLISECOND,
      Arrays.stream(Arrays.copyOfRange(measured.cpu, from, frames))
        .average()
        .orElse(0) / NANOSECONDS_PER_MILLISECOND,
      Arrays.stream(Arrays.copyOfRange(measured.allocated, from, frames))
        .average()
        .orElse(0) / BYTES_PER_MEGABYTE,
      megabits(measured.logical, seconds),
      megabits(measured.wire, seconds),
      megabits(measured.zlib, seconds),
      measured.psnrSum / frames,
      psnr(measured.mseSum / frames),
      frameRate,
      threads,
      runtime.availableProcessors(),
      encoderHeapMegabytes,
      JSON.toJson(Mcv2.describeNatives())
    );
  }

  private static double megabits(final long bytes, final double seconds) {
    return (bytes * Byte.SIZE) / seconds / BITS_PER_MEGABIT;
  }

  private static void printDigests(final String[] arguments) throws Exception {
    final MessageDigest sha256 = MessageDigest.getInstance(DIGEST_ALGORITHM);
    final boolean rgb = arguments.length >= 2 && arguments[0].equals("--rgb");
    final List<String> archives = Arrays.asList(arguments).subList(rgb ? 2 : 0, arguments.length);
    try (final OutputStream pictures = openPicturesOutput(arguments, rgb)) {
      for (final String argument : archives) {
        System.out.println("%s%s".formatted(argument, digestArchive(Files.readAllBytes(Path.of(argument)), sha256, pictures)));
      }
    }
  }

  private static OutputStream openPicturesOutput(final String[] arguments, final boolean rgb) throws IOException {
    if (!rgb) {
      return OutputStream.nullOutputStream();
    }

    final Path path = Path.of(arguments[1]);
    final OutputStream file = Files.newOutputStream(path);
    return new BufferedOutputStream(file);
  }

  private static String digestArchive(final byte[] archive, final MessageDigest sha256, final OutputStream pictures) throws IOException {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final StringBuilder line = new StringBuilder();
    int offset = 0;
    while (offset < archive.length) {
      final long length = archive.length - offset < LENGTH_BYTES ? -1 : Mcv2Decoder.readUnsignedInt(archive, offset);
      if (length < 0 || length > archive.length - offset - LENGTH_BYTES) {
        line.append(' ').append(TRUNCATED);
        break;
      }
      final byte[] frame = Arrays.copyOfRange(archive, offset + LENGTH_BYTES, offset + LENGTH_BYTES + (int) length);
      line.append(' ').append(describeFrame(receiver, frame, sha256, pictures));
      offset += LENGTH_BYTES + (int) length;
    }

    return line.toString();
  }

  private static String describeFrame(
    final Mcv2Receiver receiver,
    final byte[] frame,
    final MessageDigest sha256,
    final OutputStream pictures
  ) throws IOException {
    final byte[] picture;
    try {
      picture = receiver.accept(frame);
    } catch (final Mcv2Exception exception) {
      return REJECTED;
    }

    pictures.write(picture);
    return HexFormat.of().formatHex(sha256.digest(picture));
  }

  private static void generateFixtures(final String[] arguments) throws Exception {
    final Path sources = Path.of(arguments[0]);
    final Path fixtures = Path.of(arguments[1]);
    final Map<String, Object> digests = new LinkedHashMap<>();
    final Map<String, Object> pages = new LinkedHashMap<>();
    try (final ForkJoinPool pool = new ForkJoinPool(SINGLE_THREAD)) {
      generate(
        sources,
        fixtures,
        pool,
        PROXY_DEFAULT_STREAM,
        PROXY_SOURCE,
        Settings.DEFAULT,
        FIXTURE_WIDTH,
        FIXTURE_HEIGHT,
        FIXTURE_FRAMES,
        digests,
        pages
      );
      generate(
        sources,
        fixtures,
        pool,
        GAMEPLAY_DEFAULT_STREAM,
        GAMEPLAY_SOURCE,
        Settings.DEFAULT,
        FIXTURE_WIDTH,
        FIXTURE_HEIGHT,
        FIXTURE_FRAMES,
        digests,
        pages
      );
      generate(
        sources,
        fixtures,
        pool,
        GAMEPLAY_FAST_STREAM,
        GAMEPLAY_SOURCE,
        Settings.FAST,
        FIXTURE_WIDTH,
        FIXTURE_HEIGHT,
        FIXTURE_FRAMES,
        digests,
        pages
      );
      generate(
        sources,
        fixtures,
        pool,
        PROXY_KEYFRAMES_STREAM,
        PROXY_SOURCE,
        Settings.DEFAULT,
        FIXTURE_WIDTH,
        FIXTURE_HEIGHT,
        KEYFRAME_FIXTURE_FRAMES,
        digests,
        pages
      );
      generate(
        sources,
        fixtures,
        pool,
        PROXY_ODD_STREAM,
        PROXY_SOURCE,
        Settings.DEFAULT,
        ODD_WIDTH,
        ODD_HEIGHT,
        ODD_FIXTURE_FRAMES,
        digests,
        pages
      );
      generateGolden(fixtures, pool, Settings.DEFAULT, CROP_DEFAULT_STREAM);
      generateGolden(fixtures, pool, Settings.FAST, CROP_FAST_STREAM);
    }

    Files.writeString(fixtures.resolve(DIGESTS_PATH), "%s\n".formatted(JSON.toJson(digests)));
    Files.writeString(fixtures.resolve(PAGES_PATH), "%s\n".formatted(JSON.toJson(pages)));
  }

  private static void generate(
    final Path sources,
    final Path fixtures,
    final ForkJoinPool pool,
    final String name,
    final String source,
    final Settings settings,
    final int width,
    final int height,
    final int count,
    final Map<String, Object> digests,
    final Map<String, Object> pages
  ) throws Exception {
    final Mcv2 encoder = new Mcv2(settings, pool, SINGLE_THREAD, Mcv2.Verification.VERIFIED);
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    final List<String> hashes = new ArrayList<>();
    final byte[] rgb = new byte[width * height * CHANNELS];
    final FixtureArchive fixture = new FixtureArchive(name, encoder, archive, hashes, pages, rgb, width, height);
    final String raw = "%s%s".formatted(source, SOURCE_SUFFIX);
    try (final RandomAccessFile input = new RandomAccessFile(sources.resolve(raw).toFile(), READ_MODE)) {
      boolean fits = true;
      for (int id = 0; id < count && fits; id++) {
        crop(input, id, rgb, width, height);
        fits = fixture.addFrame(id);
      }
    }

    Files.write(fixtures.resolve("%s%s".formatted(CONFORMANCE_FOLDER, name)), archive.toByteArray());
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("source", raw);
    expected.put("crop", List.of((SOURCE_WIDTH - width) / 2, (SOURCE_HEIGHT - height) / 2, width, height));
    expected.put(Mcv2Fixtures.FRAME_DIGESTS_KEY, hashes);
    digests.put(name, expected);
    System.out.println("%s: %s frames, %s bytes".formatted(name, hashes.size(), archive.size()));
  }

  private static void crop(final RandomAccessFile input, final int frame, final byte[] rgb, final int width, final int height)
    throws IOException {
    final long first = (long) frame * SOURCE_WIDTH * SOURCE_HEIGHT * CHANNELS;
    final int left = (SOURCE_WIDTH - width) / 2;
    final int top = (SOURCE_HEIGHT - height) / 2;
    for (int row = 0; row < height; row++) {
      input.seek(first + ((long) (top + row) * SOURCE_WIDTH + left) * CHANNELS);
      input.readFully(rgb, row * width * CHANNELS, width * CHANNELS);
    }
  }

  private static void generateGolden(final Path fixtures, final ForkJoinPool pool, final Settings settings, final String name)
    throws IOException {
    final byte[] source = Files.readAllBytes(fixtures.resolve(CROP_SOURCE_PATH));
    final int bytes = FIXTURE_WIDTH * FIXTURE_HEIGHT * CHANNELS;
    if (source.length != bytes * GOLDEN_FRAMES) {
      final String message = "Expected exactly four 320x180 frames!";
      throw new IllegalArgumentException(message);
    }

    final Mcv2 encoder = new Mcv2(settings, pool, SINGLE_THREAD, Mcv2.Verification.VERIFIED);
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    for (int id = 0; id < GOLDEN_FRAMES; id++) {
      appendFrame(archive, encoder.encode(Arrays.copyOfRange(source, id * bytes, (id + 1) * bytes), FIXTURE_WIDTH, FIXTURE_HEIGHT, id));
    }

    Files.write(fixtures.resolve("%s%s".formatted(ENCODER_FOLDER, name)), archive.toByteArray());
    System.out.println("%s: %s frames, %s bytes".formatted(name, GOLDEN_FRAMES, archive.size()));
  }

  private static void appendFrame(final ByteArrayOutputStream output, final byte[] data) {
    final byte[] length = new byte[Integer.BYTES];
    Mcv2Decoder.putUnsignedInt(length, 0, data.length);
    output.writeBytes(length);
    output.writeBytes(data);
  }

  private static Map<String, String> withFlags(final Map<String, String> base, final String... flags) {
    final Map<String, String> defines = new LinkedHashMap<>(base);
    for (final String flag : flags) {
      defines.put(flag, "");
    }

    return defines;
  }

  // keeps the sampler names, so the Python GL harness can bind them
  private static int shaderCompile(final String[] arguments) throws IOException {
    final Path pack = Path.of(arguments[0]);
    final Path generated = Path.of(arguments[1]);
    final Path output = Files.createDirectories(Path.of(arguments[2]));
    final List<String> options = List.of(arguments).subList(3, arguments.length);
    final int vanillaOption = options.indexOf("--vanilla");
    final @Nullable Path vanilla = vanillaOption < 0 ? null : Path.of(options.get(vanillaOption + 1));
    final boolean postOnly = options.contains("--post-only");
    int failures = 0;
    for (final Map.Entry<String, Map<String, String>> variant : postOnly
      ? Map.<String, Map<String, String>>of().entrySet()
      : TEXT_VARIANTS.entrySet()) {
      for (final String stage : List.of(VERTEX_STAGE, FRAGMENT_STAGE)) {
        final Path source = pack.resolve("%s%s".formatted(TEXT_SHADER_PREFIX, stage));
        failures += compile(
          pack,
          generated,
          vanilla,
          source,
          stage,
          variant.getValue(),
          output.resolve("%s.%s".formatted(variant.getKey(), stage))
        );
      }
    }

    final Path post = pack.resolve(POST_SHADER_FOLDER);
    try (final Stream<Path> files = Files.walk(post)) {
      for (final Path source : files.filter(Files::isRegularFile).sorted().toList()) {
        final String name = post.relativize(source).toString().replace(post.getFileSystem().getSeparator(), "_");
        final String stage = name.substring(name.lastIndexOf('.') + 1);
        failures += compile(pack, generated, vanilla, source, stage, Map.of(), output.resolve(name));
      }
    }

    System.out.println(failures == 0 ? "every stage compiled" : "%s stages failed".formatted(failures));
    return failures;
  }

  private static int compile(
    final Path pack,
    final Path generated,
    final @Nullable Path vanilla,
    final Path source,
    final String stage,
    final Map<String, String> defines,
    final Path target
  ) throws IOException {
    final String text = Files.readString(source);
    final long compiler = Shaderc.shaderc_compiler_initialize();
    final long options = Shaderc.shaderc_compile_options_initialize();
    try {
      Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, VULKAN_1_2);
      Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
      Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
      Shaderc.shaderc_compile_options_set_generate_debug_info(options);
      Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
      for (final Map.Entry<String, String> macro : RENDERER_MACROS.entrySet()) {
        Shaderc.shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
      }

      for (final Map.Entry<String, String> define : defines.entrySet()) {
        Shaderc.shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
      }

      final List<ByteBuffer> includeBuffers = new ArrayList<>();
      Shaderc.shaderc_compile_options_set_include_callbacks(
        options,
        ShadercIncludeResolve.create((user, requested, type, requesting, depth) ->
          include(pack, generated, vanilla, MemoryUtil.memUTF8(requested), includeBuffers)
        ),
        ShadercIncludeResultRelease.create((user, result) -> {}),
        NO_USER_DATA
      );

      final ShaderStage shaderStage = stage.equals(VERTEX_STAGE) ? ShaderStage.VERTEX : ShaderStage.FRAGMENT;
      final int kind = shaderStage == ShaderStage.VERTEX ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
      final long result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, source.getFileName().toString(), ENTRY_POINT, options);
      if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
        System.out.println("FAIL %s: %s".formatted(target.getFileName(), Shaderc.shaderc_result_get_error_message(result)));
        Shaderc.shaderc_result_release(result);
        return 1;
      }

      final ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
      final String glsl = crossCompile(spirv, shaderStage);
      Shaderc.shaderc_result_release(result);
      Files.writeString(target, glsl);
      System.out.println("ok   %s".formatted(target.getFileName()));
      return 0;
    } finally {
      Shaderc.shaderc_compile_options_release(options);
      Shaderc.shaderc_compiler_release(compiler);
    }
  }

  private static long include(
    final Path pack,
    final Path generated,
    final @Nullable Path vanilla,
    final String requested,
    final List<ByteBuffer> includeBuffers
  ) {
    final int colon = requested.indexOf(NAMESPACE_SEPARATOR);
    final String namespace = requested.substring(0, colon);
    final String path = requested.substring(colon + 1);
    final String includePath = "%s%s%s%s".formatted(ASSETS_FOLDER, namespace, INCLUDE_FOLDER, path);
    Path file = generated.resolve(path);
    if (!Files.exists(file)) {
      file = pack.resolve(includePath);
    }

    if (!Files.exists(file) && vanilla != null) {
      file = vanilla.resolve(includePath);
    }

    final ShadercIncludeResult result = ShadercIncludeResult.calloc();
    try {
      final ByteBuffer content = MemoryUtil.memUTF8(Files.readString(file), false);
      final ByteBuffer name = MemoryUtil.memUTF8(requested, false);
      includeBuffers.add(content);
      includeBuffers.add(name);
      result.content(content).source_name(name);
    } catch (final IOException exception) {
      final String error = "cannot read %s".formatted(file);
      final ByteBuffer message = MemoryUtil.memUTF8(error, false);
      includeBuffers.add(message);
      result.content(message).source_name(MemoryUtil.memUTF8("", false));
    }

    return result.address();
  }

  private static String crossCompile(final ByteBuffer spirv, final ShaderStage shaderStage) {
    try (final MemoryStack stack = stackPush()) {
      final PointerBuffer pointer = stack.callocPointer(1);
      check(Spvc.spvc_context_create(pointer), "context");
      final long context = pointer.get(0);
      try {
        check(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / Integer.BYTES, pointer), "parse");
        final long intermediateRepresentation = pointer.get(0);
        check(
          Spvc.spvc_context_create_compiler(
            context,
            Spvc.SPVC_BACKEND_GLSL,
            intermediateRepresentation,
            Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
            pointer
          ),
          "compiler"
        );
        final long compiler = pointer.get(0);
        Spvc.spvc_compiler_create_compiler_options(compiler, pointer);
        final long options = pointer.get(0);
        setGlslOptions(options);
        check(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "resources");
        final long resources = pointer.get(0);
        renameStageVariables(compiler, resources, shaderStage);
        Spvc.spvc_compiler_install_compiler_options(compiler, options);
        final PointerBuffer source = stack.callocPointer(1);
        check(Spvc.spvc_compiler_compile(compiler, source), "compile");
        return MemoryUtil.memUTF8(source.get(0));
      } finally {
        Spvc.spvc_context_destroy(context);
      }
    }
  }

  private static void setGlslOptions(final long options) {
    Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, GLSL_VERSION);
    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER, true);
    // the game sets this to whether the device has shader draw parameters; no shader here reads gl_InstanceIndex
    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE, false);
    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLATTEN_MULTIDIMENSIONAL_ARRAYS, true);
  }

  private static void renameStageVariables(final long compiler, final long resources, final ShaderStage shaderStage) {
    // without separate shader objects the stages link by name, so the game names every stage input and output
    // after its location
    final boolean vertex = shaderStage == ShaderStage.VERTEX;
    final int attributes = vertex ? Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT : Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT;
    final int varyings = vertex ? Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT : Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT;
    renameByLocation(compiler, resources, attributes, vertex ? VERTEX_INPUT_NAME_FORMAT : FRAGMENT_OUTPUT_NAME_FORMAT);
    renameByLocation(compiler, resources, varyings, INTERFACE_VARIABLE_NAME_FORMAT);
  }

  private static void renameByLocation(final long compiler, final long resources, final int type, final String format) {
    try (final MemoryStack stack = stackPush()) {
      final PointerBuffer list = stack.callocPointer(1);
      final PointerBuffer count = stack.callocPointer(1);
      check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count), "interface");
      for (final SpvcReflectedResource variable : SpvcReflectedResource.create(list.get(0), (int) count.get(0))) {
        final int location = Spvc.spvc_compiler_get_decoration(compiler, variable.id(), SpvDecorationLocation);
        Spvc.spvc_compiler_set_name(compiler, variable.id(), String.format(Locale.ROOT, format, location));
      }
    }
  }

  private static void check(final int result, final String step) {
    if (result != Spvc.SPVC_SUCCESS) {
      final String message = "SPIRV-Cross returned %s at the %s step!".formatted(result, step);
      throw new IllegalStateException(message);
    }
  }

  private enum ShaderStage {
    VERTEX,
    FRAGMENT,
  }

  private static final class Measurement {

    private final long[] times;
    private final long[] cpu;
    private final long[] allocated;

    private long logical;
    private long wire;
    private long zlib;
    private long keyframes;

    private double psnrSum;
    private double mseSum;

    private Measurement(final int frames) {
      this.times = new long[frames];
      this.cpu = new long[frames];
      this.allocated = new long[frames];
    }
  }

  private static final class EncodedFrame {

    private final byte[] data;
    private final byte[] picture;

    private EncodedFrame(final byte[] data, final byte[] picture) {
      this.data = data;
      this.picture = picture;
    }

    private byte[] getData() {
      return this.data;
    }

    private byte[] getPicture() {
      return this.picture;
    }
  }

  private static final class FrameMeter {

    private final Mcv2 encoder;
    private final Pool pool;
    private final boolean inBudget;
    private final boolean forceKeyframes;
    private final int width;
    private final int height;
    private final OperatingSystemMXBean system;
    private final ThreadMXBean threadBean;
    private final Measurement measurement;
    private final Deflater deflater;
    private final byte[] buffer;

    private FrameMeter(
      final Map<String, String> options,
      final Mcv2 encoder,
      final Pool pool,
      final int width,
      final int height,
      final int frames
    ) {
      this.encoder = encoder;
      this.pool = pool;
      this.inBudget = Boolean.parseBoolean(options.getOrDefault("budget", "true"));
      this.forceKeyframes = options.getOrDefault("key", "0").equals("1");
      this.width = width;
      this.height = height;
      this.system = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      this.threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
      this.measurement = new Measurement(frames);
      this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
      this.buffer = new byte[DEFLATE_BUFFER];
    }

    private EncodedFrame encode(final byte[] rgb, final int frameIndex) throws Exception {
      final long cpuBefore = this.system.getProcessCpuTime();
      final long allocatedBefore = this.threadBean.getTotalThreadAllocatedBytes();
      final long startNanoseconds = System.nanoTime();
      if (this.forceKeyframes) {
        this.encoder.requestKeyframe();
      }

      final byte @Nullable [] predictFrom = this.encoder.getReference().orElse(null);
      final byte[] data = this.encodeInBudget(rgb, frameIndex);
      this.measurement.times[frameIndex] = System.nanoTime() - startNanoseconds;
      this.measurement.cpu[frameIndex] = this.system.getProcessCpuTime() - cpuBefore;
      this.measurement.allocated[frameIndex] = this.threadBean.getTotalThreadAllocatedBytes() - allocatedBefore;

      if (this.encoder.getStatistics().orElseThrow().isKeyframe()) {
        this.measurement.keyframes++;
      }

      this.measurement.logical += data.length;
      final List<byte[]> pages = TransportPages.makePages(data, STREAM_ID);
      this.measurement.wire += TransportPages.wireBytes(pages, TransportPages.PACKET_OVERHEAD);
      this.measurement.zlib += zlibBytes(pages, this.deflater, this.buffer);
      final byte[] picture = decode(data, predictFrom);
      final double mse = squaredError(rgb, picture) / (double) rgb.length;
      this.measurement.psnrSum += mse == 0 ? EXACT_PSNR : psnr(mse);
      this.measurement.mseSum += mse;

      return new EncodedFrame(data, picture);
    }

    private byte[] encodeInBudget(final byte[] rgb, final long frameId) throws Exception {
      if (!this.inBudget) {
        return this.encoder.encode(rgb, this.width, this.height, frameId);
      }

      return this.pool.run(() -> this.encoder.encode(rgb, this.width, this.height, frameId));
    }

    private Measurement getMeasurement() {
      return this.measurement;
    }
  }

  private static final class FixtureArchive {

    private final String name;
    private final Mcv2 encoder;
    private final Mcv2Receiver receiver;
    private final ByteArrayOutputStream archive;
    private final List<String> hashes;
    private final Map<String, Object> pages;
    private final byte[] rgb;
    private final int width;
    private final int height;

    private FixtureArchive(
      final String name,
      final Mcv2 encoder,
      final ByteArrayOutputStream archive,
      final List<String> hashes,
      final Map<String, Object> pages,
      final byte[] rgb,
      final int width,
      final int height
    ) {
      this.name = name;
      this.encoder = encoder;
      this.receiver = new Mcv2Receiver();
      this.archive = archive;
      this.hashes = hashes;
      this.pages = pages;
      this.rgb = rgb;
      this.width = width;
      this.height = height;
    }

    private boolean addFrame(final int id) throws Exception {
      if (this.name.equals(PROXY_KEYFRAMES_STREAM)) {
        this.encoder.requestKeyframe();
      }

      final byte[] data = this.encoder.encode(this.rgb, this.width, this.height, id);
      if (this.archive.size() + Integer.BYTES + data.length > MAXIMUM_ARCHIVE_BYTES) {
        return false;
      }

      appendFrame(this.archive, data);

      final byte[] picture = this.receiver.accept(data);
      if (!Arrays.equals(picture, this.encoder.getReference().orElseThrow())) {
        final String message = "Reference for %s frame %s differs!".formatted(this.name, id);
        throw new IllegalStateException(message);
      }

      this.hashes.add(Mcv2Resources.sha256(picture));

      if (this.name.equals(PROXY_DEFAULT_STREAM) && id < PAGE_VECTOR_FRAMES) {
        final String vectorName = "%s%s#%s@%s".formatted(CONFORMANCE_FOLDER, this.name, id, CONFORMANCE_SYMBOL_BITS);
        this.pages.put(vectorName, this.pageExpectations(data));
      }

      return true;
    }

    private Map<String, Object> pageExpectations(final byte[] data) {
      final List<byte[]> transportPages = TransportPages.makePages(data, CONFORMANCE_STREAM_ID);
      final List<String> pageHashes = transportPages.stream().map(Mcv2Resources::sha256).toList();

      final Map<String, Object> expected = new LinkedHashMap<>();
      expected.put("pages", pageHashes);
      expected.put("wire", TransportPages.wireBytes(transportPages, TransportPages.PACKET_OVERHEAD));
      expected.put("wire_full", TransportPages.fullMapWireBytes(transportPages, TransportPages.PACKET_OVERHEAD));
      return expected;
    }
  }
}

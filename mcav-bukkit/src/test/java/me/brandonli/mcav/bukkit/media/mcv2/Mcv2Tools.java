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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.Deflater;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
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

  private Mcv2Tools() {}

  static void main(final String[] arguments) throws Exception {
    if (arguments.length == 0) {
      throw new IllegalArgumentException("Expected bench, digests, generate-fixtures or shader-compile");
    }
    final String[] options = Arrays.copyOfRange(arguments, 1, arguments.length);
    switch (arguments[0]) {
      case "bench" -> bench(options);
      case "digests" -> digests(options);
      case "generate-fixtures" -> generateFixtures(options);
      case "shader-compile" -> System.exit(shaderCompile(options));
      default -> throw new IllegalArgumentException("Unknown subcommand " + arguments[0]);
    }
  }

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;

  private static final double BYTES_PER_MEGABYTE = 1048576.0;

  private static final double BITS_PER_MEGABIT = 1e6;

  private static final int DEFLATE_BUFFER = 1 << 16;

  private static final double MAX_SAMPLE = 255.0;

  private static final double EXACT_PSNR = 100.0;

  private static final double NINETY_FIFTH_PERCENTILE = 0.95;

  private static final int STREAM_ID = 1;

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

  /**
   * Native extraction files are scheduled for deletion at JVM shutdown. On Windows, loaded DLLs and their temporary
   * directory may remain because the libraries are still open while shutdown hooks run.
   */
  private static void bench(final String[] arguments) throws Exception {
    final Map<String, String> options = arguments(arguments);
    final Path nativeFolder = installNatives(options.getOrDefault("natives", MCV2.NATIVE_AUTO));
    try {
      final int width = Integer.parseInt(options.getOrDefault("width", "1920"));
      final int height = Integer.parseInt(options.getOrDefault("height", "1080"));
      final int frames = Integer.parseInt(options.getOrDefault("frames", "60"));
      final int threads = Integer.parseInt(options.getOrDefault("threads", "12"));
      final int warm = Integer.parseInt(options.getOrDefault("warm", "0"));
      final double frameRate = Double.parseDouble(options.getOrDefault("fps", "60"));
      final Path source = Path.of(options.get("source"));

      System.gc();
      final long heapBefore = usedHeap();
      final Pool budget = new Pool(threads);
      final MCV2 encoder = budget.encoder(settings(options), Boolean.parseBoolean(options.getOrDefault("verify", "false")));
      if (options.containsKey("framebudget")) {
        encoder.setFrameBudget((long) (Double.parseDouble(options.get("framebudget")) * NANOSECONDS_PER_MILLISECOND));
      }
      final Measurement measured = encode(options, source, width, height, frames, budget, encoder);
      final long heapAfter;
      try {
        System.gc();
        heapAfter = usedHeap();
      } finally {
        Reference.reachabilityFence(encoder);
      }
      budget.close();
      report(measured, frames, warm, frameRate, threads, (heapAfter - heapBefore) / BYTES_PER_MEGABYTE);
    } finally {
      try (final var paths = Files.walk(nativeFolder)) {
        paths.forEach(path -> path.toFile().deleteOnExit());
      }
    }
  }

  private static Path installNatives(final String mode) throws IOException {
    if (!mode.equals(MCV2.NATIVE_AUTO) && !mode.equals(MCV2.NATIVE_OFF)) {
      throw new IllegalArgumentException("natives must be auto or off");
    }
    final Path folder = Files.createTempDirectory("mcv2-bench-natives-");
    MCV2.installNatives(folder, mode);
    return folder;
  }

  private static Map<String, String> arguments(final String[] arguments) {
    final Map<String, String> options = new HashMap<>();
    for (final String argument : arguments) {
      final int separator = argument.indexOf('=');
      options.put(argument.substring(0, separator), argument.substring(separator + 1));
    }
    return options;
  }

  private static long usedHeap() {
    return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
  }

  private static Settings settings(final Map<String, String> options) {
    final String profile = options.getOrDefault("profile", "DEFAULT");
    Settings settings = switch (profile.toUpperCase(Locale.ROOT)) {
      case "DEFAULT" -> Settings.DEFAULT;
      case "FAST" -> Settings.FAST;
      default -> throw new IllegalArgumentException("Unknown profile " + profile + "; valid profiles: DEFAULT, FAST");
    };
    if (options.containsKey("lambda")) {
      settings = settings.withLambda(Double.parseDouble(options.get("lambda")));
    }
    return settings;
  }

  private static Measurement encode(
    final Map<String, String> options,
    final Path source,
    final int width,
    final int height,
    final int frames,
    final Pool budget,
    final MCV2 encoder
  ) throws Exception {
    final long frameBytes = (long) width * height * 3;
    final int sourceFrames = (int) (Files.size(source) / frameBytes);
    final String loop = options.getOrDefault("loop", "none");
    final boolean inBudget = Boolean.parseBoolean(options.getOrDefault("budget", "true"));
    final OperatingSystemMXBean system = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    final Measurement measured = new Measurement(frames);
    final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    final byte[] buffer = new byte[DEFLATE_BUFFER];
    try (
      RandomAccessFile input = new RandomAccessFile(source.toFile(), "r");
      DataOutputStream out = options.containsKey("out")
        ? new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(options.get("out")))))
        : null;
      OutputStream decoded = options.containsKey("decoded")
        ? new BufferedOutputStream(Files.newOutputStream(Path.of(options.get("decoded"))))
        : null
    ) {
      final byte[] rgb = new byte[(int) frameBytes];
      for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
        input.seek(sourceIndex(frameIndex, loop, sourceFrames) * frameBytes);
        input.readFully(rgb);
        final long cpuBefore = system.getProcessCpuTime();
        final long allocatedBefore = threadBean.getTotalThreadAllocatedBytes();
        final long startNanos = System.nanoTime();
        final long frameId = frameIndex;
        if (options.getOrDefault("key", "0").equals("1")) {
          encoder.requestKeyframe();
        }
        final byte[] predictFrom = encoder.getReference();
        final byte[] data = inBudget
          ? budget.run(() -> encoder.encode(rgb, width, height, frameId))
          : encoder.encode(rgb, width, height, frameId);
        measured.times[frameIndex] = System.nanoTime() - startNanos;
        measured.cpu[frameIndex] = system.getProcessCpuTime() - cpuBefore;
        measured.allocated[frameIndex] = threadBean.getTotalThreadAllocatedBytes() - allocatedBefore;
        if (encoder.getStats().keyframe()) {
          measured.keyframes++;
        }
        measured.logical += data.length;
        final List<byte[]> pages = TransportPages.makePages(data, STREAM_ID);
        measured.wire += TransportPages.wireBytes(pages, false, TransportPages.PACKET_OVERHEAD);
        measured.zlib += zlibBytes(pages, deflater, buffer);
        final byte[] picture = decoded(data, predictFrom);
        final double mse = squaredError(rgb, picture) / (double) rgb.length;
        measured.psnrSum += mse == 0 ? EXACT_PSNR : psnr(mse);
        measured.mseSum += mse;
        if (out != null) {
          out.writeInt(Integer.reverseBytes(data.length));
          out.write(data);
        }
        if (decoded != null) {
          decoded.write(picture);
        }
      }
    }
    return measured;
  }

  private static byte[] decoded(final byte[] data, final byte[] predictFrom) throws Mcv2Exception {
    final Frame frame = Mcv2Decoder.parse(data);
    return Mcv2Decoder.decode(frame, predictFrom, frame.getReferenceId());
  }

  private static int sourceIndex(final int frame, final String loop, final int sourceFrames) {
    final int period = 2 * (sourceFrames - 1);
    return switch (loop) {
      case "pingpong" -> period == 0 ? 0 : frame % period < sourceFrames ? frame % period : period - (frame % period);
      case "wrap" -> frame % sourceFrames;
      default -> frame;
    };
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
      final int difference = (rgb[index] & 0xFF) - (picture[index] & 0xFF);
      sum += (long) difference * difference;
    }
    return sum;
  }

  private static double psnr(final double mse) {
    return 10 * Math.log10((MAX_SAMPLE * MAX_SAMPLE) / mse);
  }

  private static void report(
    final Measurement measured,
    final int frames,
    final int warm,
    final double frameRate,
    final int threads,
    final double encoderHeapMegabytes
  ) {
    final int from = Math.min(warm, frames - 1);
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
      Runtime.getRuntime().availableProcessors(),
      encoderHeapMegabytes,
      JSON.toJson(MCV2.describeNatives())
    );
  }

  private static double megabits(final long bytes, final double seconds) {
    return (bytes * Byte.SIZE) / seconds / BITS_PER_MEGABIT;
  }

  private static final int LENGTH_BYTES = 4;

  private static final String TRUNCATED = "truncated";

  private static final String REJECTED = "reject";

  /**
   * Prints each archive path followed by one lowercase RGB SHA-256 or {@code reject} token per frame. A missing length
   * prefix or payload emits {@code truncated} and ends that archive. {@code --rgb FILE} concatenates accepted pictures.
   */
  private static void digests(final String[] arguments) throws Exception {
    final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    final boolean rgb = arguments.length >= 2 && arguments[0].equals("--rgb");
    final List<String> archives = Arrays.asList(arguments).subList(rgb ? 2 : 0, arguments.length);
    try (
      OutputStream pictures = rgb ? new BufferedOutputStream(Files.newOutputStream(Path.of(arguments[1]))) : OutputStream.nullOutputStream()
    ) {
      for (final String argument : archives) {
        System.out.println(argument + digests(Files.readAllBytes(Path.of(argument)), sha256, pictures));
      }
    }
  }

  private static String digests(final byte[] archive, final MessageDigest sha256, final OutputStream pictures) throws IOException {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final StringBuilder line = new StringBuilder();
    int offset = 0;
    while (offset < archive.length) {
      final long length = archive.length - offset < LENGTH_BYTES ? -1 : Mcv2Decoder.u32(archive, offset);
      if (length < 0 || length > archive.length - offset - LENGTH_BYTES) {
        line.append(' ').append(TRUNCATED);
        break;
      }
      final byte[] frame = Arrays.copyOfRange(archive, offset + LENGTH_BYTES, offset + LENGTH_BYTES + (int) length);
      line.append(' ').append(token(receiver, frame, sha256, pictures));
      offset += LENGTH_BYTES + (int) length;
    }
    return line.toString();
  }

  private static String token(final Mcv2Receiver receiver, final byte[] frame, final MessageDigest sha256, final OutputStream pictures)
    throws IOException {
    final byte[] picture;
    try {
      picture = receiver.accept(frame);
    } catch (final Mcv2Exception rejected) {
      return REJECTED;
    }
    pictures.write(picture);
    return HexFormat.of().formatHex(sha256.digest(picture));
  }

  private static final int SOURCE_WIDTH = 1920;
  private static final int SOURCE_HEIGHT = 1080;
  private static final int CHANNELS = 3;
  private static final int MAX_ARCHIVE_BYTES = 1_000_000;
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  private static void generateFixtures(final String[] arguments) throws Exception {
    final Path sources = Path.of(arguments[0]);
    final Path fixtures = Path.of(arguments[1]);
    final Map<String, Object> digests = new LinkedHashMap<>();
    final Map<String, Object> pages = new LinkedHashMap<>();
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      generate(sources, fixtures, pool, "proxy-default.mcs", "proxy", Settings.DEFAULT, 320, 180, 8, digests, pages);
      generate(sources, fixtures, pool, "gameplay-default.mcs", "gameplay", Settings.DEFAULT, 320, 180, 8, digests, pages);
      generate(sources, fixtures, pool, "gameplay-fast.mcs", "gameplay", Settings.FAST, 320, 180, 8, digests, pages);
      generate(sources, fixtures, pool, "proxy-keyframes.mcs", "proxy", Settings.DEFAULT, 320, 180, 4, digests, pages);
      generate(sources, fixtures, pool, "proxy-odd.mcs", "proxy", Settings.DEFAULT, 319, 179, 6, digests, pages);
      golden(fixtures, pool, Settings.DEFAULT, "crop-default.mcs");
      golden(fixtures, pool, Settings.FAST, "crop-fast.mcs");
    }
    Files.writeString(fixtures.resolve("conformance/digests.json"), JSON.toJson(digests) + "\n");
    Files.writeString(fixtures.resolve("conformance/pages.json"), JSON.toJson(pages) + "\n");
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
    final MCV2 encoder = new MCV2(settings, pool, 1, true);
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    final List<String> hashes = new ArrayList<>();
    final byte[] rgb = new byte[width * height * CHANNELS];
    final String raw = source + "30_pp600_1920x1080.rgb";
    try (final RandomAccessFile input = new RandomAccessFile(sources.resolve(raw).toFile(), "r")) {
      for (int id = 0; id < count; id++) {
        crop(input, id, rgb, width, height);
        if (name.equals("proxy-keyframes.mcs")) {
          encoder.requestKeyframe();
        }
        final byte[] data = encoder.encode(rgb, width, height, id);
        if (archive.size() + Integer.BYTES + data.length > MAX_ARCHIVE_BYTES) {
          break;
        }
        frame(archive, data);
        final byte[] picture = receiver.accept(data);
        if (!Arrays.equals(picture, encoder.getReference())) {
          throw new IllegalStateException("Reference differs for " + name + " frame " + id);
        }
        hashes.add(Mcv2Resources.sha256(picture));
        if (name.equals("proxy-default.mcs") && id < 4) {
          final List<byte[]> symbols = TransportPages.makePages(data, 7);
          final List<String> pageHashes = new ArrayList<>();
          for (final byte[] page : symbols) {
            pageHashes.add(Mcv2Resources.sha256(page));
          }
          final Map<String, Object> expected = new LinkedHashMap<>();
          expected.put("pages", pageHashes);
          expected.put("wire", TransportPages.wireBytes(symbols, false, TransportPages.PACKET_OVERHEAD));
          expected.put("wire_full", TransportPages.wireBytes(symbols, true, TransportPages.PACKET_OVERHEAD));
          pages.put("conformance/" + name + "#" + id + "@6", expected);
        }
      }
    }
    Files.write(fixtures.resolve("conformance/" + name), archive.toByteArray());
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("source", raw);
    expected.put("crop", List.of((SOURCE_WIDTH - width) / 2, (SOURCE_HEIGHT - height) / 2, width, height));
    expected.put("sha256_per_frame", hashes);
    digests.put(name, expected);
    System.out.println(name + ": " + hashes.size() + " frames, " + archive.size() + " bytes");
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

  private static void golden(final Path fixtures, final ForkJoinPool pool, final Settings settings, final String name) throws IOException {
    final byte[] source = Files.readAllBytes(fixtures.resolve("encoder/crop-320x180x4.rgb"));
    final int bytes = 320 * 180 * CHANNELS;
    if (source.length != bytes * 4) {
      throw new IllegalArgumentException("Expected exactly four 320x180 frames");
    }
    final MCV2 encoder = new MCV2(settings, pool, 1, true);
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    for (int id = 0; id < 4; id++) {
      frame(archive, encoder.encode(Arrays.copyOfRange(source, id * bytes, (id + 1) * bytes), 320, 180, id));
    }
    Files.write(fixtures.resolve("encoder/" + name), archive.toByteArray());
    System.out.println(name + ": 4 frames, " + archive.size() + " bytes");
  }

  private static void frame(final ByteArrayOutputStream out, final byte[] data) {
    final byte[] length = new byte[Integer.BYTES];
    Mcv2Decoder.putU32(length, 0, data.length);
    out.writeBytes(length);
    out.writeBytes(data);
  }

  private static final int VULKAN_1_2 = (1 << 22) | (2 << 12);

  // the macros GlslCompiler defines for every shader on a device with a zero-to-one depth range; it also defines
  // RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE on devices with shader draw parameters, which no shader reads
  private static final Map<String, String> RENDERER_MACROS = Map.of("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");

  // what GlslCompiler defines on drivers that need depth invariance spelled out; only the transparency includes read it
  private static final String EXPLICIT_DEPTH_INVARIANCE = "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE";

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

  /**
   * Compiles Minecraft's shader variants through shaderc and SPIRV-Cross, returning the number of failed stages.
   * Sampler names stay unchanged so the Python GL harness can bind them; Minecraft renames them to names such as
   * {@code _uniform_00_03}. The command exits with this failure count.
   */
  private static int shaderCompile(final String[] arguments) throws IOException {
    final Path pack = Path.of(arguments[0]);
    final Path generated = Path.of(arguments[1]);
    final Path output = Files.createDirectories(Path.of(arguments[2]));
    final List<String> options = List.of(arguments).subList(3, arguments.length);
    final int vanillaOption = options.indexOf("--vanilla");
    final Path vanilla = vanillaOption < 0 ? null : Path.of(options.get(vanillaOption + 1));
    final boolean postOnly = options.contains("--post-only");
    int failures = 0;
    for (final Map.Entry<String, Map<String, String>> variant : postOnly
      ? Map.<String, Map<String, String>>of().entrySet()
      : TEXT_VARIANTS.entrySet()) {
      for (final String stage : List.of("vsh", "fsh")) {
        final Path source = pack.resolve("assets/minecraft/shaders/core/text." + stage);
        failures += compile(pack, generated, vanilla, source, stage, variant.getValue(), output.resolve(variant.getKey() + "." + stage));
      }
    }
    final Path post = pack.resolve("assets/mcav/shaders/post");
    try (final var files = Files.walk(post)) {
      for (final Path source : files.filter(Files::isRegularFile).sorted().toList()) {
        final String name = post.relativize(source).toString().replace(post.getFileSystem().getSeparator(), "_");
        final String stage = name.substring(name.lastIndexOf('.') + 1);
        failures += compile(pack, generated, vanilla, source, stage, Map.of(), output.resolve(name));
      }
    }
    System.out.println(failures == 0 ? "every stage compiled" : failures + " stages failed");
    return failures;
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
      final List<ByteBuffer> keep = new ArrayList<>();
      Shaderc.shaderc_compile_options_set_include_callbacks(
        options,
        ShadercIncludeResolve.create((user, requested, type, requesting, depth) ->
          include(pack, generated, vanilla, MemoryUtil.memUTF8(requested), keep)
        ),
        ShadercIncludeResultRelease.create((user, result) -> {}),
        0L
      );
      final int kind = stage.equals("vsh") ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
      final long result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, source.getFileName().toString(), "main", options);
      if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
        System.out.println("FAIL " + target.getFileName() + ": " + Shaderc.shaderc_result_get_error_message(result));
        Shaderc.shaderc_result_release(result);
        return 1;
      }
      final ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
      final String glsl = crossCompile(spirv, kind == Shaderc.shaderc_vertex_shader);
      Shaderc.shaderc_result_release(result);
      Files.writeString(target, glsl);
      System.out.println("ok   " + target.getFileName());
      return 0;
    } finally {
      Shaderc.shaderc_compile_options_release(options);
      Shaderc.shaderc_compiler_release(compiler);
    }
  }

  private static long include(
    final Path pack,
    final Path generated,
    final Path vanilla,
    final String requested,
    final List<ByteBuffer> keep
  ) {
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
      check(Spvc.spvc_context_create(pointer), "context");
      final long context = pointer.get(0);
      try {
        check(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, pointer), "parse");
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
        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, 330);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER, true);
        // the game sets this to whether the device has shader draw parameters; no shader here reads gl_InstanceIndex
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE, false);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLATTEN_MULTIDIMENSIONAL_ARRAYS, true);
        check(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "resources");
        final long resources = pointer.get(0);
        // without separate shader objects the stages link by name, so the game names every stage input and output
        // after its location
        final int attributes = vertex ? Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT : Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT;
        final int varyings = vertex ? Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT : Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT;
        renameByLocation(compiler, resources, attributes, vertex ? "_vert_input_%02d" : "_frag_output_%02d");
        renameByLocation(compiler, resources, varyings, "_interface_variable_%02d");
        Spvc.spvc_compiler_install_compiler_options(compiler, options);
        final PointerBuffer source = stack.callocPointer(1);
        check(Spvc.spvc_compiler_compile(compiler, source), "compile");
        return MemoryUtil.memUTF8(source.get(0));
      } finally {
        Spvc.spvc_context_destroy(context);
      }
    }
  }

  private static void renameByLocation(final long compiler, final long resources, final int type, final String format) {
    try (MemoryStack stack = stackPush()) {
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
      throw new IllegalStateException("SPIRV-Cross failed at " + step + ": " + result);
    }
  }
}

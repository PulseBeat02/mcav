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

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;

/**
 * The encoder benchmark behind every encode time in the MCV2 report: encodes the frames of a raw RGB source (row-major,
 * 8-bit RGB, frame after frame) with a profile and prints one JSON line - the time per frame after the warm-up frames
 * (mean, p50, p95, max), the process CPU time per frame, the bytes allocated per frame, keyframes, the map rate and the
 * zlib rate (every page deflated as a map packet), the PSNR of the pictures a client decodes (every frame goes through
 * mcav's decoder, from the reference it predicts from) - so the JIT is warm and the first frames are discarded, as
 * addendum 5 asks. It is not a build or CI task.
 *
 * <p>Compile with a JDK and run on the JVM to measure (the report used Temurin 25), for example:
 *
 * <pre>
 * javac -cp mcav-bukkit/build/libs/mcav-bukkit-*.jar -d build/bench tools/mcv2/Mcv2Bench.java
 * java -XX:ActiveProcessorCount=12 -Xmx3g -cp build/bench:mcav-bukkit/build/libs/mcav-bukkit-*.jar:guava.jar:slf4j-api.jar \
 *   Mcv2Bench source=proxy_1920x1080_60.rgb width=1920 height=1080 frames=660 warm=60 loop=pingpong fps=60 threads=12 \
 *   profile=DEFAULT budget=true verify=true
 * </pre>
 *
 * <p>Arguments, all {@code key=value}: {@code source}, {@code width}, {@code height}, {@code frames}, {@code warm}
 * (frames left out of the times), {@code loop} ({@code none}, {@code wrap} or {@code pingpong} over the source's
 * frames), {@code fps} (for the rates), {@code threads}, {@code profile} ({@code DEFAULT}, {@code FAST}),
 * {@code lambda}, {@code key} (1 requests a keyframe before every encode),
 * {@code budget} (encode inside a {@link Pool}, as a screen and a pre-encode do), {@code verify},
 * {@code framebudget} (milliseconds, see {@link MCV2#setFrameBudget}), {@code out} (write the archive)
 * and {@code decoded} (write the pictures).
 */
public final class Mcv2Bench {

  private static final double NANOS_PER_MILLISECOND = 1e6;

  private static final double BYTES_PER_MEGABYTE = 1048576.0;

  private static final double BITS_PER_MEGABIT = 1e6;

  private static final int DEFLATE_BUFFER = 1 << 16;

  private static final double MAX_SAMPLE = 255.0;

  /** The PSNR a frame that equals its source counts as. */
  private static final double EXACT_PSNR = 100.0;

  private static final double P95 = 0.95;

  /** The stream id the pages carry; any id costs the same bytes. */
  private static final int STREAM_ID = 1;

  private Mcv2Bench() {}

  /** What an encode of the source measured, frame by frame, and summed. */
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

  public static void main(final String[] args) throws Exception {
    final Map<String, String> options = arguments(args);
    final int width = Integer.parseInt(options.getOrDefault("width", "1920"));
    final int height = Integer.parseInt(options.getOrDefault("height", "1080"));
    final int frames = Integer.parseInt(options.getOrDefault("frames", "60"));
    final int threads = Integer.parseInt(options.getOrDefault("threads", "12"));
    final int warm = Integer.parseInt(options.getOrDefault("warm", "0"));
    final double fps = Double.parseDouble(options.getOrDefault("fps", "60"));
    final Path source = Path.of(options.get("source"));
    // the heap an encoder keeps: used heap after a full collection with the encoder alive, less before it existed
    System.gc();
    final long heapBefore = usedHeap();
    final Pool budget = new Pool(threads);
    final MCV2 encoder = budget.encoder(settings(options), Boolean.parseBoolean(options.getOrDefault("verify", "false")));
    if (options.containsKey("framebudget")) {
      encoder.setFrameBudget((long) (Double.parseDouble(options.get("framebudget")) * NANOS_PER_MILLISECOND));
    }
    final Measurement measured = encode(options, source, width, height, frames, budget, encoder);
    System.gc();
    final long heapAfter = usedHeap();
    Reference.reachabilityFence(encoder);
    budget.close();
    report(measured, frames, warm, fps, threads, (heapAfter - heapBefore) / BYTES_PER_MEGABYTE);
  }

  private static Map<String, String> arguments(final String[] args) {
    final Map<String, String> options = new HashMap<>();
    for (final String arg : args) {
      final int separator = arg.indexOf('=');
      options.put(arg.substring(0, separator), arg.substring(separator + 1));
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

  /** Encodes the frames, writing the archive and the pictures when asked, and measures every frame. */
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
      DataOutputStream out = options.containsKey("out") ? new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(options.get("out"))))) : null;
      OutputStream decoded = options.containsKey("decoded") ? new BufferedOutputStream(Files.newOutputStream(Path.of(options.get("decoded")))) : null
    ) {
      final byte[] rgb = new byte[(int) frameBytes];
      for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
        input.seek(sourceIndex(frameIndex, loop, sourceFrames) * frameBytes);
        input.readFully(rgb);
        final long cpuBefore = system.getProcessCpuTime();
        final long allocatedBefore = threadBean.getTotalThreadAllocatedBytes();
        final long startNanos = System.nanoTime();
        final long frameId = frameIndex;
        if (options.getOrDefault("key", "0").equals("1")) { encoder.requestKeyframe(); }
        final byte[] predictFrom = encoder.getReference();
        final byte[] data = inBudget ? budget.run(() -> encoder.encode(rgb, width, height, frameId)) : encoder.encode(rgb, width, height, frameId);
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

  /**
   * The picture a client decodes from a frame, which predicts from the picture the encoder held as its reference before
   * it: the immediately preceding reconstructed frame.
   */
  private static byte[] decoded(final byte[] data, final byte[] predictFrom) throws Mcv2Exception {
    final Frame frame = Mcv2Decoder.parse(data);
    return Mcv2Decoder.decode(frame, predictFrom, frame.getReferenceId());
  }

  /** The source frame of an encoded frame: the frames in order, over again, or forward and back. */
  private static int sourceIndex(final int frame, final String loop, final int sourceFrames) {
    final int period = 2 * (sourceFrames - 1);
    return switch (loop) {
      case "pingpong" -> period == 0 ? 0 : (frame % period < sourceFrames ? frame % period : period - frame % period);
      case "wrap" -> frame % sourceFrames;
      default -> frame;
    };
  }

  /** The bytes of a frame's map packets after the game's zlib, every page deflated as its own packet. */
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
    return 10 * Math.log10(MAX_SAMPLE * MAX_SAMPLE / mse);
  }

  /** Prints the measurement as one JSON line: times over the frames after the warm-up, rates over all of them. */
  private static void report(
    final Measurement measured,
    final int frames,
    final int warm,
    final double fps,
    final int threads,
    final double encoderHeapMegabytes
  ) {
    final int from = Math.min(warm, frames - 1);
    final long[] warmTimes = Arrays.copyOfRange(measured.times, from, frames);
    final long[] sorted = warmTimes.clone();
    Arrays.sort(sorted);
    final double seconds = frames / fps;
    System.out.printf(
      Locale.ROOT,
      "{\"frames\":%d,\"warm\":%d,\"keyframes\":%d,\"mean_ms\":%.3f,\"p50_ms\":%.3f,\"p95_ms\":%.3f,\"max_ms\":%.3f,\"cpu_ms\":%.3f," +
      "\"alloc_mb_per_frame\":%.2f,\"logical_mbps\":%.6f,\"map_mbps\":%.6f,\"zlib_mbps\":%.6f,\"psnr_mean\":%.4f,\"psnr_global\":%.4f," +
      "\"fps\":%.1f,\"threads\":%d,\"processors\":%d,\"encoder_heap_mb\":%.1f}%n",
      frames,
      warmTimes.length,
      measured.keyframes,
      Arrays.stream(warmTimes).average().orElse(0) / NANOS_PER_MILLISECOND,
      sorted[sorted.length / 2] / NANOS_PER_MILLISECOND,
      sorted[(int) Math.ceil(sorted.length * P95) - 1] / NANOS_PER_MILLISECOND,
      sorted[sorted.length - 1] / NANOS_PER_MILLISECOND,
      Arrays.stream(Arrays.copyOfRange(measured.cpu, from, frames)).average().orElse(0) / NANOS_PER_MILLISECOND,
      Arrays.stream(Arrays.copyOfRange(measured.allocated, from, frames)).average().orElse(0) / BYTES_PER_MEGABYTE,
      megabits(measured.logical, seconds),
      megabits(measured.wire, seconds),
      megabits(measured.zlib, seconds),
      measured.psnrSum / frames,
      psnr(measured.mseSum / frames),
      fps,
      threads,
      Runtime.getRuntime().availableProcessors(),
      encoderHeapMegabytes
    );
  }

  private static double megabits(final long bytes, final double seconds) {
    return bytes * Byte.SIZE / seconds / BITS_PER_MEGABIT;
  }

}

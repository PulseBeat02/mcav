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
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.media.mcv2.encode.LiveSearch;
import me.brandonli.mcav.media.mcv2.encode.Mcv2Encoder;
import me.brandonli.mcav.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.media.mcv2.transport.TransportPages;

/**
 * The encoder benchmark behind every encode time in the MCV2 report: encodes the frames of a raw RGB source (row-major,
 * 8-bit RGB, frame after frame) with a profile and prints one JSON line - the time per frame after the warm-up frames
 * (mean, p50, p95, max), the process CPU time per frame, the bytes allocated per frame, keyframes, the map rate and the
 * zlib rate (every page deflated as a map packet), the PSNR - so the JIT is warm and the first frames are discarded, as
 * addendum 5 asks. It is not a build or CI task.
 *
 * <p>Compile with a JDK and run on the JVM to measure (the report used Temurin 25), for example:
 *
 * <pre>
 * javac -cp mcav-common/build/libs/mcav-common-*.jar -d build/bench tools/mcv2/Mcv2Bench.java
 * java -XX:ActiveProcessorCount=12 -Xmx3g -cp build/bench:mcav-common/build/libs/mcav-common-*.jar:guava.jar:slf4j-api.jar \
 *   Mcv2Bench source=proxy_1920x1080_60.rgb width=1920 height=1080 frames=660 warm=60 loop=pingpong fps=60 threads=12 \
 *   profile=live budget=true verify=true
 * </pre>
 *
 * <p>Arguments, all {@code key=value}: {@code source}, {@code width}, {@code height}, {@code frames}, {@code warm} (frames
 * left out of the times), {@code loop} ({@code none}, {@code wrap} or {@code pingpong} over the source's frames),
 * {@code fps} (for the rates), {@code threads}, {@code profile} ({@code ship}, {@code low}, {@code live}), {@code lambda},
 * {@code key} (keyframe interval), {@code reference} ({@code previous} or {@code keyframe}), {@code budget} (encode inside
 * an {@link EncoderPool}, as an MCV2 screen and a pre-encode do), {@code verify}, {@code framebudget} (milliseconds, see
 * {@link Mcv2Encoder#setFrameBudget}), {@code out} (write the archive) and {@code decoded} (write the pictures). For a live
 * search other than the profile's: {@code search=custom} with {@code smallest}, {@code skip}, {@code split},
 * {@code steady}, {@code fine}, {@code good}, {@code gate}, {@code modes}, {@code smallmodes}, {@code keymodes},
 * {@code classes}, {@code q}, {@code seeded}, {@code searchblock}, {@code coarse} and {@code fast} (see {@link LiveSearch};
 * mode sets as comma-separated mode numbers, {@code all}, or {@code lambda} for the quantizer from lambda).
 */
public final class Mcv2Bench {

  private Mcv2Bench() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> a = new HashMap<>();
    for (final String arg : args) {
      final int i = arg.indexOf('=');
      a.put(arg.substring(0, i), arg.substring(i + 1));
    }
    final int w = Integer.parseInt(a.getOrDefault("width", "1920"));
    final int h = Integer.parseInt(a.getOrDefault("height", "1080"));
    final int frames = Integer.parseInt(a.getOrDefault("frames", "60"));
    final int threads = Integer.parseInt(a.getOrDefault("threads", "12"));
    final int warm = Integer.parseInt(a.getOrDefault("warm", "0"));
    final double fps = Double.parseDouble(a.getOrDefault("fps", "60"));
    final String loop = a.getOrDefault("loop", "none");
    EncoderSettings s = switch (a.getOrDefault("profile", "ship")) {
      case "low" -> EncoderSettings.LOW_BANDWIDTH;
      case "live" -> EncoderSettings.LIVE;
      default -> EncoderSettings.SHIP;
    };
    if (a.containsKey("lambda")) {
      s = s.withLambda(Double.parseDouble(a.get("lambda")));
    }
    if (a.containsKey("key")) {
      s = s.withKeyInterval(Integer.parseInt(a.get("key")));
    }
    if (a.getOrDefault("reference", "previous").equals("keyframe")) {
      s = s.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME);
    }
    if (a.getOrDefault("search", "profile").equals("custom")) {
      final int modes = parseSet(a.getOrDefault("modes", "all"), LiveSearch.ALL_MODES);
      s = s.withLive(
        new LiveSearch(
          Integer.parseInt(a.getOrDefault("smallest", "8")),
          Double.parseDouble(a.getOrDefault("skip", "26.5")),
          Double.parseDouble(a.getOrDefault("split", "52.5")),
          Double.parseDouble(a.getOrDefault("steady", a.getOrDefault("split", "52.5"))),
          Double.parseDouble(a.getOrDefault("fine", a.getOrDefault("split", "52.5"))),
          Double.parseDouble(a.getOrDefault("good", "0")),
          Double.parseDouble(a.getOrDefault("gate", "0")),
          modes,
          a.containsKey("smallmodes") ? parseSet(a.get("smallmodes"), LiveSearch.ALL_MODES) : modes,
          parseSet(a.getOrDefault("keymodes", "all"), LiveSearch.ALL_MODES),
          parseSet(a.getOrDefault("classes", "all"), LiveSearch.ALL_CLASSES),
          parseSet(a.getOrDefault("q", "all"), LiveSearch.ALL_QUANTIZERS),
          Boolean.parseBoolean(a.getOrDefault("seeded", "false")),
          Integer.parseInt(a.getOrDefault("searchblock", "8")),
          Boolean.parseBoolean(a.getOrDefault("coarse", "false")),
          Integer.parseInt(a.getOrDefault("fast", "0"))
        )
      );
    }
    final Path source = Path.of(a.get("source"));
    final long frameBytes = (long) w * h * 3;
    final int sourceFrames = (int) (Files.size(source) / frameBytes);
    // the heap an encoder keeps: used heap after a full collection with the encoder alive, less before it existed
    System.gc();
    final long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    final boolean verify = Boolean.parseBoolean(a.getOrDefault("verify", "false"));
    final EncoderPool budget = new EncoderPool(threads);
    final Mcv2Encoder encoder = budget.encoder(s, verify);
    if (a.containsKey("framebudget")) {
      encoder.setFrameBudget((long) (Double.parseDouble(a.get("framebudget")) * 1e6));
    }
    final boolean inBudget = Boolean.parseBoolean(a.getOrDefault("budget", "true"));
    final DataOutputStream out = a.containsKey("out")
      ? new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(a.get("out")))))
      : null;
    final OutputStream decoded = a.containsKey("decoded") ? new BufferedOutputStream(Files.newOutputStream(Path.of(a.get("decoded")))) : null;
    final long[] times = new long[frames];
    final long[] cpu = new long[frames];
    final long[] allocated = new long[frames];
    final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long logical = 0;
    long wire = 0;
    long zlib = 0;
    long keyframes = 0;
    double psnrSum = 0;
    double mseSum = 0;
    final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    final byte[] buffer = new byte[1 << 16];
    try (RandomAccessFile in = new RandomAccessFile(source.toFile(), "r")) {
      final byte[] rgb = new byte[(int) frameBytes];
      for (int i = 0; i < frames; i++) {
        final int period = 2 * (sourceFrames - 1);
        final int index = switch (loop) {
          case "pingpong" -> period == 0 ? 0 : (i % period < sourceFrames ? i % period : period - i % period);
          case "wrap" -> i % sourceFrames;
          default -> i;
        };
        in.seek(index * frameBytes);
        in.readFully(rgb);
        final long c0 = os.getProcessCpuTime();
        final long a0 = threadBean.getTotalThreadAllocatedBytes();
        final long t0 = System.nanoTime();
        final long frameId = i;
        final byte[] data = inBudget ? budget.run(() -> encoder.encode(rgb, w, h, frameId)) : encoder.encode(rgb, w, h, frameId);
        times[i] = System.nanoTime() - t0;
        cpu[i] = os.getProcessCpuTime() - c0;
        allocated[i] = threadBean.getTotalThreadAllocatedBytes() - a0;
        if (encoder.getStats().keyframe()) {
          keyframes++;
        }
        logical += data.length;
        final List<byte[]> pages = TransportPages.makePages(data, 1, 6);
        wire += TransportPages.wireBytes(pages, false, TransportPages.PACKET_OVERHEAD);
        for (final byte[] page : pages) {
          deflater.reset();
          deflater.setInput(MapAlphabet.toMapColors(page));
          deflater.finish();
          int n = 0;
          while (!deflater.finished()) {
            n += deflater.deflate(buffer);
          }
          zlib += n + TransportPages.PACKET_OVERHEAD;
        }
        final byte[] picture = encoder.getReference();
        long se = 0;
        for (int k = 0; k < rgb.length; k++) {
          final int d = (rgb[k] & 0xFF) - (picture[k] & 0xFF);
          se += (long) d * d;
        }
        final double mse = se / (double) rgb.length;
        psnrSum += mse == 0 ? 100.0 : 10 * Math.log10(255.0 * 255.0 / mse);
        mseSum += mse;
        if (out != null) {
          out.writeInt(Integer.reverseBytes(data.length));
          out.write(data);
        }
        if (decoded != null) {
          decoded.write(picture);
        }
      }
    }
    System.gc();
    final long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    Reference.reachabilityFence(encoder);
    if (out != null) {
      out.close();
    }
    if (decoded != null) {
      decoded.close();
    }
    budget.close();
    final int from = Math.min(warm, frames - 1);
    final long[] warmTimes = Arrays.copyOfRange(times, from, frames);
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
      keyframes,
      Arrays.stream(warmTimes).average().orElse(0) / 1e6,
      sorted[sorted.length / 2] / 1e6,
      sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1e6,
      sorted[sorted.length - 1] / 1e6,
      Arrays.stream(Arrays.copyOfRange(cpu, from, frames)).average().orElse(0) / 1e6,
      Arrays.stream(Arrays.copyOfRange(allocated, from, frames)).average().orElse(0) / 1048576.0,
      logical * 8 / seconds / 1e6,
      wire * 8 / seconds / 1e6,
      zlib * 8 / seconds / 1e6,
      psnrSum / frames,
      10 * Math.log10(255.0 * 255.0 / (mseSum / frames)),
      fps,
      threads,
      Runtime.getRuntime().availableProcessors(),
      (heapAfter - heapBefore) / 1048576.0
    );
  }

  private static int parseSet(final String text, final int all) {
    if (text.equals("all")) {
      return all;
    }
    if (text.equals("lambda")) {
      return LiveSearch.FROM_LAMBDA;
    }
    int set = 0;
    for (final String part : text.split(",")) {
      if (!part.isBlank()) {
        set |= 1 << Integer.parseInt(part.trim());
      }
    }
    return set;
  }
}

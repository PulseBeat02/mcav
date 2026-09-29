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
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import me.brandonli.mcav.bukkit.media.map.DeltaMapEncoder;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapRegion;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPaletteLoader;

/**
 * What mcav's dithered maps send, measured as {@code /mcav video map} sends it: every frame of a raw RGB source is
 * dithered into map colours at the wall's resolution, and {@code DeltaMapEncoder} (what {@code CompressedMapResult}
 * sends a viewer who watches from the start) picks the patches of the maps that changed, within its byte budget. It
 * prints one JSON line: the map rate (every patch's colours plus {@code MapLayout.PATCH_OVERHEAD}), the rate after
 * zlib (every patch's colours deflated at zlib's default level, as the game's packet compression does, plus the same
 * overhead), and the frames on which the viewer's maps did not show the whole current picture. The first {@code warm}
 * frames, while the wall fills, count for none of these. With {@code decoded=} it writes the pictures the viewer's maps
 * show after the warm-up, as RGB, and with {@code reference=} the source frames they show, for VMAF.
 *
 * <pre>
 * javac -cp CLASSPATH -d build/bench docs/mcv2/measure/DitherBench.java
 * java -cp build/bench:CLASSPATH DitherBench source=SRC.rgb width=1920 height=1080 frames=600 warm=120 fps=30 \
 *   loop=pingpong algorithm=filter_lite budget=131072 decoded=dithered.rgb reference=reference.rgb
 * </pre>
 *
 * <p>CLASSPATH holds mcav-bukkit, mcav-common and their dependencies with the JavaCV natives of the platform. The
 * algorithms are {@code filter_lite} (the recommended default) and {@code temporal} (temporal Floyd-Steinberg); a budget
 * of 0 sends every change. {@code loop=pingpong} plays the source's frames forward and back, as long as frames asks.
 */
public final class DitherBench {

  private static final double BITS_PER_MEGABIT = 1e6;

  private DitherBench() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> a = new HashMap<>();
    for (final String arg : args) {
      final int at = arg.indexOf('=');
      a.put(arg.substring(0, at), arg.substring(at + 1));
    }
    final int width = Integer.parseInt(a.getOrDefault("width", "1920"));
    final int height = Integer.parseInt(a.getOrDefault("height", "1080"));
    final int frames = Integer.parseInt(a.getOrDefault("frames", "30"));
    final int warm = Integer.parseInt(a.getOrDefault("warm", "0"));
    final boolean pingPong = "pingpong".equals(a.getOrDefault("loop", "none"));
    final double fps = Double.parseDouble(a.getOrDefault("fps", "30"));
    final int budget = Integer.parseInt(a.getOrDefault("budget", Integer.toString(DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME)));
    final String name = a.getOrDefault("algorithm", "filter_lite");
    final DitherAlgorithm algorithm = switch (name) {
      case "filter_lite" -> DitherAlgorithm.filterLite();
      case "temporal" -> DitherAlgorithm.temporalFloydSteinberg();
      default -> throw new IllegalArgumentException("Unknown algorithm: " + name);
    };
    final int columns = (width + MapLayout.MAP_SIZE - 1) / MapLayout.MAP_SIZE;
    final int rows = (height + MapLayout.MAP_SIZE - 1) / MapLayout.MAP_SIZE;
    final MapLayout layout = new MapLayout(0, columns, rows, width, height);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, budget == 0 ? Integer.MAX_VALUE : budget);
    final int[] palette = MapPaletteLoader.getColors();
    final byte[] shown = new byte[width * height];
    final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    final byte[] buffer = new byte[1 << 16];
    long mapBytes = 0;
    long zlibBytes = 0;
    long patches = 0;
    int incomplete = 0;
    final byte[] rgb = new byte[width * height * 3];
    final int[] argb = new int[width * height];
    try (
      RandomAccessFile in = new RandomAccessFile(a.get("source"), "r");
      OutputStream decoded = a.containsKey("decoded") ? new BufferedOutputStream(Files.newOutputStream(Path.of(a.get("decoded")))) : null;
      OutputStream reference = a.containsKey("reference") ? new BufferedOutputStream(Files.newOutputStream(Path.of(a.get("reference")))) : null
    ) {
      final int sourceFrames = (int) (in.length() / rgb.length);
      for (int frame = 0; frame < frames; frame++) {
        in.seek((long) sourceIndex(frame, pingPong, sourceFrames) * rgb.length);
        in.readFully(rgb);
        final boolean counted = frame >= warm;
        for (int i = 0; i < argb.length; i++) {
          argb[i] = 0xFF000000 | (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | (rgb[3 * i + 2] & 0xFF);
        }
        final byte[] colours;
        try (ImageBuffer image = ImageBuffer.buffer(argb, width, height)) {
          colours = algorithm.ditherIntoBytes(image);
        }
        final List<MapTilePatch> sent = encoder.encode(colours);
        for (final MapTilePatch patch : sent) {
          apply(layout, patch, shown, width);
          if (!counted) {
            continue;
          }
          patches++;
          mapBytes += patch.getEncodedSize();
          deflater.reset();
          deflater.setInput(patch.getColors());
          deflater.finish();
          int deflated = 0;
          while (!deflater.finished()) {
            deflated += deflater.deflate(buffer);
          }
          zlibBytes += deflated + MapLayout.PATCH_OVERHEAD;
        }
        if (!counted) {
          continue;
        }
        for (int i = 0; i < colours.length; i++) {
          if (colours[i] != shown[i]) {
            incomplete++;
            break;
          }
        }
        if (reference != null) {
          reference.write(rgb);
        }
        if (decoded != null) {
          for (int i = 0; i < shown.length; i++) {
            final int colour = palette[shown[i] & 0xFF];
            decoded.write(colour >> 16 & 0xFF);
            decoded.write(colour >> 8 & 0xFF);
            decoded.write(colour & 0xFF);
          }
        }
      }
    }
    final double seconds = (frames - warm) / fps;
    System.out.printf(
      Locale.ROOT,
      "{\"algorithm\":\"%s\",\"budget\":%d,\"frames\":%d,\"warm\":%d,\"fps\":%.3f,\"maps\":%d,\"patches\":%d,\"map_mbps\":%.6f,\"zlib_mbps\":%.6f,\"frames_not_complete\":%d}%n",
      name,
      budget,
      frames,
      warm,
      fps,
      layout.getMapCount(),
      patches,
      mapBytes * 8 / seconds / BITS_PER_MEGABIT,
      zlibBytes * 8 / seconds / BITS_PER_MEGABIT,
      incomplete
    );
  }

  /** The source frame of a frame: in order, or forward and back. */
  private static int sourceIndex(final int frame, final boolean pingPong, final int sourceFrames) {
    if (!pingPong || sourceFrames < 2) {
      return frame % sourceFrames;
    }
    final int period = 2 * (sourceFrames - 1);
    final int at = frame % period;
    return at < sourceFrames ? at : period - at;
  }

  /** Writes a patch into the picture the viewer's maps show. */
  private static void apply(final MapLayout layout, final MapTilePatch patch, final byte[] shown, final int width) {
    final MapRegion region = layout.getRegion(patch.getMapId());
    final int x0 = region.getSourceX() + patch.getX() - region.getLocalX();
    final int y0 = region.getSourceY() + patch.getY() - region.getLocalY();
    final byte[] colours = patch.getColors();
    for (int row = 0; row < patch.getHeight(); row++) {
      System.arraycopy(colours, row * patch.getWidth(), shown, (y0 + row) * width + x0, patch.getWidth());
    }
  }
}

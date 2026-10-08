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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Receiver;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;

/**
 * Regenerates the v3 conformance and encoder fixtures from the two read-only 1920x1080 RGB24 sources.
 * Compile with the bukkit classes, Guava, Checker qualifiers, Gson and SLF4J on the classpath, then run
 * {@code Mcv2GenerateFixtures <source-folder> <fixture-folder>}. The committed RGB crop is never changed.
 */
public final class Mcv2GenerateFixtures {

  private static final int SOURCE_WIDTH = 1920;
  private static final int SOURCE_HEIGHT = 1080;
  private static final int CHANNELS = 3;
  private static final int MAX_ARCHIVE_BYTES = 1_000_000;
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

  private Mcv2GenerateFixtures() {}

  public static void main(final String[] args) throws Exception {
    final Path sources = Path.of(args[0]);
    final Path fixtures = Path.of(args[1]);
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

  private static void generate(final Path sources, final Path fixtures, final ForkJoinPool pool, final String name,
    final String source, final Settings settings, final int width, final int height, final int count,
    final Map<String, Object> digests, final Map<String, Object> pages) throws Exception {
    final MCV2 encoder = new MCV2(settings, pool, 1, true);
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    final List<String> hashes = new ArrayList<>();
    final byte[] rgb = new byte[width * height * CHANNELS];
    final String raw = source + "30_pp600_1920x1080.rgb";
    try (final RandomAccessFile input = new RandomAccessFile(sources.resolve(raw).toFile(), "r")) {
      for (int id = 0; id < count; id++) {
        crop(input, id, rgb, width, height);
        if (name.equals("proxy-keyframes.mcs")) { encoder.requestKeyframe(); }
        final byte[] data = encoder.encode(rgb, width, height, id);
        if (archive.size() + Integer.BYTES + data.length > MAX_ARCHIVE_BYTES) { break; }
        frame(archive, data);
        final byte[] picture = receiver.accept(data);
        if (!Arrays.equals(picture, encoder.getReference())) { throw new IllegalStateException("Reference differs for " + name + " frame " + id); }
        hashes.add(sha256(picture));
        if (name.equals("proxy-default.mcs") && id < 4) {
          final List<byte[]> symbols = TransportPages.makePages(data, 7);
          final List<String> pageHashes = new ArrayList<>();
          for (final byte[] page : symbols) { pageHashes.add(sha256(page)); }
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

  private static void crop(final RandomAccessFile input, final int frame, final byte[] rgb, final int width, final int height) throws IOException {
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
    if (source.length != bytes * 4) { throw new IllegalArgumentException("Expected exactly four 320x180 frames"); }
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

  private static String sha256(final byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}

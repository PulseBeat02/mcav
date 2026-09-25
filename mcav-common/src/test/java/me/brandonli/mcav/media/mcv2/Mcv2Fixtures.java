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
package me.brandonli.mcav.media.mcv2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The committed MCV2 conformance fixtures: research archives (a little-endian u32 length before every frame) and the
 * per-frame SHA-256 digests of the reference decoder's RGB output, produced outside the tests by
 * {@code tools/mcv2/conformance_digests.py} and {@code tools/mcv2/edge_streams.py} with the Python reference.
 */
public final class Mcv2Fixtures {

  /** The resource folder of the fixtures. */
  public static final String ROOT = "/me/brandonli/mcav/media/mcv2/";

  private Mcv2Fixtures() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads a resource.
   *
   * @param path the path below {@link #ROOT}
   * @return the bytes
   */
  public static byte[] read(final String path) {
    try (final InputStream stream = Objects.requireNonNull(Mcv2Fixtures.class.getResourceAsStream(ROOT + path), path)) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Splits a research archive into its frames.
   *
   * @param archive the archive
   * @return the frames in order
   */
  public static List<byte[]> frames(final byte[] archive) {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < archive.length) {
      final int length = (int) Mcv2Format.u32(archive, offset);
      frames.add(Arrays.copyOfRange(archive, offset + 4, offset + 4 + length));
      offset += 4 + length;
    }
    return frames;
  }

  /**
   * Reads the per-frame digests of a fixture folder.
   *
   * @param folder {@code conformance} or {@code edge}
   * @return the digests of every stream, by file name
   */
  public static Map<String, List<String>> digests(final String folder) {
    final String text = new String(read(folder + "/digests.json"), StandardCharsets.UTF_8);
    final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
    final Map<String, List<String>> digests = new TreeMap<>();
    for (final Map.Entry<String, JsonElement> entry : root.entrySet()) {
      final JsonElement value = entry.getValue();
      final JsonElement list = value.isJsonObject() ? value.getAsJsonObject().get("sha256_per_frame") : value;
      final List<String> frames = new ArrayList<>();
      for (final JsonElement digest : list.getAsJsonArray()) {
        frames.add(digest.getAsString());
      }
      digests.put(entry.getKey(), frames);
    }
    return digests;
  }

  /**
   * Hashes a picture.
   *
   * @param bytes the bytes
   * @return the lowercase hexadecimal SHA-256
   */
  public static String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}

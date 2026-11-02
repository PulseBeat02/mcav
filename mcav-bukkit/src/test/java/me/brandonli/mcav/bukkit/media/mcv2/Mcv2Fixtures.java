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
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.Nullable;

// made by mcv2_tools.py fixtures and edge_streams with the Python reference
public final class Mcv2Fixtures {

  private static final String DIGESTS_FILE = "/digests.json";

  private static final String ROOT = "/mcv2/";

  private Mcv2Fixtures() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static byte[] read(final String path) {
    final String resource = "%s%s".formatted(ROOT, path);
    final String missingMessage = "Fixture %s is missing!".formatted(path);
    final @Nullable InputStream resourceStream = Mcv2Fixtures.class.getResourceAsStream(resource);
    try (final InputStream stream = requireNonNull(resourceStream, missingMessage)) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      final String message = "Fixture %s could not be read!".formatted(path);
      throw new UncheckedIOException(message, exception);
    }
  }

  public static List<byte[]> splitFrames(final byte[] archive) {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < archive.length) {
      final int length = (int) Mcv2Decoder.readUnsignedInt(archive, offset);
      frames.add(Arrays.copyOfRange(archive, offset + 4, offset + 4 + length));
      offset += 4 + length;
    }

    return frames;
  }

  static Map<String, List<String>> readDigests(final String folder) {
    final String text = new String(read(folder + DIGESTS_FILE), StandardCharsets.UTF_8);
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

  public static String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}

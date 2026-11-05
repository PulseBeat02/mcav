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

  private static final String RESOURCE_PATH_FORMAT = "%s%s";

  private static final String DIGESTS_FILE = "/digests.json";
  private static final String ROOT = "/mcv2/";

  static final String FRAME_DIGESTS_KEY = "sha256_per_frame";

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private static final int LENGTH_PREFIX_BYTES = Integer.BYTES;

  private Mcv2Fixtures() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static byte[] read(final String path) {
    final String resource = RESOURCE_PATH_FORMAT.formatted(ROOT, path);
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
      final int start = offset + LENGTH_PREFIX_BYTES;
      final int end = start + length;
      final byte[] frame = Arrays.copyOfRange(archive, start, end);
      frames.add(frame);
      offset = end;
    }

    return frames;
  }

  static Map<String, List<String>> readDigests(final String folder) {
    final byte[] bytes = read(RESOURCE_PATH_FORMAT.formatted(folder, DIGESTS_FILE));
    final String text = new String(bytes, StandardCharsets.UTF_8);
    final JsonElement parsed = JsonParser.parseString(text);
    final JsonObject root = parsed.getAsJsonObject();
    final Map<String, List<String>> digests = new TreeMap<>();
    for (final Map.Entry<String, JsonElement> entry : root.entrySet()) {
      final JsonElement value = entry.getValue();
      final JsonElement list = findFrameDigests(value);
      final List<String> frames = new ArrayList<>();
      for (final JsonElement digest : list.getAsJsonArray()) {
        frames.add(digest.getAsString());
      }

      digests.put(entry.getKey(), frames);
    }

    return digests;
  }

  private static JsonElement findFrameDigests(final JsonElement value) {
    if (!value.isJsonObject()) {
      return value;
    }

    final JsonObject object = value.getAsJsonObject();
    return object.get(FRAME_DIGESTS_KEY);
  }

  public static String sha256(final byte[] bytes) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
      final byte[] hash = digest.digest(bytes);
      final HexFormat hexadecimalFormat = HexFormat.of();
      return hexadecimalFormat.formatHex(hash);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}

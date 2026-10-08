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
package me.brandonli.mcav.bukkit.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the paths and names that go into a resource pack: sound keys, locations of extra files and the description.
 * Whatever they are, the builder either refuses them with an {@link IllegalArgumentException} or writes a pack whose
 * entries all lie below its root, with no empty, dot or parent segment, and whose generated JSON holds exactly the
 * description and the sounds that were accepted. The seeds are the keys and paths of the tests of the builder.
 */
@Tag("fuzz")
final class ResourcePackFuzzTest {

  private static final Path WORK = createWorkFolder();
  private static final Path SOUND = writeFile("sound.ogg", "OggS");
  private static final Path TEXT = writeFile("credits.txt", "credits");

  private static Path createWorkFolder() {
    try {
      final Path folder = Files.createTempDirectory("mcav-pack-paths-fuzz");
      folder.toFile().deleteOnExit();
      return folder;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static Path writeFile(final String name, final String content) {
    try {
      final Path file = WORK.resolve(name);
      Files.writeString(file, content);
      file.toFile().deleteOnExit();
      return file;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @FuzzTest(maxDuration = "30s")
  void writesOnlySafeEntriesAndExactlyTheAcceptedData(final FuzzedDataProvider data) throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final String description = data.consumeString(64);
    pack.meta(data.consumeInt(1, 100), description);
    final List<String> acceptedSounds = new ArrayList<>();
    final int additions = data.consumeInt(0, 4);
    for (int addition = 0; addition < additions; addition++) {
      final boolean sound = data.consumeBoolean();
      final String name = data.consumeString(48);
      try {
        if (sound) {
          pack.sound(name, SOUND);
          acceptedSounds.add(name);
        } else {
          pack.external(name, TEXT);
        }
      } catch (final IllegalArgumentException refused) {
        // refusing is always allowed; what was accepted is checked below
      }
    }

    final Path zipped = Files.createTempFile(WORK, "pack", ".zip");
    try {
      pack.zip(zipped);
      try (final ZipFile zip = new ZipFile(zipped.toFile(), StandardCharsets.UTF_8)) {
        assertSafeEntries(zip);
        assertMeta(zip, description);
        assertSounds(zip, acceptedSounds);
      }
    } finally {
      Files.deleteIfExists(zipped);
    }
  }

  private static void assertSafeEntries(final ZipFile zip) {
    final Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      final ZipEntry entry = entries.nextElement();
      final String name = entry.getName();
      assertFalse(name.startsWith("/"), () -> "an absolute entry " + name);
      assertFalse(name.contains("\\"), () -> "an entry with a backslash " + name);
      for (final String segment : name.split("/", -1)) {
        final boolean unsafe = segment.isEmpty() || segment.equals(".") || segment.equals("..");
        assertFalse(unsafe, () -> "an entry with an empty, dot or parent segment " + name);
      }
    }
  }

  private static void assertMeta(final ZipFile zip, final String description) throws IOException {
    final JsonObject meta = readJson(zip, "pack.mcmeta");
    final JsonObject pack = meta.getAsJsonObject("pack");
    final JsonElement written = pack.get("description");
    final String writtenDescription = written.getAsString();
    assertEquals(description, writtenDescription, "the description survives the JSON unchanged");
  }

  private static void assertSounds(final ZipFile zip, final List<String> acceptedSounds) throws IOException {
    for (final String key : acceptedSounds) {
      final int separator = key.indexOf(':');
      final String namespace = key.substring(0, separator);
      final String value = key.substring(separator + 1);
      final JsonObject sounds = readJson(zip, "assets/" + namespace + "/sounds.json");
      final boolean registered = sounds.has(value);
      assertTrue(registered, () -> "the sound " + key + " is not registered");
      final ZipEntry file = zip.getEntry("assets/" + namespace + "/sounds/" + value + ".ogg");
      assertNotNull(file, () -> "the sound " + key + " has no file");
    }
  }

  private static JsonObject readJson(final ZipFile zip, final String name) throws IOException {
    final ZipEntry entry = zip.getEntry(name);
    assertNotNull(entry, () -> "the pack has no " + name);
    try (final InputStream stream = zip.getInputStream(entry)) {
      final byte[] bytes = stream.readAllBytes();
      final String json = new String(bytes, StandardCharsets.UTF_8);
      final JsonElement parsed = JsonParser.parseString(json);
      return parsed.getAsJsonObject();
    }
  }
}

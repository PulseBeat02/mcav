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

@Tag("fuzz")
final class ResourcePackFuzzTest {

  private static final Path WORK = createWorkFolder();
  private static final Path SOUND = writeFile("sound.ogg", "OggS");
  private static final Path TEXT = writeFile("credits.txt", "credits");

  private static final String PATH_SEPARATOR = "/";
  private static final String ZIP_EXTENSION = ".zip";
  private static final String SOUND_EXTENSION = ".ogg";

  private static final String ASSETS_FOLDER = "assets/";
  private static final String SOUNDS_FILE = "/sounds.json";
  private static final String SOUNDS_FOLDER = "/sounds/";

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
  void shouldWriteOnlySafeEntriesAndExactlyTheAcceptedData(final FuzzedDataProvider data) throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final String description = data.consumeString(64);
    pack.metadata(data.consumeInt(1, 100), description);
    final List<String> acceptedSounds = new ArrayList<>();
    final int additions = data.consumeInt(0, 4);
    for (int addition = 0; addition < additions; addition++) {
      final EntryKind kind = data.consumeBoolean() ? EntryKind.SOUND : EntryKind.EXTERNAL;
      final String name = data.consumeString(48);
      try {
        this.addEntry(pack, kind, name, acceptedSounds);
      } catch (final IllegalArgumentException exception) {
        // refusing is always allowed; what was accepted is checked below
      }
    }

    final Path zipped = Files.createTempFile(WORK, "pack", ZIP_EXTENSION);
    try {
      pack.zip(zipped);
      try (final ZipFile zip = new ZipFile(zipped.toFile(), StandardCharsets.UTF_8)) {
        this.assertSafeEntries(zip);
        this.assertMeta(zip, description);
        this.assertSounds(zip, acceptedSounds);
      }
    } finally {
      Files.deleteIfExists(zipped);
    }
  }

  private void addEntry(final SimpleResourcePack pack, final EntryKind kind, final String name, final List<String> acceptedSounds) {
    if (kind == EntryKind.EXTERNAL) {
      pack.external(name, TEXT);
      return;
    }

    pack.sound(name, SOUND);
    acceptedSounds.add(name);
  }

  private void assertSafeEntries(final ZipFile zip) {
    final Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      final ZipEntry entry = entries.nextElement();
      final String name = entry.getName();
      final boolean absolute = name.startsWith(PATH_SEPARATOR);
      assertFalse(absolute, () -> "an absolute entry " + name);
      final boolean hasBackslash = name.contains("\\");
      assertFalse(hasBackslash, () -> "an entry with a backslash " + name);
      for (final String segment : name.split(PATH_SEPARATOR, -1)) {
        final boolean unsafe = segment.isEmpty() || segment.equals(".") || segment.equals("..");
        assertFalse(unsafe, () -> "an entry with an empty, dot or parent segment " + name);
      }
    }
  }

  private void assertMeta(final ZipFile zip, final String description) throws IOException {
    final JsonObject meta = this.readJson(zip, "pack.mcmeta");
    final JsonObject pack = meta.getAsJsonObject("pack");
    final JsonElement written = pack.get("description");
    final String writtenDescription = written.getAsString();
    assertEquals(description, writtenDescription, "the description survives the JSON unchanged");
  }

  private void assertSounds(final ZipFile zip, final List<String> acceptedSounds) throws IOException {
    for (final String key : acceptedSounds) {
      final int separator = key.indexOf(':');
      final String namespace = key.substring(0, separator);
      final String value = key.substring(separator + 1);
      final JsonObject sounds = this.readJson(zip, ASSETS_FOLDER + namespace + SOUNDS_FILE);
      final boolean registered = sounds.has(value);
      assertTrue(registered, () -> "the sound " + key + " is not registered");

      final ZipEntry file = zip.getEntry(ASSETS_FOLDER + namespace + SOUNDS_FOLDER + value + SOUND_EXTENSION);
      assertNotNull(file, () -> "the sound " + key + " has no file");
    }
  }

  private JsonObject readJson(final ZipFile zip, final String name) throws IOException {
    final ZipEntry entry = zip.getEntry(name);
    assertNotNull(entry, () -> "the pack has no " + name);

    try (final InputStream stream = zip.getInputStream(entry)) {
      final byte[] bytes = stream.readAllBytes();
      final String json = new String(bytes, StandardCharsets.UTF_8);
      final JsonElement parsed = JsonParser.parseString(json);
      return parsed.getAsJsonObject();
    }
  }

  private enum EntryKind {
    EXTERNAL,
    SOUND,
  }
}

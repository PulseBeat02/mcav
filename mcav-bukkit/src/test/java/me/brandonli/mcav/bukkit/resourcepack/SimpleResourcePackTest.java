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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link SimpleResourcePack}.
 */
final class SimpleResourcePackTest {

  private static final byte[] FIRST_SOUND = "first sound".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SECOND_SOUND = "second sound".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CREDITS = "credits".getBytes(StandardCharsets.US_ASCII);
  private static final String DESCRIPTION = "MCAV <audio> & \"more\"";
  private static final List<CopyOption> ATOMIC_MOVE = List.of(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  private static final List<CopyOption> REGULAR_MOVE = List.of(StandardCopyOption.REPLACE_EXISTING);

  @TempDir
  private Path directory;

  private Path firstSound;
  private Path secondSound;
  private Path credits;

  @BeforeEach
  void writeFiles() throws IOException {
    this.firstSound = this.directory.resolve("first.ogg");
    this.secondSound = this.directory.resolve("second.ogg");
    this.credits = this.directory.resolve("credits.txt");
    Files.write(this.firstSound, FIRST_SOUND);
    Files.write(this.secondSound, SECOND_SOUND);
    Files.write(this.credits, CREDITS);
  }

  private static Map<String, byte[]> readZip(final Path zip) throws IOException {
    final Map<String, byte[]> entries = new LinkedHashMap<>();
    try (final InputStream input = Files.newInputStream(zip); final ZipInputStream zipInput = new ZipInputStream(input)) {
      ZipEntry entry = zipInput.getNextEntry();
      while (entry != null) {
        final String name = entry.getName();
        final byte[] content = zipInput.readAllBytes();
        entries.put(name, content);
        entry = zipInput.getNextEntry();
      }
    }
    return entries;
  }

  private static List<String> entryNames(final Map<String, byte[]> entries) {
    final Set<String> names = entries.keySet();
    return List.copyOf(names);
  }

  private static JsonObject parseEntry(final Map<String, byte[]> entries, final String name) {
    final byte[] content = entries.get(name);
    final String text = new String(content, StandardCharsets.UTF_8);
    final JsonElement element = JsonParser.parseString(text);
    return element.getAsJsonObject();
  }

  private static JsonObject soundEvent(final String name) {
    final JsonObject sound = new JsonObject();
    sound.addProperty("name", name);
    sound.addProperty("type", "file");
    sound.addProperty("stream", true);
    final JsonArray sounds = new JsonArray();
    sounds.add(sound);
    final JsonObject event = new JsonObject();
    event.addProperty("replace", false);
    event.add("sounds", sounds);
    return event;
  }

  private static void addSoundEvent(final JsonObject events, final String value, final String name) {
    final JsonObject event = soundEvent(name);
    events.add(value, event);
  }

  private static List<Path> listFiles(final Path folder) throws IOException {
    try (final Stream<Path> files = Files.list(folder)) {
      return files.toList();
    }
  }

  private static void assertEntry(final byte[] expected, final Map<String, byte[]> entries, final String name) {
    final byte[] actual = entries.get(name);
    assertArrayEquals(expected, actual, name);
  }

  private static SimpleResourcePack.FileMover recordingMover(final List<List<CopyOption>> moves, final boolean atomicSupported) {
    return (from, to, options) -> {
      final List<CopyOption> optionList = List.of(options);
      moves.add(optionList);
      if (!atomicSupported && optionList.contains(StandardCopyOption.ATOMIC_MOVE)) {
        final String fromText = from.toString();
        final String toText = to.toString();
        throw new AtomicMoveNotSupportedException(fromText, toText, "network share");
      }
      Files.move(from, to, options);
    };
  }

  private Path zipFullPack() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(46, DESCRIPTION);
    pack.sound("mcav:audio", this.firstSound);
    pack.sound("mcav:music/intro", this.secondSound);
    pack.sound("other:beep", this.firstSound);
    pack.external("assets/mcav/texts/credits.txt", this.credits);

    final Path output = this.directory.resolve("output");
    final Path zip = output.resolve("pack.zip");
    pack.zip(zip);
    return zip;
  }

  private static void assertMetadata(final Map<String, byte[]> entries) {
    final JsonObject expectedPack = new JsonObject();
    expectedPack.addProperty("description", DESCRIPTION);
    expectedPack.addProperty("pack_format", 46);
    expectedPack.addProperty("min_format", 46);
    expectedPack.addProperty("max_format", 46);
    final JsonObject expectedMeta = new JsonObject();
    expectedMeta.add("pack", expectedPack);

    final JsonObject meta = parseEntry(entries, "pack.mcmeta");
    final byte[] metaBytes = entries.get("pack.mcmeta");
    final String metaText = new String(metaBytes, StandardCharsets.UTF_8);
    final boolean readableDescription = metaText.contains("MCAV <audio> &");
    assertEquals(expectedMeta, meta);
    assertTrue(readableDescription, "the description is not HTML escaped");
  }

  private static void assertSoundEvents(final Map<String, byte[]> entries) {
    final JsonObject expectedMcavSounds = new JsonObject();
    addSoundEvent(expectedMcavSounds, "audio", "mcav:audio");
    addSoundEvent(expectedMcavSounds, "music/intro", "mcav:music/intro");
    final JsonObject expectedOtherSounds = new JsonObject();
    addSoundEvent(expectedOtherSounds, "beep", "other:beep");

    final JsonObject mcavSounds = parseEntry(entries, "assets/mcav/sounds.json");
    final JsonObject otherSounds = parseEntry(entries, "assets/other/sounds.json");
    assertEquals(expectedMcavSounds, mcavSounds);
    assertEquals(expectedOtherSounds, otherSounds);
  }

  private static void assertReadableByEveryone(final Path zip) throws IOException {
    final PosixFileAttributeView permissionView = Files.getFileAttributeView(zip, PosixFileAttributeView.class);
    if (permissionView == null) {
      return;
    }

    final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(zip);
    final Set<PosixFilePermission> readable = PosixFilePermissions.fromString("rw-r--r--");
    assertEquals(readable, permissions, "a web server running as another user can read the pack");
  }

  @Test
  void packagesTheMetadataSoundsAndFilesIntoAZip() throws IOException {
    final List<String> expectedNames = List.of(
      "pack.mcmeta",
      "assets/mcav/sounds/audio.ogg",
      "assets/mcav/sounds/music/intro.ogg",
      "assets/mcav/sounds.json",
      "assets/other/sounds/beep.ogg",
      "assets/other/sounds.json",
      "assets/mcav/texts/credits.txt"
    );

    final Path zip = this.zipFullPack();

    final Map<String, byte[]> entries = readZip(zip);
    final List<String> names = entryNames(entries);
    final Path output = zip.getParent();
    final List<Path> outputFiles = listFiles(output);
    final List<Path> onlyTheZip = List.of(zip);
    assertEquals(expectedNames, names);
    assertMetadata(entries);
    assertSoundEvents(entries);
    assertEntry(FIRST_SOUND, entries, "assets/mcav/sounds/audio.ogg");
    assertEntry(SECOND_SOUND, entries, "assets/mcav/sounds/music/intro.ogg");
    assertEntry(FIRST_SOUND, entries, "assets/other/sounds/beep.ogg");
    assertEntry(CREDITS, entries, "assets/mcav/texts/credits.txt");
    assertEquals(onlyTheZip, outputFiles, "no temporary file is left behind");
    assertReadableByEveryone(zip);
  }

  @Test
  void packagesGeneratedContentAndKeepsTheLastSourceOfALocation() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(88, "generated");
    final byte[] shader = "#version 330".getBytes(StandardCharsets.US_ASCII);
    pack.data("assets/mcav/shaders/post/a.fsh", shader);
    shader[0] = 'X';
    pack.data("assets/mcav/texts/credits.txt", new byte[] { 1 });
    pack.external("assets/mcav/texts/credits.txt", this.credits);
    pack.external("assets/mcav/texts/replaced.txt", this.credits);
    pack.data("assets/mcav/texts/replaced.txt", new byte[] { 2 });
    final Path zip = this.directory.resolve("generated.zip");
    pack.zip(zip);

    final Map<String, byte[]> entries = readZip(zip);
    assertEquals(
      List.of("pack.mcmeta", "assets/mcav/texts/credits.txt", "assets/mcav/shaders/post/a.fsh", "assets/mcav/texts/replaced.txt"),
      entryNames(entries)
    );
    assertEntry("#version 330".getBytes(StandardCharsets.US_ASCII), entries, "assets/mcav/shaders/post/a.fsh");
    assertEntry(CREDITS, entries, "assets/mcav/texts/credits.txt");
    assertEntry(new byte[] { 2 }, entries, "assets/mcav/texts/replaced.txt");
    assertThrows(IllegalArgumentException.class, () -> pack.data("pack.mcmeta", new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> pack.data("assets/../pack.mcmeta", new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> pack.data("/assets/a.txt", new byte[0]));
  }

  @Test
  void zipsTheSamePackToTheSameBytes() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(88, "stable");
    pack.sound("mcav:first", this.firstSound);
    pack.external("assets/mcav/texts/credits.txt", this.credits);
    pack.data("assets/mcav/shaders/post/a.fsh", "#version 330".getBytes(StandardCharsets.US_ASCII));
    final Path first = this.directory.resolve("first.zip");
    final Path second = this.directory.resolve("second.zip");
    pack.zip(first);
    pack.zip(second);
    assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    final LocalDateTime fixed = LocalDateTime.of(1980, 2, 1, 0, 0);
    try (final InputStream input = Files.newInputStream(first); final ZipInputStream zip = new ZipInputStream(input)) {
      int entries = 0;
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        assertEquals(fixed, entry.getTimeLocal(), entry.getName());
        entries++;
      }
      assertEquals(5, entries);
    }
    assertEquals(fixed, SimpleResourcePack.entry("a.txt").getTimeLocal());
  }

  @Test
  void rejectsFileLocationsThatAreNotRelativeFiles() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();

    final IllegalArgumentException absolute = assertThrows(IllegalArgumentException.class, () ->
      pack.external("/assets/credits.txt", this.credits)
    );

    final String message = absolute.getMessage();
    assertEquals("Pack path must be relative to the pack root and name a file: /assets/credits.txt", message);
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/credits/", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets//credits.txt", this.credits));
  }

  @Test
  void rejectsFileLocationsReservedForGeneratedEntries() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path zip = this.directory.resolve("pack.zip");
    final List<String> expectedNames = List.of("pack.mcmeta", "pack.png", "assets/mcav/sounds/readme.txt", "assets/mcav/sounds.json.txt");

    final IllegalArgumentException meta = assertThrows(IllegalArgumentException.class, () -> pack.external("pack.mcmeta", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/mcav/sounds.json", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/mcav/sounds/audio.ogg", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/other/sounds/music/intro.ogg", this.credits));
    pack.external("pack.png", this.credits);
    pack.external("assets/mcav/sounds/readme.txt", this.credits);
    pack.external("assets/mcav/sounds.json.txt", this.credits);
    pack.meta(1, "pack");
    pack.zip(zip);

    final String message = meta.getMessage();
    final Map<String, byte[]> entries = readZip(zip);
    final List<String> names = entryNames(entries);
    assertEquals("Pack path is reserved for entries generated by the builder: pack.mcmeta", message);
    assertEquals(expectedNames, names);
  }

  @Test
  void makesFilesReadableByEveryoneOnlyOnPosixFileSystems() throws IOException {
    final PosixFileAttributeView view = mock(PosixFileAttributeView.class);
    final Set<PosixFilePermission> readable = PosixFilePermissions.fromString("rw-r--r--");

    SimpleResourcePack.makeReadable(view);

    verify(view).setPermissions(readable);
    assertDoesNotThrow(() -> SimpleResourcePack.makeReadable(null), "Windows has no POSIX permissions to change");
  }

  @Test
  void movesPacksIntoPlaceAtomicallyWhereSupported() throws IOException {
    final Path source = this.directory.resolve("source.zip");
    final Path target = this.directory.resolve("target.zip");
    Files.write(source, CREDITS);
    final List<List<CopyOption>> moves = new ArrayList<>();
    final SimpleResourcePack.FileMover mover = recordingMover(moves, true);

    SimpleResourcePack.moveIntoPlace(source, target, mover);

    final byte[] moved = Files.readAllBytes(target);
    final List<List<CopyOption>> expectedMoves = List.of(ATOMIC_MOVE);
    assertEquals(expectedMoves, moves);
    assertArrayEquals(CREDITS, moved);
  }

  @Test
  void fallsBackToARegularMoveWhereAtomicMovesAreNotSupported() throws IOException {
    final Path source = this.directory.resolve("source.zip");
    final Path target = this.directory.resolve("target.zip");
    Files.write(source, CREDITS);
    Files.write(target, FIRST_SOUND);
    final List<List<CopyOption>> moves = new ArrayList<>();
    final SimpleResourcePack.FileMover mover = recordingMover(moves, false);

    SimpleResourcePack.moveIntoPlace(source, target, mover);

    final byte[] moved = Files.readAllBytes(target);
    final boolean sourceExists = Files.exists(source);
    final List<List<CopyOption>> expectedMoves = List.of(ATOMIC_MOVE, REGULAR_MOVE);
    assertEquals(expectedMoves, moves);
    assertArrayEquals(CREDITS, moved, "the existing pack is replaced");
    assertFalse(sourceExists);
  }

  @Test
  void replacesSoundsWithTheSameKeyAndPacksThatAlreadyExist() throws IOException {
    final Path zip = this.directory.resolve("pack.zip");
    final SimpleResourcePack first = SimpleResourcePack.pack();
    final SimpleResourcePack second = SimpleResourcePack.pack();

    first.meta(1, "first");
    first.zip(zip);
    second.meta(2, "second");
    second.sound("mcav:audio", this.firstSound);
    second.sound("mcav:audio", this.secondSound);
    second.zip(zip);

    final Map<String, byte[]> entries = readZip(zip);
    final JsonObject meta = parseEntry(entries, "pack.mcmeta");
    final JsonObject packObject = meta.getAsJsonObject("pack");
    final JsonElement descriptionElement = packObject.get("description");
    final String description = descriptionElement.getAsString();
    final JsonObject sounds = parseEntry(entries, "assets/mcav/sounds.json");
    final int eventCount = sounds.size();
    assertEquals("second", description);
    assertEquals(1, eventCount);
    assertEntry(SECOND_SOUND, entries, "assets/mcav/sounds/audio.ogg");
  }

  @Test
  void createsIndependentBuilders() {
    final SimpleResourcePack first = SimpleResourcePack.pack();
    final SimpleResourcePack second = SimpleResourcePack.pack();

    assertNotSame(first, second);
  }

  @Test
  void needsMetadataBeforeZipping() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path zip = this.directory.resolve("pack.zip");

    assertThrows(IllegalStateException.class, () -> pack.zip(zip));

    final boolean exists = Files.exists(zip);
    assertFalse(exists);
    assertThrows(NullPointerException.class, () -> pack.zip(null));
  }

  @Test
  void rejectsTraversalAndAmbiguousSoundPaths() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final List<String> invalid = List.of(
      "mcav:../../../../outside",
      "..:audio",
      ".:audio",
      "mcav:.",
      "mcav:..",
      "mcav:music/../audio",
      "mcav:music/./audio",
      "mcav:/audio",
      "mcav:music//audio",
      "mcav:music/"
    );
    for (final String key : invalid) {
      assertThrows(IllegalArgumentException.class, () -> pack.sound(key, this.firstSound), key);
    }
    pack.sound("mcav:music/v1.2/intro", this.firstSound);
  }

  @Test
  void rejectsDotSegmentsThatBypassReservedPaths() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    assertThrows(IllegalArgumentException.class, () -> pack.external("./pack.mcmeta", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/./mcav/sounds.json", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/mcav/.", this.credits));
  }

  @Test
  void rejectsInvalidSounds() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path missing = this.directory.resolve("missing.ogg");

    assertThrows(IllegalArgumentException.class, () -> pack.sound("Mcav:audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound(":audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:my audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:audio", missing));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:audio", this.directory));
    assertThrows(NullPointerException.class, () -> pack.sound(null, this.firstSound));
    assertThrows(NullPointerException.class, () -> pack.sound("mcav:audio", null));
  }

  @Test
  void rejectsInvalidFileLocations() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path missing = this.directory.resolve("missing.txt");

    assertThrows(IllegalArgumentException.class, () -> pack.external("../evil.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/../../evil.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("with space.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("C:/absolute.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("assets/credits.txt", missing));
    assertThrows(NullPointerException.class, () -> pack.external(null, this.credits));
    assertThrows(NullPointerException.class, () -> pack.external("assets/credits.txt", null));
  }

  @Test
  void rejectsInvalidMetadata() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();

    assertThrows(IllegalArgumentException.class, () -> pack.meta(0, "pack"));
    assertThrows(NullPointerException.class, () -> pack.meta(1, null));
  }

  @Test
  void rejectsDestinationsWithoutAParent() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path root = this.directory.getRoot();
    pack.meta(1, "pack");

    assertThrows(IllegalArgumentException.class, () -> pack.zip(root));
  }

  @Test
  void reportsPacksThatCannotBeWritten() throws IOException {
    final SimpleResourcePack blocked = SimpleResourcePack.pack();
    final Path file = this.directory.resolve("file");
    final Path belowFile = file.resolve("pack.zip");
    blocked.meta(1, "pack");
    Files.write(file, CREDITS);

    assertThrows(UncheckedIOException.class, () -> blocked.zip(belowFile));
  }

  @Test
  void leavesNothingBehindWhenASoundVanishesBeforeZipping() throws IOException {
    final SimpleResourcePack vanished = SimpleResourcePack.pack();
    final Path output = this.directory.resolve("output");
    final Path zip = output.resolve("pack.zip");
    vanished.meta(1, "pack");
    vanished.sound("mcav:audio", this.firstSound);
    Files.delete(this.firstSound);
    Files.createDirectory(output);

    assertThrows(UncheckedIOException.class, () -> vanished.zip(zip));

    final List<Path> outputFiles = listFiles(output);
    final List<Path> nothing = List.of();
    assertEquals(nothing, outputFiles, "neither the pack nor the temporary file exist");
  }
}

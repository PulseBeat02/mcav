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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.bukkit.BukkitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;

final class SimpleResourcePackTest {

  private static final int GENERATED_PACK_FORMAT = 88;

  private static final byte[] ZIP_DESCRIPTOR_SIGNATURE = { 'P', 'K', 7, 8 };
  private static final byte[] ZIP_END_SIGNATURE = { 'P', 'K', 5, 6 };
  private static final int ZIP_END_RECORD_LENGTH = 22;

  private static final String PLAIN_DESCRIPTION = "pack";

  private static final byte[] FIRST_SOUND = "first sound".getBytes(StandardCharsets.US_ASCII);

  private static final byte[] SECOND_SOUND = "second sound".getBytes(StandardCharsets.US_ASCII);

  private static final byte[] CREDITS = "credits".getBytes(StandardCharsets.US_ASCII);

  private static final String DESCRIPTION = "MCAV <audio> & \"more\"";

  private static final List<CopyOption> ATOMIC_MOVE = List.of(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

  private static final List<CopyOption> REGULAR_MOVE = List.of(StandardCopyOption.REPLACE_EXISTING);

  private static final int PACK_FORMAT = 46;

  private static final String PACK_KEY = "pack";
  private static final String DESCRIPTION_KEY = "description";

  private static final String AUDIO_SOUND = "mcav:audio";
  private static final String INTRO_SOUND = "mcav:music/intro";
  private static final String BEEP_SOUND = "other:beep";
  private static final String FIRST_SOUND_EVENT = "mcav:first";

  private static final String SHADER_SOURCE = "#version 330";
  private static final String READABLE_PERMISSIONS = "rw-r--r--";

  private static final String CREDITS_FILE_NAME = "credits.txt";
  private static final String OUTPUT_FOLDER = "output";
  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String GENERATED_FILE_NAME = "generated.zip";
  private static final String FIRST_FILE_NAME = "first.zip";
  private static final String SECOND_FILE_NAME = "second.zip";
  private static final String SOURCE_FILE_NAME = "source.zip";
  private static final String TARGET_FILE_NAME = "target.zip";
  private static final String MISSING_FILE_NAME = "missing.txt";

  private static final String METADATA_ENTRY = "pack.mcmeta";
  private static final String ICON_ENTRY = "pack.png";
  private static final String GENERATED_ENTRY = "generated.txt";
  private static final String ASSETS_CREDITS_ENTRY = "assets/credits.txt";
  private static final String CREDITS_ENTRY = "assets/mcav/texts/credits.txt";
  private static final String REPLACED_ENTRY = "assets/mcav/texts/replaced.txt";
  private static final String SHADER_ENTRY = "assets/mcav/shaders/post/a.fsh";
  private static final String MCAV_SOUNDS_ENTRY = "assets/mcav/sounds.json";
  private static final String OTHER_SOUNDS_ENTRY = "assets/other/sounds.json";
  private static final String SOUNDS_TEXT_ENTRY = "assets/mcav/sounds.json.txt";
  private static final String SOUNDS_README_ENTRY = "assets/mcav/sounds/readme.txt";
  private static final String AUDIO_ENTRY = "assets/mcav/sounds/audio.ogg";
  private static final String INTRO_ENTRY = "assets/mcav/sounds/music/intro.ogg";
  private static final String BEEP_ENTRY = "assets/other/sounds/beep.ogg";
  private static final String FIRST_SOUND_ENTRY = "assets/mcav/sounds/first.ogg";
  private static final String SECOND_SOUND_ENTRY = "assets/mcav/sounds/second.ogg";

  private static final String FIRST_SOUND_FILE_NAME = "first.ogg";
  private static final String SECOND_SOUND_FILE_NAME = "second.ogg";
  private static final String MISSING_SOUND_FILE_NAME = "missing.ogg";

  private static final String BLOCKING_FILE_NAME = "file";
  private static final String TIMESTAMP_ENTRY_FILE_NAME = "a.txt";

  private static final String INTRO_SOUND_PATH = "music/intro";

  private static final String PARENT_METADATA_PATH = "assets/../pack.mcmeta";
  private static final String ABSOLUTE_DATA_PATH = "/assets/a.txt";
  private static final String ABSOLUTE_CREDITS_PATH = "/assets/credits.txt";
  private static final String DIRECTORY_CREDITS_PATH = "assets/credits/";
  private static final String EMPTY_SEGMENT_CREDITS_PATH = "assets//credits.txt";
  private static final String OTHER_NAMESPACE_INTRO_PATH = "assets/other/sounds/music/intro.ogg";
  private static final String DOT_METADATA_PATH = "./pack.mcmeta";
  private static final String DOT_SOUNDS_PATH = "assets/./mcav/sounds.json";
  private static final String TRAILING_DOT_PATH = "assets/mcav/.";
  private static final String PARENT_ESCAPE_PATH = "../evil.txt";
  private static final String ASSETS_PARENT_ESCAPE_PATH = "assets/../../evil.txt";

  @TempDir
  private Path directory;

  private Path firstSound;

  private Path secondSound;

  private Path credits;

  @BeforeEach
  void writeFiles() throws IOException {
    this.firstSound = this.directory.resolve(FIRST_SOUND_FILE_NAME);
    this.secondSound = this.directory.resolve(SECOND_SOUND_FILE_NAME);
    this.credits = this.directory.resolve(CREDITS_FILE_NAME);
    Files.write(this.firstSound, FIRST_SOUND);
    Files.write(this.secondSound, SECOND_SOUND);
    Files.write(this.credits, CREDITS);
  }

  private Map<String, byte[]> readZip(final Path zip) throws IOException {
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

  private List<String> entryNames(final Map<String, byte[]> entries) {
    final Set<String> names = entries.keySet();
    return List.copyOf(names);
  }

  private JsonObject parseEntry(final Map<String, byte[]> entries, final String name) {
    final byte[] content = entries.get(name);
    final String text = new String(content, StandardCharsets.UTF_8);
    final JsonElement element = JsonParser.parseString(text);
    return element.getAsJsonObject();
  }

  private JsonObject soundEvent(final String name) {
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

  private void addSoundEvent(final JsonObject events, final String value, final String name) {
    final JsonObject event = this.soundEvent(name);
    events.add(value, event);
  }

  private List<Path> listFiles(final Path folder) throws IOException {
    try (final Stream<Path> files = Files.list(folder)) {
      return files.toList();
    }
  }

  private void assertEntry(final byte[] expected, final Map<String, byte[]> entries, final String name) {
    final byte[] actual = entries.get(name);
    assertArrayEquals(expected, actual, name);
  }

  private SimpleResourcePack.FileMover recordingMover(final List<List<CopyOption>> moves, final AtomicMoves atomicMoves) {
    return (source, destination, options) -> this.recordFileMove(source, destination, options, moves, atomicMoves);
  }

  private void recordFileMove(
    final Path source,
    final Path destination,
    final CopyOption[] options,
    final List<List<CopyOption>> moves,
    final AtomicMoves atomicMoves
  ) throws IOException {
    final List<CopyOption> optionList = List.of(options);
    moves.add(optionList);

    if (atomicMoves == AtomicMoves.UNSUPPORTED && optionList.contains(StandardCopyOption.ATOMIC_MOVE)) {
      final String sourceText = source.toString();
      final String destinationText = destination.toString();
      final String message = "network share";
      throw new AtomicMoveNotSupportedException(sourceText, destinationText, message);
    }

    Files.move(source, destination, options);
  }

  private Path zipFullPack() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(PACK_FORMAT, DESCRIPTION);
    pack.sound(AUDIO_SOUND, this.firstSound);
    pack.sound(INTRO_SOUND, this.secondSound);
    pack.sound(BEEP_SOUND, this.firstSound);
    pack.external(CREDITS_ENTRY, this.credits);

    final Path output = this.directory.resolve(OUTPUT_FOLDER);
    final Path zip = output.resolve(PACK_FILE_NAME);
    pack.zip(zip);
    return zip;
  }

  private void assertMetadata(final Map<String, byte[]> entries) {
    final JsonObject expectedPack = new JsonObject();
    expectedPack.addProperty(DESCRIPTION_KEY, DESCRIPTION);
    expectedPack.addProperty("pack_format", PACK_FORMAT);
    expectedPack.addProperty("min_format", PACK_FORMAT);
    expectedPack.addProperty("max_format", PACK_FORMAT);
    final JsonObject expectedMeta = new JsonObject();
    expectedMeta.add(PACK_KEY, expectedPack);

    final JsonObject meta = this.parseEntry(entries, METADATA_ENTRY);
    final byte[] metaBytes = entries.get(METADATA_ENTRY);
    final String metaText = new String(metaBytes, StandardCharsets.UTF_8);
    final boolean readableDescription = metaText.contains("MCAV <audio> &");
    assertEquals(expectedMeta, meta);
    assertTrue(readableDescription, "the description is not HTML escaped");
  }

  private void assertSoundEvents(final Map<String, byte[]> entries) {
    final JsonObject expectedMcavSounds = new JsonObject();
    this.addSoundEvent(expectedMcavSounds, "audio", AUDIO_SOUND);
    this.addSoundEvent(expectedMcavSounds, INTRO_SOUND_PATH, INTRO_SOUND);
    final JsonObject expectedOtherSounds = new JsonObject();
    this.addSoundEvent(expectedOtherSounds, "beep", BEEP_SOUND);

    final JsonObject mcavSounds = this.parseEntry(entries, MCAV_SOUNDS_ENTRY);
    final JsonObject otherSounds = this.parseEntry(entries, OTHER_SOUNDS_ENTRY);
    assertEquals(expectedMcavSounds, mcavSounds);
    assertEquals(expectedOtherSounds, otherSounds);
  }

  private void assertReadableByEveryone(final Path zip) throws IOException {
    final PosixFileAttributeView permissionView = Files.getFileAttributeView(zip, PosixFileAttributeView.class);
    if (permissionView == null) {
      return;
    }

    final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(zip);
    final Set<PosixFilePermission> readable = PosixFilePermissions.fromString(READABLE_PERMISSIONS);
    assertEquals(readable, permissions, "a web server running as another user can read the pack");
  }

  @Test
  void shouldPackageTheMetadataSoundsAndFilesIntoAZip() throws IOException {
    final List<String> expectedNames = List.of(
      METADATA_ENTRY,
      AUDIO_ENTRY,
      INTRO_ENTRY,
      MCAV_SOUNDS_ENTRY,
      BEEP_ENTRY,
      OTHER_SOUNDS_ENTRY,
      CREDITS_ENTRY
    );

    final Path zip = this.zipFullPack();

    final Map<String, byte[]> entries = this.readZip(zip);
    final List<String> names = this.entryNames(entries);
    final Path output = zip.getParent();
    final List<Path> outputFiles = this.listFiles(output);
    final List<Path> onlyTheZip = List.of(zip);
    assertEquals(expectedNames, names);
    this.assertMetadata(entries);
    this.assertSoundEvents(entries);
    this.assertEntry(FIRST_SOUND, entries, AUDIO_ENTRY);
    this.assertEntry(SECOND_SOUND, entries, INTRO_ENTRY);
    this.assertEntry(FIRST_SOUND, entries, BEEP_ENTRY);
    this.assertEntry(CREDITS, entries, CREDITS_ENTRY);
    assertEquals(onlyTheZip, outputFiles, "no temporary file is left behind");
    this.assertReadableByEveryone(zip);
  }

  @Test
  void shouldPackageGeneratedContentAndKeepTheLastSourceOfALocation() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(GENERATED_PACK_FORMAT, "generated");
    final byte[] shader = SHADER_SOURCE.getBytes(StandardCharsets.US_ASCII);
    pack.data(SHADER_ENTRY, shader);
    shader[0] = 'X';
    pack.data(CREDITS_ENTRY, new byte[] { 1 });
    pack.external(CREDITS_ENTRY, this.credits);
    pack.external(REPLACED_ENTRY, this.credits);
    pack.data(REPLACED_ENTRY, new byte[] { 2 });
    final Path zip = this.directory.resolve(GENERATED_FILE_NAME);
    pack.zip(zip);

    final Map<String, byte[]> entries = this.readZip(zip);
    final List<String> expectedNames = List.of(METADATA_ENTRY, CREDITS_ENTRY, SHADER_ENTRY, REPLACED_ENTRY);
    final List<String> names = this.entryNames(entries);
    assertEquals(expectedNames, names);
    this.assertEntry(SHADER_SOURCE.getBytes(StandardCharsets.US_ASCII), entries, SHADER_ENTRY);
    this.assertEntry(CREDITS, entries, CREDITS_ENTRY);
    this.assertEntry(new byte[] { 2 }, entries, REPLACED_ENTRY);
    assertThrows(IllegalArgumentException.class, () -> pack.data(METADATA_ENTRY, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> pack.data(PARENT_METADATA_PATH, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> pack.data(ABSOLUTE_DATA_PATH, new byte[0]));
  }

  @Test
  void shouldZipTheSamePackToTheSameBytes() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(GENERATED_PACK_FORMAT, "stable");
    pack.sound(FIRST_SOUND_EVENT, this.firstSound);
    pack.external(CREDITS_ENTRY, this.credits);
    pack.data(SHADER_ENTRY, SHADER_SOURCE.getBytes(StandardCharsets.US_ASCII));
    final Path first = this.directory.resolve(FIRST_FILE_NAME);
    final Path second = this.directory.resolve(SECOND_FILE_NAME);
    pack.zip(first);
    pack.zip(second);
    final byte[] expectedBytes = Files.readAllBytes(first);
    final byte[] bytes = Files.readAllBytes(second);
    assertArrayEquals(expectedBytes, bytes);

    final LocalDateTime fixed = LocalDateTime.of(1980, 2, 1, 0, 0);
    try (final InputStream input = Files.newInputStream(first); final ZipInputStream zip = new ZipInputStream(input)) {
      int entries = 0;
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        final LocalDateTime time = entry.getTimeLocal();
        assertEquals(fixed, time, entry.getName());
        entries++;
      }
      assertEquals(5, entries);
    }

    final LocalDateTime newEntryTime = SimpleResourcePack.createEntry(TIMESTAMP_ENTRY_FILE_NAME).getTimeLocal();
    assertEquals(fixed, newEntryTime);
  }

  @Test
  void shouldRejectFileLocationsThatAreNotRelativeFiles() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();

    final IllegalArgumentException absolute = assertThrows(IllegalArgumentException.class, () ->
      pack.external(ABSOLUTE_CREDITS_PATH, this.credits)
    );

    final String message = absolute.getMessage();
    assertEquals("Pack path /assets/credits.txt must be relative to the pack root and name a file!", message);
    assertThrows(IllegalArgumentException.class, () -> pack.external(DIRECTORY_CREDITS_PATH, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(EMPTY_SEGMENT_CREDITS_PATH, this.credits));
  }

  @Test
  void shouldRejectFileLocationsReservedForGeneratedEntries() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path zip = this.directory.resolve(PACK_FILE_NAME);
    final List<String> expectedNames = List.of(METADATA_ENTRY, ICON_ENTRY, SOUNDS_README_ENTRY, SOUNDS_TEXT_ENTRY);

    final IllegalArgumentException meta = assertThrows(IllegalArgumentException.class, () -> pack.external(METADATA_ENTRY, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(MCAV_SOUNDS_ENTRY, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(AUDIO_ENTRY, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(OTHER_NAMESPACE_INTRO_PATH, this.credits));

    pack.external(ICON_ENTRY, this.credits);
    pack.external(SOUNDS_README_ENTRY, this.credits);
    pack.external(SOUNDS_TEXT_ENTRY, this.credits);
    pack.metadata(1, PLAIN_DESCRIPTION);
    pack.zip(zip);

    final String message = meta.getMessage();
    final Map<String, byte[]> entries = this.readZip(zip);
    final List<String> names = this.entryNames(entries);
    assertEquals("Pack path pack.mcmeta is reserved for entries generated by the builder!", message);
    assertEquals(expectedNames, names);
  }

  @Test
  void shouldMakeFilesReadableByEveryoneOnlyOnPosixFileSystems() throws IOException {
    final PosixFileAttributeView view = mock(PosixFileAttributeView.class);
    final Set<PosixFilePermission> readable = PosixFilePermissions.fromString(READABLE_PERMISSIONS);

    SimpleResourcePack.makeReadable(view);

    verify(view).setPermissions(readable);
    assertDoesNotThrow(() -> SimpleResourcePack.makeReadable(null), "Windows has no POSIX permissions to change");
  }

  @Test
  void shouldMovePacksIntoPlaceAtomicallyWhereSupported() throws IOException {
    final Path source = this.directory.resolve(SOURCE_FILE_NAME);
    final Path target = this.directory.resolve(TARGET_FILE_NAME);
    Files.write(source, CREDITS);
    final List<List<CopyOption>> moves = new ArrayList<>();
    final SimpleResourcePack.FileMover mover = this.recordingMover(moves, AtomicMoves.SUPPORTED);

    SimpleResourcePack.moveIntoPlace(source, target, mover);

    final byte[] moved = Files.readAllBytes(target);
    final List<List<CopyOption>> expectedMoves = List.of(ATOMIC_MOVE);
    assertEquals(expectedMoves, moves);
    assertArrayEquals(CREDITS, moved);
  }

  @Test
  void shouldFallBackToARegularMoveWhereAtomicMovesAreNotSupported() throws IOException {
    final Path source = this.directory.resolve(SOURCE_FILE_NAME);
    final Path target = this.directory.resolve(TARGET_FILE_NAME);
    Files.write(source, CREDITS);
    Files.write(target, FIRST_SOUND);
    final List<List<CopyOption>> moves = new ArrayList<>();
    final SimpleResourcePack.FileMover mover = this.recordingMover(moves, AtomicMoves.UNSUPPORTED);

    SimpleResourcePack.moveIntoPlace(source, target, mover);

    final byte[] moved = Files.readAllBytes(target);
    final boolean sourceExists = Files.exists(source);
    final List<List<CopyOption>> expectedMoves = List.of(ATOMIC_MOVE, REGULAR_MOVE);
    assertEquals(expectedMoves, moves);
    assertArrayEquals(CREDITS, moved, "the existing pack is replaced");
    assertFalse(sourceExists);
  }

  @Test
  void shouldReplaceSoundsWithTheSameKeyAndPacksThatAlreadyExist() throws IOException {
    final Path zip = this.directory.resolve(PACK_FILE_NAME);
    final SimpleResourcePack first = SimpleResourcePack.pack();
    final SimpleResourcePack second = SimpleResourcePack.pack();

    first.metadata(1, "first");
    first.zip(zip);
    second.metadata(2, "second");
    second.sound(AUDIO_SOUND, this.firstSound);
    second.sound(AUDIO_SOUND, this.secondSound);
    second.zip(zip);

    final Map<String, byte[]> entries = this.readZip(zip);
    final JsonObject meta = this.parseEntry(entries, METADATA_ENTRY);
    final JsonObject packObject = meta.getAsJsonObject(PACK_KEY);
    final JsonElement descriptionElement = packObject.get(DESCRIPTION_KEY);
    final String description = descriptionElement.getAsString();
    final JsonObject sounds = this.parseEntry(entries, MCAV_SOUNDS_ENTRY);
    final int eventCount = sounds.size();
    assertEquals("second", description);
    assertEquals(1, eventCount);
    this.assertEntry(SECOND_SOUND, entries, AUDIO_ENTRY);
  }

  @Test
  void shouldCreateIndependentBuilders() throws IOException {
    final SimpleResourcePack first = SimpleResourcePack.pack();
    final SimpleResourcePack second = SimpleResourcePack.pack();

    assertNotSame(first, second);

    first.metadata(1, "first");
    second.metadata(2, "second");
    first.external(CREDITS_FILE_NAME, this.credits);
    second.external(CREDITS_FILE_NAME, this.secondSound);
    first.data(GENERATED_ENTRY, FIRST_SOUND);
    second.data(GENERATED_ENTRY, SECOND_SOUND);
    first.sound(FIRST_SOUND_EVENT, this.firstSound);
    second.sound("mcav:second", this.secondSound);
    final Path firstZip = this.directory.resolve(FIRST_FILE_NAME);
    final Path secondZip = this.directory.resolve(SECOND_FILE_NAME);
    first.zip(firstZip);
    second.zip(secondZip);

    final Map<String, byte[]> firstEntries = this.readZip(firstZip);
    final Map<String, byte[]> secondEntries = this.readZip(secondZip);
    final Set<String> expectedFirstNames = Set.of(METADATA_ENTRY, CREDITS_FILE_NAME, GENERATED_ENTRY, FIRST_SOUND_ENTRY, MCAV_SOUNDS_ENTRY);
    final Set<String> firstNames = firstEntries.keySet();
    assertEquals(expectedFirstNames, firstNames);

    final Set<String> expectedSecondNames = Set.of(
      METADATA_ENTRY,
      CREDITS_FILE_NAME,
      GENERATED_ENTRY,
      SECOND_SOUND_ENTRY,
      MCAV_SOUNDS_ENTRY
    );
    final Set<String> secondNames = secondEntries.keySet();
    assertEquals(expectedSecondNames, secondNames);

    final JsonElement expectedFirstMetadata = JsonParser.parseString(
      "{\"pack\":{\"description\":\"first\",\"pack_format\":1,\"min_format\":1,\"max_format\":1}}"
    );
    final JsonObject firstMetadata = this.parseEntry(firstEntries, METADATA_ENTRY);
    assertEquals(expectedFirstMetadata, firstMetadata);

    final JsonElement expectedSecondMetadata = JsonParser.parseString(
      "{\"pack\":{\"description\":\"second\",\"pack_format\":2,\"min_format\":2,\"max_format\":2}}"
    );
    final JsonObject secondMetadata = this.parseEntry(secondEntries, METADATA_ENTRY);
    assertEquals(expectedSecondMetadata, secondMetadata);

    final JsonObject firstEvents = new JsonObject();
    final JsonObject secondEvents = new JsonObject();
    this.addSoundEvent(firstEvents, "first", FIRST_SOUND_EVENT);
    this.addSoundEvent(secondEvents, "second", "mcav:second");
    final JsonObject firstSounds = this.parseEntry(firstEntries, MCAV_SOUNDS_ENTRY);
    assertEquals(firstEvents, firstSounds);

    final JsonObject secondSounds = this.parseEntry(secondEntries, MCAV_SOUNDS_ENTRY);
    assertEquals(secondEvents, secondSounds);
    this.assertEntry(CREDITS, firstEntries, CREDITS_FILE_NAME);
    this.assertEntry(SECOND_SOUND, secondEntries, CREDITS_FILE_NAME);
    this.assertEntry(FIRST_SOUND, firstEntries, GENERATED_ENTRY);
    this.assertEntry(SECOND_SOUND, secondEntries, GENERATED_ENTRY);
    this.assertEntry(FIRST_SOUND, firstEntries, FIRST_SOUND_ENTRY);
    this.assertEntry(SECOND_SOUND, secondEntries, SECOND_SOUND_ENTRY);
  }

  @Test
  void shouldNeedMetadataBeforeZipping() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path zip = this.directory.resolve(PACK_FILE_NAME);

    assertThrows(IllegalStateException.class, () -> pack.zip(zip));

    final boolean exists = Files.exists(zip);
    assertFalse(exists);
    assertThrows(NullPointerException.class, () -> pack.zip(null));
  }

  @Test
  void shouldRejectTraversalAndAmbiguousSoundPaths() {
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
  void shouldRejectDotSegmentsThatBypassReservedPaths() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    assertThrows(IllegalArgumentException.class, () -> pack.external(DOT_METADATA_PATH, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(DOT_SOUNDS_PATH, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(TRAILING_DOT_PATH, this.credits));
  }

  @Test
  void shouldRejectInvalidSounds() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path missing = this.directory.resolve(MISSING_SOUND_FILE_NAME);

    assertThrows(IllegalArgumentException.class, () -> pack.sound("Mcav:audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound(":audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound("mcav:my audio", this.firstSound));
    assertThrows(IllegalArgumentException.class, () -> pack.sound(AUDIO_SOUND, missing));
    assertThrows(IllegalArgumentException.class, () -> pack.sound(AUDIO_SOUND, this.directory));
    assertThrows(NullPointerException.class, () -> pack.sound(null, this.firstSound));
    assertThrows(NullPointerException.class, () -> pack.sound(AUDIO_SOUND, null));
  }

  @Test
  void shouldRejectInvalidFileLocations() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);

    assertThrows(IllegalArgumentException.class, () -> pack.external(PARENT_ESCAPE_PATH, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(ASSETS_PARENT_ESCAPE_PATH, this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("with space.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("C:/absolute.txt", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external("", this.credits));
    assertThrows(IllegalArgumentException.class, () -> pack.external(ASSETS_CREDITS_ENTRY, missing));
    assertThrows(NullPointerException.class, () -> pack.external(null, this.credits));
    assertThrows(NullPointerException.class, () -> pack.external(ASSETS_CREDITS_ENTRY, null));
  }

  @Test
  void shouldRejectInvalidMetadata() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();

    assertThrows(IllegalArgumentException.class, () -> pack.metadata(0, PLAIN_DESCRIPTION));
    assertThrows(NullPointerException.class, () -> pack.metadata(1, null));
  }

  @Test
  void shouldRejectDestinationsWithoutAParent() {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    final Path root = this.directory.getRoot();
    pack.metadata(1, PLAIN_DESCRIPTION);

    assertThrows(IllegalArgumentException.class, () -> pack.zip(root));
  }

  @Test
  void shouldFinishTheArchiveAfterAGeneratedEntryDescriptorFails() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(1, PLAIN_DESCRIPTION);
    pack.data(GENERATED_ENTRY, CREDITS);

    this.assertFinishedArchiveAfterDescriptorFailure(pack);
  }

  @Test
  void shouldFinishTheArchiveAfterTheMetadataDescriptorFails() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(1, PLAIN_DESCRIPTION);

    this.assertFinishedArchiveAfterDescriptorFailure(pack);
  }

  @Test
  void shouldFinishTheArchiveAfterAnExternalFileDescriptorFails() throws IOException {
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(1, PLAIN_DESCRIPTION);
    pack.external(CREDITS_FILE_NAME, this.credits);

    this.assertFinishedArchiveAfterDescriptorFailure(pack);
  }

  private void assertFinishedArchiveAfterDescriptorFailure(final SimpleResourcePack pack) throws IOException {
    final Path reference = this.directory.resolve(GENERATED_FILE_NAME);
    pack.zip(reference);
    final byte[] complete = Files.readAllBytes(reference);
    final int descriptorOffset = this.lastDescriptorOffset(complete);
    final DescriptorFailureOutputStream output = new DescriptorFailureOutputStream(descriptorOffset);
    final Path target = this.directory.resolve(PACK_FILE_NAME);
    final Path temporary = this.directory.resolve(SOURCE_FILE_NAME);
    try (final MockedStatic<Files> files = mockStatic(Files.class)) {
      files.when(() -> Files.createTempFile(any(Path.class), anyString(), anyString())).thenReturn(temporary);
      files.when(() -> Files.newOutputStream(temporary)).thenReturn(output);
      files.when(() -> Files.copy(eq(this.credits), any(OutputStream.class))).thenAnswer(this::copyCredits);

      final BukkitException exception = assertThrows(BukkitException.class, () -> pack.zip(target));
      final Throwable cause = exception.getCause();
      final ZipException failure = output.getFailure();
      assertSame(failure, cause);
    }

    final byte[] archive = output.getBytes();
    final int endOffset = archive.length - ZIP_END_RECORD_LENGTH;
    assertTrue(endOffset >= 0);

    final byte[] endSignature = Arrays.copyOfRange(archive, endOffset, endOffset + ZIP_END_SIGNATURE.length);
    assertArrayEquals(ZIP_END_SIGNATURE, endSignature);
  }

  private int lastDescriptorOffset(final byte[] archive) {
    for (int offset = archive.length - ZIP_DESCRIPTOR_SIGNATURE.length; offset >= 0; offset--) {
      final byte[] signature = Arrays.copyOfRange(archive, offset, offset + ZIP_DESCRIPTOR_SIGNATURE.length);
      if (Arrays.equals(ZIP_DESCRIPTOR_SIGNATURE, signature)) {
        return offset;
      }
    }

    final String message = "The reference archive must contain a data descriptor!";
    throw new AssertionError(message);
  }

  private long copyCredits(final InvocationOnMock invocation) throws IOException {
    final OutputStream output = invocation.getArgument(1);
    output.write(CREDITS);

    return CREDITS.length;
  }

  @Test
  void shouldReportPacksThatCannotBeWritten() throws IOException {
    final SimpleResourcePack blocked = SimpleResourcePack.pack();
    final Path file = this.directory.resolve(BLOCKING_FILE_NAME);
    final Path belowFile = file.resolve(PACK_FILE_NAME);
    blocked.metadata(1, PLAIN_DESCRIPTION);
    Files.write(file, CREDITS);

    final BukkitException exception = assertThrows(BukkitException.class, () -> blocked.zip(belowFile));
    final String message = exception.getMessage();
    final Path absolute = belowFile.toAbsolutePath();
    final String expected = "Resource pack %s could not be written!".formatted(absolute);
    assertEquals(expected, message);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldLeaveNothingBehindWhenASoundVanishesBeforeZipping() throws IOException {
    final SimpleResourcePack vanished = SimpleResourcePack.pack();
    final Path output = this.directory.resolve(OUTPUT_FOLDER);
    final Path zip = output.resolve(PACK_FILE_NAME);
    vanished.metadata(1, PLAIN_DESCRIPTION);
    vanished.sound(AUDIO_SOUND, this.firstSound);
    Files.delete(this.firstSound);
    Files.createDirectory(output);

    assertThrows(BukkitException.class, () -> vanished.zip(zip));

    final List<Path> outputFiles = this.listFiles(output);
    final List<Path> nothing = List.of();
    assertEquals(nothing, outputFiles, "neither the pack nor the temporary file exist");
  }

  private static final class DescriptorFailureOutputStream extends OutputStream {

    private final ByteArrayOutputStream bytes;
    private final ZipException failure;
    private final int descriptorOffset;

    private boolean failed;

    private DescriptorFailureOutputStream(final int descriptorOffset) {
      this.bytes = new ByteArrayOutputStream();
      this.failure = new ZipException("Descriptor write failed!");
      this.descriptorOffset = descriptorOffset;
      this.failed = false;
    }

    @Override
    public void write(final int value) throws IOException {
      final int written = this.bytes.size();
      if (!this.failed && written == this.descriptorOffset) {
        this.failed = true;
        throw this.failure;
      }

      this.bytes.write(value);
    }

    private ZipException getFailure() {
      return this.failure;
    }

    private byte[] getBytes() {
      return this.bytes.toByteArray();
    }
  }

  private enum AtomicMoves {
    SUPPORTED,
    UNSUPPORTED,
  }
}

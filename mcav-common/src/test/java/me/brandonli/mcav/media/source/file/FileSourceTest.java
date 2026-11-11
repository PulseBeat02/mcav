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
package me.brandonli.mcav.media.source.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import me.brandonli.mcav.utils.FileOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSourceTest {

  private static final String MEDIA_DIRECTORY_NAME = "media";
  private static final String CLIP_FILE_NAME = "clip.mp4";

  private static final String FIRST_FILE_NAME = "a.mp4";
  private static final String OTHER_FILE_NAME = "b.mp4";

  private static final String VIDEO_FILE_NAME = "video.mp4";

  private static final String DATA_FILE_NAME = "data.bin";

  private static final String OUTPUT_FILE_NAME = "out.txt";

  private static final String EXISTING_FILE_NAME = "exists.mp4";
  private static final String MISSING_FILE_NAME = "missing.mp4";
  private static final String INVALID_PATH = "bad\u0000path";

  @TempDir
  private Path directory;

  @Test
  void shouldDescribeTheFile() {
    final Path path = Path.of(MEDIA_DIRECTORY_NAME, CLIP_FILE_NAME);
    final FileSource source = FileSource.path(path);
    final Path storedPath = source.getPath();
    final String resource = source.getResource();
    final String expectedResource = path.toString();
    final String name = source.getName();
    final boolean isStatic = source.isStatic();
    final boolean isDynamic = source.isDynamic();
    final String text = source.toString();
    final String expectedText = "DefaultFileSource{path=%s}".formatted(path);
    assertFalse(isDynamic);
    assertEquals(path, storedPath);
    assertEquals(expectedResource, resource);
    assertEquals("file", name);
    assertTrue(isStatic);
    assertEquals(expectedText, text);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final Path path = Path.of(FIRST_FILE_NAME);
    final Path otherPath = Path.of(OTHER_FILE_NAME);
    final FileSource source = FileSource.path(path);
    final FileSource equalSource = FileSource.path(path);
    final FileSource otherSource = FileSource.path(otherPath);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherSource);
  }

  @Test
  void shouldHashThePath() {
    final Path path = this.directory.resolve(VIDEO_FILE_NAME);
    final FileSource source = FileSource.path(path);

    final int hash = source.hashCode();
    final int expected = Objects.hash(path);
    assertEquals(expected, hash);
  }

  @Test
  void shouldWriteAndReadThroughItsWritable() throws IOException {
    final Path path = this.directory.resolve(DATA_FILE_NAME);
    final FileSource source = FileSource.path(path);
    final Writable writable = source.createWritable();
    final Path writablePath = writable.getPath();
    final byte[] content = { 1, 2, 3 };
    try (final OutputStream output = writable.newOutputStream()) {
      output.write(content);
    }

    final byte[] read;
    try (final InputStream input = writable.newInputStream()) {
      read = input.readAllBytes();
    }

    assertEquals(path, writablePath);
    assertArrayEquals(content, read);
  }

  @Test
  void shouldTruncateWithoutOptionsAndAppendWhenAsked() throws IOException {
    final Path path = this.directory.resolve(DATA_FILE_NAME);
    final Writable writable = Writable.path(path);
    final byte[] first = { 1, 2, 3 };
    final byte[] second = { 4 };
    final byte[] appended = { 5 };
    this.write(writable, first);
    this.write(writable, second);
    this.write(writable, appended, StandardOpenOption.APPEND);

    final byte[] content = Files.readAllBytes(path);
    final byte[] expected = { 4, 5 };
    assertArrayEquals(expected, content);
  }

  private void write(final Writable writable, final byte[] content, final OpenOption... options) throws IOException {
    try (final OutputStream output = writable.newOutputStream(options)) {
      output.write(content);
    }
  }

  @Test
  void shouldReportFilesThatCannotBeOpened() {
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final Path unreachable = missing.resolve(OUTPUT_FILE_NAME);
    final Writable missingFile = Writable.path(missing);
    final Writable unreachableFile = Writable.path(unreachable);
    final FileOperationException reading = assertThrows(FileOperationException.class, missingFile::newInputStream);
    final FileOperationException writing = assertThrows(FileOperationException.class, unreachableFile::newOutputStream);
    final Throwable readingCause = reading.getCause();
    final Throwable writingCause = writing.getCause();
    assertInstanceOf(NoSuchFileException.class, readingCause);
    assertInstanceOf(NoSuchFileException.class, writingCause);
  }

  @Test
  void shouldCreateWritablesForPaths() {
    final Path path = this.directory.resolve(OUTPUT_FILE_NAME);
    final Writable writable = Writable.path(path);
    final Path writablePath = writable.getPath();
    assertEquals(path, writablePath);
    assertThrows(NullPointerException.class, () -> Writable.path(null));
  }

  @Test
  void shouldDescribeTheWritable() {
    final Path path = Path.of(FIRST_FILE_NAME);
    final Writable writable = Writable.path(path);

    final String text = writable.toString();
    final String expected = "DefaultWritable{path=%s}".formatted(path);
    assertEquals(expected, text);
  }

  @Test
  void shouldFollowTheWritableEqualityContract() {
    final Path path = Path.of(FIRST_FILE_NAME);
    final Path otherPath = Path.of(OTHER_FILE_NAME);
    final Writable writable = Writable.path(path);
    final Writable equalWritable = Writable.path(path);
    final Writable otherWritable = Writable.path(otherPath);
    EqualityAssertions.assertEqualityContract(writable, equalWritable, otherWritable);
  }

  @Test
  void shouldHashTheWritablePath() {
    final Path path = this.directory.resolve(OUTPUT_FILE_NAME);
    final Writable writable = Writable.path(path);

    final int hash = writable.hashCode();
    final int expected = Objects.hash(path);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDetectOnlyExistingPaths() throws IOException {
    final Path existing = this.directory.resolve(EXISTING_FILE_NAME);
    Files.createFile(existing);
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final FileSourceDetector detector = new FileSourceDetector();
    final String existingRaw = existing.toString();
    final String missingRaw = missing.toString();
    final boolean existingDetected = detector.isDetectedSource(existingRaw);
    final boolean missingDetected = detector.isDetectedSource(missingRaw);
    final boolean invalidDetected = detector.isDetectedSource(INVALID_PATH);
    final FileSource created = detector.createSource(existingRaw);
    final FileSource expected = FileSource.path(existing);
    final int priority = detector.getPriority();
    assertTrue(existingDetected);
    assertFalse(missingDetected);
    assertFalse(invalidDetected);
    assertEquals(expected, created);
    assertEquals(SourceDetector.HIGH_PRIORITY, priority);
  }

  @Test
  void shouldDetectExistingDirectories() {
    final FileSourceDetector detector = new FileSourceDetector();
    final String resource = this.directory.toString();
    final boolean detected = detector.isDetectedSource(resource);
    final FileSource created = detector.createSource(resource);
    final FileSource expected = FileSource.path(this.directory);
    assertTrue(detected);
    assertEquals(expected, created);
  }

  @Test
  void shouldRejectNullInput() {
    final FileSourceDetector detector = new FileSourceDetector();
    assertThrows(NullPointerException.class, () -> FileSource.path(null));
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }

  @Test
  void shouldRejectAnInvalidPathWhenCreatingTheSource() {
    final FileSourceDetector detector = new FileSourceDetector();
    assertThrows(InvalidPathException.class, () -> detector.createSource(INVALID_PATH));
  }
}

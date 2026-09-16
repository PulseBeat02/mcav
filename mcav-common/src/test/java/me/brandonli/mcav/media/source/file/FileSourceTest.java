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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileSource}, {@link FileSourceImpl}, {@link FileSourceDetector} and {@link Writable}.
 */
final class FileSourceTest {

  @TempDir
  private Path directory;

  @Test
  void describesTheFile() {
    final Path path = Path.of("media", "clip.mp4");
    final FileSource source = FileSource.path(path);
    final Path storedPath = source.getPath();
    final String resource = source.getResource();
    final String expectedResource = path.toString();
    final String name = source.getName();
    final boolean isStatic = source.isStatic();
    final boolean isDynamic = source.isDynamic();
    final String text = source.toString();
    assertFalse(isDynamic);
    assertEquals(path, storedPath);
    assertEquals(expectedResource, resource);
    assertEquals("file", name);
    assertTrue(isStatic);
    assertEquals("FileSource[" + path + "]", text);
  }

  @Test
  void followsTheEqualityContract() {
    final Path path = Path.of("a.mp4");
    final Path otherPath = Path.of("b.mp4");
    final FileSource source = FileSource.path(path);
    final FileSource equalSource = FileSource.path(path);
    final FileSource otherSource = FileSource.path(otherPath);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherSource);
  }

  @Test
  void writesAndReadsThroughItsWritable() throws IOException {
    final Path path = this.directory.resolve("data.bin");
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
  void createsWritablesForPaths() {
    final Path path = this.directory.resolve("out.txt");
    final Writable writable = Writable.path(path);
    final Path writablePath = writable.getPath();
    assertEquals(path, writablePath);
    assertThrows(NullPointerException.class, () -> Writable.path(null));
  }

  @Test
  void detectsOnlyExistingPaths() throws IOException {
    final Path existing = this.directory.resolve("exists.mp4");
    Files.createFile(existing);
    final Path missing = this.directory.resolve("missing.mp4");
    final FileSourceDetector detector = new FileSourceDetector();
    final String existingRaw = existing.toString();
    final String missingRaw = missing.toString();
    final boolean existingDetected = detector.isDetectedSource(existingRaw);
    final boolean missingDetected = detector.isDetectedSource(missingRaw);
    final boolean invalidDetected = detector.isDetectedSource("bad\u0000path");
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
  void rejectsNullInput() {
    final FileSourceDetector detector = new FileSourceDetector();
    assertThrows(NullPointerException.class, () -> FileSource.path(null));
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }
}

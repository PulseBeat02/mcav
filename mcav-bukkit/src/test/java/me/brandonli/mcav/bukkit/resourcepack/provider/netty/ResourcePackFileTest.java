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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/**
 * Tests {@link ResourcePackFile}.
 */
final class ResourcePackFileTest {

  private static final byte[] ORIGINAL = { 1, 2, 3 };
  private static final byte[] SAME_SIZE = { 4, 5, 6 };
  private static final byte[] LONGER = { 7, 8, 9, 10 };

  @TempDir
  private Path directory;

  @Test
  void readsTheFileOnlyOnceWhileItIsUnchanged() throws IOException {
    final Path pack = this.directory.resolve("pack.zip");
    Files.write(pack, ORIGINAL);
    final ResourcePackFile file = new ResourcePackFile(pack);

    final byte[] first = file.read();
    final byte[] second = file.read();

    assertArrayEquals(ORIGINAL, first);
    assertSame(first, second, "the cached bytes are shared");
  }

  @Test
  void readsTheFileAgainWhenItsSizeChanged() throws IOException {
    final Path pack = this.directory.resolve("pack.zip");
    Files.write(pack, ORIGINAL);
    final FileTime modified = Files.getLastModifiedTime(pack);
    final ResourcePackFile file = new ResourcePackFile(pack);

    final byte[] first = file.read();
    Files.write(pack, LONGER);
    Files.setLastModifiedTime(pack, modified);
    final byte[] second = file.read();

    assertNotSame(first, second);
    assertArrayEquals(LONGER, second);
  }

  @Test
  void readsTheFileAgainWhenItWasModified() throws IOException {
    final Path pack = this.directory.resolve("pack.zip");
    final Instant firstModification = Instant.parse("2024-01-01T00:00:00Z");
    final Instant secondModification = Instant.parse("2024-01-02T00:00:00Z");
    final FileTime firstTime = FileTime.from(firstModification);
    final FileTime secondTime = FileTime.from(secondModification);
    Files.write(pack, ORIGINAL);
    Files.setLastModifiedTime(pack, firstTime);
    final ResourcePackFile file = new ResourcePackFile(pack);

    final byte[] first = file.read();
    Files.write(pack, SAME_SIZE);
    Files.setLastModifiedTime(pack, secondTime);
    final byte[] second = file.read();

    assertArrayEquals(ORIGINAL, first);
    assertArrayEquals(SAME_SIZE, second);
  }

  @Test
  void reportsMissingFiles() {
    final Path missing = this.directory.resolve("missing.zip");
    final ResourcePackFile file = new ResourcePackFile(missing);

    assertThrows(NoSuchFileException.class, file::read);
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void detectsSubmillisecondChangesAndReplacements(final boolean replacement) throws IOException {
    final Path path = this.directory.resolve("precise.zip");
    final BasicFileAttributes first = mock(BasicFileAttributes.class);
    final BasicFileAttributes second = mock(BasicFileAttributes.class);
    final Instant initial = Instant.parse("2024-01-01T00:00:00.000000001Z");
    final Instant updated = replacement ? initial : initial.plusNanos(1);
    final FileTime firstTime = FileTime.from(initial);
    final FileTime secondTime = FileTime.from(updated);
    when(first.lastModifiedTime()).thenReturn(firstTime);
    when(second.lastModifiedTime()).thenReturn(secondTime);
    when(first.size()).thenReturn(3L);
    when(second.size()).thenReturn(3L);
    when(first.fileKey()).thenReturn("inode-one");
    when(second.fileKey()).thenReturn(replacement ? "inode-two" : "inode-one");
    try (final MockedStatic<Files> files = mockStatic(Files.class)) {
      files.when(() -> Files.readAttributes(path, BasicFileAttributes.class)).thenReturn(first, second);
      files.when(() -> Files.readAllBytes(path)).thenReturn(ORIGINAL, SAME_SIZE);
      final ResourcePackFile cache = new ResourcePackFile(path);
      final byte[] original = cache.read();
      final byte[] changed = cache.read();
      assertArrayEquals(ORIGINAL, original);
      assertArrayEquals(SAME_SIZE, changed);
    }
  }
}

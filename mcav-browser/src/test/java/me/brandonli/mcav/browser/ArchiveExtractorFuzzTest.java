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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import me.brandonli.mcav.utils.IoUtils;
import org.junit.jupiter.api.Tag;

// The target gzips the tar streams, so the fuzzer works on tar, not gzip; behind the jar's pinned hash, this is a second line of defense.
@Tag("fuzz")
final class ArchiveExtractorFuzzTest {

  private static final String FILE_SYSTEM_ROOT = "/";

  private static final String TEMPORARY_FOLDER_PREFIX = "mcav-extract-fuzz";
  private static final String TARGET_FOLDER = "target";
  private static final String OBSERVED_ARCHIVE_FILE = "observed.zip";
  private static final String NESTED_ROOT_FOLDER = "nested/root";
  private static final String SENTINEL_FILE = "existing";

  private static final int MAXIMUM_ENTRIES = 64;
  private static final long MAXIMUM_BYTES = 1 << 20;

  @FuzzTest(maxDuration = "30s")
  void shouldExtractOnlyPlainFilesInsideTheTarget(final FuzzedDataProvider data) throws IOException {
    final byte[] tar = data.consumeRemainingAsBytes();
    final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (final GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
      gzip.write(tar);
    }

    final Path root = Files.createTempDirectory(TEMPORARY_FOLDER_PREFIX);
    try {
      final Path target = Files.createDirectory(root.resolve(TARGET_FOLDER));
      try {
        new ArchiveExtractor(MAXIMUM_ENTRIES, MAXIMUM_BYTES).extract(new ByteArrayInputStream(compressed.toByteArray()), target);
      } catch (final IOException exception) {
        // malformed or breaking a rule; what was written so far is checked all the same
      }
      this.check(root, target);
      this.checkWholeFileSystem(root.resolve(OBSERVED_ARCHIVE_FILE), compressed.toByteArray());
    } finally {
      IoUtils.deleteRecursively(root);
    }
  }

  private void checkWholeFileSystem(final Path backing, final byte[] archive) throws IOException {
    try (final FileSystem fileSystem = FileSystems.newFileSystem(backing, Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
      final Path scope = fileSystem.getPath(FILE_SYSTEM_ROOT);
      final Path root = Files.createDirectories(scope.resolve(NESTED_ROOT_FOLDER));
      final Path target = Files.createDirectory(root.resolve(TARGET_FOLDER));
      final byte[] untouched = { 3, 5, 7 };
      final Path sentinel = Files.write(scope.resolve(SENTINEL_FILE), untouched);
      final List<Path> before;
      try (final Stream<Path> paths = Files.walk(scope)) {
        before = paths.toList();
      }
      try {
        new ArchiveExtractor(MAXIMUM_ENTRIES, MAXIMUM_BYTES).extract(new ByteArrayInputStream(archive), target);
      } catch (final IOException exception) {
        // a refused archive must leave the file system around the target alone too
      }
      this.check(root, target);
      try (final Stream<Path> paths = Files.walk(scope)) {
        final List<Path> outside = paths.filter(path -> !before.contains(path) && !path.startsWith(target)).toList();
        assertEquals(List.of(), outside, "writes above the extraction root remain visible");
      }
      final byte[] sentinelBytes = Files.readAllBytes(sentinel);
      assertArrayEquals(untouched, sentinelBytes);
    }
  }

  private void check(final Path root, final Path target) throws IOException {
    final boolean supportsPosix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
    final List<Path> all = this.listAllPaths(root);
    long bytes = 0L;
    for (final Path path : all) {
      final boolean symbolicLink = Files.isSymbolicLink(path);
      assertFalse(symbolicLink, () -> "a link: " + path);

      final boolean insideTarget = path.equals(root) || path.startsWith(target);
      assertTrue(insideTarget, () -> "outside the target: " + path);

      final boolean regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
      final boolean fileOrFolder = regular || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
      assertTrue(fileOrFolder, () -> "neither file nor folder: " + path);

      if (regular) {
        bytes += Files.size(path);
      }
      if (regular && supportsPosix) {
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        final boolean writableByOthers = permissions.contains(PosixFilePermission.OTHERS_WRITE);
        assertFalse(writableByOthers, () -> "writable by others: " + path);
      }
    }

    final long total = bytes;
    final boolean withinLimit = total <= MAXIMUM_BYTES;
    assertTrue(withinLimit, () -> total + " bytes");
  }

  private List<Path> listAllPaths(final Path root) throws IOException {
    try (final Stream<Path> paths = Files.walk(root)) {
      return paths.toList();
    } catch (final UncheckedIOException exception) {
      throw exception.getCause();
    }
  }
}

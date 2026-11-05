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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

// Fuzzes the extraction of zip archives, which the installers of MCAV run on archives they download.
@Tag("fuzz")
final class UnzipFuzzTest {

  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-unzip-fuzz";

  private static final String OBSERVED_ARCHIVE_NAME = "observed.zip";
  private static final String ZIP_ROOT = "/";
  private static final String NESTED_ROOT = "nested/root";

  private static final String ARCHIVE_NAME = "archive.zip";
  private static final String NESTING = "1/2/3/4/5/6/7/8";
  private static final String DESTINATION_NAME = "destination";

  @FuzzTest(maxDuration = "30s")
  void shouldExtractOnlyIntoTheDestination(final byte[] archiveBytes) throws IOException {
    final Path root = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    try {
      checkExtraction(root, root, archiveBytes);

      try (final FileSystem fileSystem = FileSystems.newFileSystem(root.resolve(OBSERVED_ARCHIVE_NAME), Map.of("create", "true"))) {
        final Path scope = fileSystem.getPath(ZIP_ROOT);
        final Path extractionRoot = Files.createDirectories(scope.resolve(NESTED_ROOT));
        checkExtraction(extractionRoot, scope, archiveBytes);
      }
    } finally {
      deleteTree(root);
    }
  }

  private static void checkExtraction(final Path root, final Path scope, final byte[] archiveBytes) throws IOException {
    final Path archive = root.resolve(ARCHIVE_NAME);
    final Path nesting = root.resolve(NESTING);
    final Path destination = nesting.resolve(DESTINATION_NAME);
    Files.createDirectories(nesting);
    Files.write(archive, archiveBytes);
    final List<Path> before = listTree(scope);
    try {
      IoUtils.unzip(archive, destination);
    } catch (final FileOperationException | ZipEntryIntegrityException exception) {
      // a broken or unsafe archive; what matters is where anything was written
    }

    final List<Path> after = listTree(scope);
    final Stream<Path> created = after.stream();
    final Stream<Path> escaped = created.filter(path -> !before.contains(path) && !path.startsWith(destination));
    final List<Path> outside = escaped.toList();
    assertEquals(List.of(), outside, "something was written outside the destination");

    final boolean survived = after.containsAll(before);
    assertTrue(survived, "preexisting paths survive extraction");

    final byte[] archiveAfter = Files.readAllBytes(archive);
    assertArrayEquals(archiveBytes, archiveAfter, "extraction preserves the original archive");
  }

  private static List<Path> listTree(final Path directory) throws IOException {
    try (final Stream<Path> paths = Files.walk(directory)) {
      return paths.toList();
    }
  }

  private static void deleteTree(final Path directory) throws IOException {
    Files.walkFileTree(directory, new TreeDeleter());
  }

  private static final class TreeDeleter extends SimpleFileVisitor<Path> {

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
      Files.delete(file);

      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path visited, final IOException failure) throws IOException {
      if (failure != null) {
        throw failure;
      }

      Files.delete(visited);

      return FileVisitResult.CONTINUE;
    }
  }
}

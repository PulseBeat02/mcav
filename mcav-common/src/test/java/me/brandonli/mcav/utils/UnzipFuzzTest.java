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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the extraction of zip archives, which the installers of mcav run on archives they download: whatever the
 * archive, nothing is written outside the destination, and a bad archive fails with one of the exceptions
 * {@link IOUtils#unzip(Path, Path)} documents. The seeds are archives shaped like those the tests of the method build: an
 * ordinary one, one that escapes with {@code ..}, one with an absolute entry, a nested one and an empty one.
 */
@Tag("fuzz")
final class UnzipFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void extractsOnlyIntoTheDestination(final byte[] archiveBytes) throws IOException {
    final Path root = Files.createTempDirectory("mcav-unzip-fuzz");
    try {
      final Path archive = root.resolve("archive.zip");
      // deep enough below the root that an entry has to climb out of nine folders before it could escape unseen
      final Path nesting = root.resolve("1/2/3/4/5/6/7/8");
      final Path destination = nesting.resolve("destination");
      Files.createDirectories(nesting);
      Files.write(archive, archiveBytes);
      final List<Path> before = listTree(root);
      try {
        IOUtils.unzip(archive, destination);
      } catch (final UncheckedIOException | ZipEntryIntegrityException refused) {
        // a broken or unsafe archive; what matters is where anything was written
      }
      final List<Path> after = listTree(root);
      final Stream<Path> created = after.stream();
      final Stream<Path> escaped = created.filter(path -> !before.contains(path) && !path.startsWith(destination));
      final List<Path> outside = escaped.toList();
      assertEquals(List.of(), outside, "something was written outside the destination");
    } finally {
      deleteTree(root);
    }
  }

  private static List<Path> listTree(final Path directory) throws IOException {
    try (final Stream<Path> paths = Files.walk(directory)) {
      return paths.toList();
    }
  }

  private static void deleteTree(final Path directory) throws IOException {
    Files.walkFileTree(
      directory,
      new SimpleFileVisitor<>() {
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
    );
  }
}

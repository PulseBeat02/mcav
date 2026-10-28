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
package me.brandonli.mcav.capability.installer.debian;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.InstallationException;
import org.junit.jupiter.api.Tag;

// Fuzzes the extraction of Debian packages, which mcav runs on packages it downloads: whatever the package, nothing is
// written outside the destination, no symbolic link leads out of it, and a bad package fails only with an
// InstallationException. The limits are small so that a compressed bomb cannot fill the heap. The seeds are packages
// with xz, gzip and uncompressed data, GNU and pax long names, a symbolic link that escapes, a chain of links, a hard
// link and a program with its library.
@Tag("fuzz")
final class DebianPackageExtractorFuzzTest {

  private static final String ROOT = "/";
  private static final String ARCHIVE = "/work/package.deb";
  private static final String DESTINATION = "/work/destination";

  private static final List<String> TREES = List.of("usr/lib", "lib", "usr/bin");
  private static final Configuration POSIX = Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build();

  private static final long MAXIMUM_ENTRY_SIZE = 1024L * 1024L;
  private static final long MAXIMUM_TOTAL_SIZE = 4L * 1024L * 1024L;

  @FuzzTest(maxDuration = "30s")
  void shouldExtractOnlyIntoTheDestination(final byte[] debianPackage) throws IOException {
    try (final FileSystem fileSystem = Jimfs.newFileSystem(POSIX)) {
      final Path root = fileSystem.getPath(ROOT);
      final Path archive = fileSystem.getPath(ARCHIVE);
      final Path destination = fileSystem.getPath(DESTINATION);
      Files.createDirectories(destination);
      Files.write(archive, debianPackage);
      final List<Path> before = this.listTree(root);
      this.extract(archive, destination);

      final List<Path> after = this.listTree(root);
      final List<Path> outside = after
        .stream()
        .filter(path -> !before.contains(path) && !path.startsWith(destination))
        .toList();
      assertEquals(List.of(), outside, "something was written outside the destination");

      final List<Path> escapingLinks = after
        .stream()
        .filter(path -> this.leadsOutside(path, destination))
        .toList();
      assertEquals(List.of(), escapingLinks, "a symbolic link leads out of the destination");

      final byte[] archiveAfter = Files.readAllBytes(archive);
      assertArrayEquals(debianPackage, archiveAfter, "extraction preserves the package");
    }
  }

  private void extract(final Path archive, final Path destination) {
    try {
      DebianPackageExtractor.extract(archive, destination, TREES, MAXIMUM_ENTRY_SIZE, MAXIMUM_TOTAL_SIZE);
    } catch (final InstallationException exception) {
      // a broken or unsafe package; what matters is where anything was written
    }
  }

  // a link that cannot be resolved, such as a dangling one, leads nowhere
  private boolean leadsOutside(final Path path, final Path destination) {
    if (!Files.isSymbolicLink(path)) {
      return false;
    }

    try {
      final Path resolved = path.toRealPath();
      return !resolved.startsWith(destination);
    } catch (final IOException exception) {
      return false;
    }
  }

  private List<Path> listTree(final Path directory) throws IOException {
    try (final Stream<Path> paths = Files.walk(directory)) {
      return paths.toList();
    }
  }
}

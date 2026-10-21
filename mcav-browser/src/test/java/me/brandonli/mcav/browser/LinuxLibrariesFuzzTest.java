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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import me.brandonli.mcav.utils.IoUtils;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.junit.jupiter.api.Tag;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

// The packages are pinned by their hashes, so this fuzzes a second line of defense: extraction fails with an
// IOException or writes only the pinned libraries, as plain files inside the target. The first byte of an input picks
// its layer: the whole package (0), its data.tar.xz (1), or its tar (2 and 3).
@Tag("fuzz")
final class LinuxLibrariesFuzzTest {

  private static final Map<String, String> PINNED = Map.of(
    "usr/lib/x86_64-linux-gnu/libmcavfuzz.so.1.2.3",
    "libmcavfuzz.so.1",
    "usr/lib/x86_64-linux-gnu/libmcavother.so.2",
    "libmcavother.so.2"
  );
  private static final int XZ_PRESET = 0;

  @FuzzTest(maxDuration = "30s")
  void shouldWriteOnlyThePinnedLibrariesOrRefuseThePackage(final byte[] input) throws IOException {
    if (input.length == 0) {
      return;
    }

    final byte[] layer = Arrays.copyOfRange(input, 1, input.length);
    final byte[] debianPackage = switch (input[0] & 0b11) {
      case 0 -> layer;
      case 1 -> this.debianPackageOf(layer);
      default -> this.debianPackageOf(this.xz(layer));
    };
    final Path root = Files.createTempDirectory("mcav-deb-fuzz");
    try {
      final Path file = Files.write(root.resolve("package.deb"), debianPackage);
      final Path target = Files.createDirectory(root.resolve("target"));
      final Outcome outcome = this.extract(file, target);
      this.check(root, file, target, outcome);
    } finally {
      IoUtils.deleteRecursively(root);
    }
  }

  private Outcome extract(final Path file, final Path target) {
    try {
      LinuxLibraries.extract(file, PINNED, target);

      return Outcome.EXTRACTED;
    } catch (final IOException exception) {
      return Outcome.REFUSED;
    }
  }

  private void check(final Path root, final Path file, final Path target, final Outcome outcome) throws IOException {
    final boolean supportsPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    final Set<String> written = new HashSet<>();
    final List<Path> all = this.listAllPaths(root);
    for (final Path path : all) {
      if (path.equals(root) || path.equals(file) || path.equals(target)) {
        continue;
      }
      final Path parent = path.getParent();
      assertEquals(target, parent, () -> "outside the target or in a folder of its own: " + path);
      final boolean regularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
      assertTrue(regularFile, () -> "not a plain file: " + path);
      final String name = path.getFileName().toString();
      final boolean pinned = PINNED.containsValue(name);
      assertTrue(pinned, () -> "not a pinned library: " + path);
      if (supportsPosix) {
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        final boolean writableByOthers = permissions.contains(PosixFilePermission.OTHERS_WRITE);
        assertFalse(writableByOthers, () -> "writable by others: " + path);
      }
      written.add(name);
    }
    if (outcome == Outcome.EXTRACTED) {
      assertEquals(Set.copyOf(PINNED.values()), written, "a package that extracts holds every pinned library");
    }
  }

  private List<Path> listAllPaths(final Path root) throws IOException {
    try (final Stream<Path> paths = Files.walk(root)) {
      return paths.toList();
    } catch (final UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  private byte[] xz(final byte[] tar) throws IOException {
    final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (final XZOutputStream xz = new XZOutputStream(compressed, new LZMA2Options(XZ_PRESET))) {
      xz.write(tar);
    }
    return compressed.toByteArray();
  }

  private byte[] debianPackageOf(final byte[] data) throws IOException {
    final ByteArrayOutputStream debianPackage = new ByteArrayOutputStream();
    try (final ArArchiveOutputStream archive = new ArArchiveOutputStream(debianPackage)) {
      final byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
      archive.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
      archive.write(version);
      archive.closeArchiveEntry();
      archive.putArchiveEntry(new ArArchiveEntry("data.tar.xz", data.length));
      archive.write(data);
      archive.closeArchiveEntry();
    }
    return debianPackage.toByteArray();
  }

  private enum Outcome {
    EXTRACTED,
    REFUSED,
  }
}

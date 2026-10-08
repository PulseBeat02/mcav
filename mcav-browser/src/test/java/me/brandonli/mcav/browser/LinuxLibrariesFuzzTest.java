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
import me.brandonli.mcav.utils.IOUtils;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.junit.jupiter.api.Tag;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/**
 * Fuzzes the extraction of the pinned libraries out of a Debian package: an ar archive whose data.tar.xz holds them.
 * The packages are pinned by their hashes, so this is a second line of defense: whatever the package, extraction
 * either fails with an {@link IOException} or writes nothing but the pinned libraries, each a plain file of at most the
 * size limit under the name its pin gives it, all of them when it succeeds, and nothing outside the target folder.
 *
 * <p>The first byte of an input says what the rest of it is: the whole package (0), the data.tar.xz the fuzz target
 * puts into a package (1), or the tar the fuzz target compresses and puts into a package (2 and 3), so the fuzzer can
 * work on each layer's format. The seeds are packages built like real ones, with the libraries, other files, links,
 * folders and names with and without {@code ./}, and their tar and xz layers.
 */
@Tag("fuzz")
final class LinuxLibrariesFuzzTest {

  private static final Map<String, String> PINNED = Map.of(
    "usr/lib/x86_64-linux-gnu/libmcavfuzz.so.1.2.3",
    "libmcavfuzz.so.1",
    "usr/lib/x86_64-linux-gnu/libmcavother.so.2",
    "libmcavother.so.2"
  );
  private static final long MAX_FILE_BYTES = 1024;
  private static final int MAX_ENTRIES = 32;
  private static final int XZ_PRESET = 0;

  @FuzzTest(maxDuration = "30s")
  void writesOnlyThePinnedLibrariesOrRefusesThePackage(final byte[] input) throws IOException {
    if (input.length == 0) {
      return;
    }
    final byte[] layer = Arrays.copyOfRange(input, 1, input.length);
    final byte[] deb = switch (input[0] & 0b11) {
      case 0 -> layer;
      case 1 -> debOf(layer);
      default -> debOf(xz(layer));
    };
    final Path root = Files.createTempDirectory("mcav-deb-fuzz");
    try {
      final Path file = Files.write(root.resolve("package.deb"), deb);
      final Path target = Files.createDirectory(root.resolve("target"));
      boolean extracted;
      try {
        LinuxLibraries.extract(file, PINNED, target, MAX_FILE_BYTES, MAX_ENTRIES);
        extracted = true;
      } catch (final IOException refused) {
        extracted = false;
      }
      check(root, file, target, extracted);
    } finally {
      IOUtils.deleteRecursively(root);
    }
  }

  private static void check(final Path root, final Path file, final Path target, final boolean extracted) throws IOException {
    final boolean supportsPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    final Set<String> written = new HashSet<>();
    try (final Stream<Path> paths = Files.walk(root)) {
      final List<Path> all = paths.toList();
      for (final Path path : all) {
        if (path.equals(root) || path.equals(file) || path.equals(target)) {
          continue;
        }
        assertEquals(target, path.getParent(), () -> "outside the target or in a folder of its own: " + path);
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), () -> "not a plain file: " + path);
        final String name = path.getFileName().toString();
        assertTrue(PINNED.containsValue(name), () -> "not a pinned library: " + path);
        assertTrue(Files.size(path) <= MAX_FILE_BYTES, () -> "larger than the limit: " + path);
        if (supportsPosix) {
          final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
          assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE), () -> "writable by others: " + path);
        }
        written.add(name);
      }
    } catch (final UncheckedIOException exception) {
      throw exception.getCause();
    }
    if (extracted) {
      assertEquals(Set.copyOf(PINNED.values()), written, "a package that extracts holds every pinned library");
    }
  }

  private static byte[] xz(final byte[] tar) throws IOException {
    final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (final XZOutputStream xz = new XZOutputStream(compressed, new LZMA2Options(XZ_PRESET))) {
      xz.write(tar);
    }
    return compressed.toByteArray();
  }

  private static byte[] debOf(final byte[] data) throws IOException {
    final ByteArrayOutputStream deb = new ByteArrayOutputStream();
    try (final ArArchiveOutputStream archive = new ArArchiveOutputStream(deb)) {
      final byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
      archive.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
      archive.write(version);
      archive.closeArchiveEntry();
      archive.putArchiveEntry(new ArArchiveEntry("data.tar.xz", data.length));
      archive.write(data);
      archive.closeArchiveEntry();
    }
    return deb.toByteArray();
  }
}

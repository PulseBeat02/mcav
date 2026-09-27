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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import me.brandonli.mcav.utils.IOUtils;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the extraction of the CEF archive with arbitrary tar streams, compressed by the fuzz target itself so the
 * fuzzer works on the tar format rather than on gzip. The natives jar is pinned by its hash, so this is a second line
 * of defense: whatever the archive, extraction either fails with an {@link IOException} or leaves only plain files and
 * folders inside the target folder, none of them writable by others, and never more bytes than its limit.
 */
@Tag("fuzz")
final class ArchiveExtractorFuzzTest {

  private static final int MAX_ENTRIES = 64;
  private static final long MAX_BYTES = 1 << 20;

  @FuzzTest(maxDuration = "30s")
  void extractsOnlyPlainFilesInsideTheTarget(final FuzzedDataProvider data) throws IOException {
    final byte[] tar = data.consumeRemainingAsBytes();
    final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (final GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
      gzip.write(tar);
    }
    final Path root = Files.createTempDirectory("mcav-extract-fuzz");
    try {
      final Path target = Files.createDirectory(root.resolve("target"));
      try {
        new ArchiveExtractor(MAX_ENTRIES, MAX_BYTES).extract(new ByteArrayInputStream(compressed.toByteArray()), target);
      } catch (final IOException refused) {
        // malformed or breaking a rule; what was written so far is checked all the same
      }
      check(root, target);
    } finally {
      IOUtils.deleteRecursively(root);
    }
  }

  private static void check(final Path root, final Path target) throws IOException {
    final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    try (final Stream<Path> paths = Files.walk(root)) {
      final List<Path> all = paths.toList();
      long bytes = 0L;
      for (final Path path : all) {
        assertFalse(Files.isSymbolicLink(path), () -> "a link: " + path);
        assertTrue(path.equals(root) || path.startsWith(target), () -> "outside the target: " + path);
        final boolean regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        assertTrue(regular || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), () -> "neither file nor folder: " + path);
        if (regular) {
          bytes += Files.size(path);
          if (posix) {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE), () -> "writable by others: " + path);
          }
        }
      }
      final long total = bytes;
      assertTrue(total <= MAX_BYTES, () -> total + " bytes");
    } catch (final UncheckedIOException exception) {
      throw exception.getCause();
    }
  }
}

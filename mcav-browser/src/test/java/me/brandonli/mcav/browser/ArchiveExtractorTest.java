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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveExtractorTest {

  @TempDir
  Path target;

  /**
   * Builds a gzip-compressed tar archive.
   */
  static final class Archive {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final TarArchiveOutputStream tar;

    Archive() throws IOException {
      this.tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(this.bytes));
      this.tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
    }

    Archive file(final String name, final int mode, final String content) throws IOException {
      final byte[] data = content.getBytes(StandardCharsets.UTF_8);
      // keep absolute names as they are, which the archive library would otherwise make relative
      final TarArchiveEntry entry = new TarArchiveEntry(name, true);
      entry.setMode(mode);
      entry.setSize(data.length);
      this.tar.putArchiveEntry(entry);
      this.tar.write(data);
      this.tar.closeArchiveEntry();
      return this;
    }

    Archive folder(final String name) throws IOException {
      final TarArchiveEntry entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
      entry.setMode(0777);
      this.tar.putArchiveEntry(entry);
      this.tar.closeArchiveEntry();
      return this;
    }

    Archive link(final String name, final String target, final byte type) throws IOException {
      final TarArchiveEntry entry = new TarArchiveEntry(name, type);
      entry.setLinkName(target);
      this.tar.putArchiveEntry(entry);
      this.tar.closeArchiveEntry();
      return this;
    }

    InputStream finish() throws IOException {
      this.tar.close();
      return new ByteArrayInputStream(this.bytes.toByteArray());
    }
  }

  @Test
  void filesAndFoldersAreExtracted() throws IOException {
    final InputStream archive = new Archive()
      .folder("./")
      .folder("locales")
      .file("locales/en-US.pak", 0644, "english")
      .file("libcef.so", 0755, "library")
      .file("nested/deeper/file.txt", 0600, "made its own folders")
      .finish();
    new ArchiveExtractor().extract(archive, this.target);
    assertEquals("english", Files.readString(this.target.resolve("locales/en-US.pak")));
    assertEquals("library", Files.readString(this.target.resolve("libcef.so")));
    assertEquals("made its own folders", Files.readString(this.target.resolve("nested/deeper/file.txt")));
    assertTrue(Files.isDirectory(this.target.resolve("locales")));
  }

  @Test
  void executableFilesStayExecutableAndNothingBecomesWritableForOthers() throws IOException {
    final boolean posix = this.target.getFileSystem().supportedFileAttributeViews().contains("posix");
    assumeTrue(posix, "POSIX permissions");
    final InputStream archive = new Archive().file("jcef_helper", 0777, "helper").file("resources.pak", 0666, "data").finish();
    new ArchiveExtractor().extract(archive, this.target);
    final Set<PosixFilePermission> helper = Files.getPosixFilePermissions(this.target.resolve("jcef_helper"));
    final Set<PosixFilePermission> data = Files.getPosixFilePermissions(this.target.resolve("resources.pak"));
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), helper);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), data);
  }

  @Test
  void theArchiveStreamStaysOpen() throws IOException {
    final byte[] archive = new Archive().file("a", 0644, "a").finish().readAllBytes();
    final boolean[] closed = { false };
    final InputStream tracked = new ByteArrayInputStream(archive) {
      @Override
      public void close() {
        closed[0] = true;
      }
    };
    new ArchiveExtractor().extract(tracked, this.target);
    assertFalse(closed[0]);
  }

  @Test
  void linksAndSpecialFilesAreRefused() throws IOException {
    final InputStream symbolic = new Archive().link("current", "../../etc", TarConstants.LF_SYMLINK).finish();
    final IOException symbolicFailure = assertThrows(IOException.class, () -> new ArchiveExtractor().extract(symbolic, this.target));
    assertEquals("The archive entry current is not a plain file or folder", symbolicFailure.getMessage());
    final InputStream hard = new Archive().file("a", 0644, "a").link("b", "a", TarConstants.LF_LINK).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(hard, this.target));
    final InputStream fifo = new Archive().link("pipe", "", TarConstants.LF_FIFO).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(fifo, this.target));
    final InputStream character = new Archive().link("tty", "", TarConstants.LF_CHR).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(character, this.target));
    final InputStream block = new Archive().link("disk", "", TarConstants.LF_BLK).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(block, this.target));
    assertFalse(Files.exists(this.target.resolve("current")));
  }

  @Test
  void namesThatLeaveTheFolderAreRefused() throws IOException {
    for (final String name : new String[] { "../escape.txt", "a/../../escape.txt", "/etc/cron.d/evil" }) {
      final InputStream archive = new Archive().file(name, 0644, "x").finish();
      assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target), name);
    }
    assertFalse(Files.exists(this.target.getParent().resolve("escape.txt")));
  }

  @Test
  void entryNamesAreResolvedStrictly() throws IOException {
    final Path root = this.target.toRealPath();
    assertEquals(root.resolve("a/b"), ArchiveExtractor.resolve(root, "a/b"));
    // a name that climbs, even back into the folder, is refused
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a/../b"));
    assertEquals(root, ArchiveExtractor.resolve(root, "./"));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, ""));
    final IOException nul = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a\u0000b"));
    assertEquals("The archive entry name is not allowed: a\u0000b", nul.getMessage());
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a\\b"));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, ".."));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, root.resolve("x").toString()));
  }

  @Test
  void anEntryBelowALinkIsRefused() throws IOException {
    final Path root = this.target.toRealPath();
    final Path outside = Files.createTempDirectory("mcav-archive-outside");
    try {
      Files.createSymbolicLink(root.resolve("link"), outside);
      final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "link/file"));
      assertEquals("The archive entry name passes a link: link/file", failure.getMessage());
      // every folder on the way is checked, not only the last one
      assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "link/deeper/file"));
    } catch (final UnsupportedOperationException | IOException exception) {
      assumeTrue(false, "symbolic links are not available: " + exception);
    } finally {
      Files.deleteIfExists(root.resolve("link"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void aFileSystemWithoutPosixPermissionsGetsTheFilesWithoutThem() throws IOException {
    final InputStream archive = new Archive().file("libcef.so", 0755, "library").finish();
    new ArchiveExtractor().extract(archive, this.target, false);
    assertEquals("library", Files.readString(this.target.resolve("libcef.so")));
  }

  @Test
  void anArchiveWithTooManyEntriesIsRefused() throws IOException {
    final InputStream archive = new Archive().file("a", 0644, "a").file("b", 0644, "b").file("c", 0644, "c").finish();
    final IOException failure = assertThrows(IOException.class, () -> new ArchiveExtractor(2, 1000).extract(archive, this.target));
    assertEquals("The archive has more than 2 entries", failure.getMessage());
  }

  @Test
  void anArchiveThatExtractsToTooManyBytesIsRefused() throws IOException {
    final InputStream archive = new Archive().file("a", 0644, "12345").file("b", 0644, "678").finish();
    final IOException failure = assertThrows(IOException.class, () -> new ArchiveExtractor(10, 7).extract(archive, this.target));
    assertEquals("The archive extracts to more than the allowed size", failure.getMessage());
    // exactly the limit is fine
    new ArchiveExtractor(10, 8).extract(new Archive().file("c", 0644, "12345").file("d", 0644, "678").finish(), this.target);
  }

  @Test
  void anExistingFileIsNeverOverwritten() throws IOException {
    Files.writeString(this.target.resolve("a"), "original");
    final InputStream archive = new Archive().file("a", 0644, "replacement").finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target));
    assertEquals("original", Files.readString(this.target.resolve("a")));
  }

  @Test
  void aStreamThatIsNotAnArchiveFails() {
    final InputStream garbage = new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(garbage, this.target));
  }

  @Test
  void anArchiveWithExactlyTheLargestNumberOfEntriesIsExtracted() throws IOException {
    final InputStream archive = new Archive().file("a", 0644, "a").file("b", 0644, "b").finish();
    new ArchiveExtractor(2, 1000).extract(archive, this.target);
    assertTrue(Files.isRegularFile(this.target.resolve("b")));
  }

  @Test
  void aNameThatStartsWithABackslashIsRefused() {
    final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(this.target, "\\evil"));
    assertEquals("The archive entry name is not allowed: \\evil", failure.getMessage());
  }
}

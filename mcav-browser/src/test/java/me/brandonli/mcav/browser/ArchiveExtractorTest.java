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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveExtractorTest {

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String POSIX_MISSING_MESSAGE = "This file system has no POSIX permissions!";
  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

  private static final int REGULAR_MODE = 0644;
  private static final int EXECUTABLE_MODE = 0755;

  private static final String LOCALES_FOLDER = "locales";

  @TempDir
  Path target;

  @Test
  void shouldExtractFilesAndFolders() throws IOException {
    final InputStream archive = new Archive()
      .folder("./")
      .folder(LOCALES_FOLDER)
      .file("locales/en-US.pak", REGULAR_MODE, "english")
      .file("libcef.so", EXECUTABLE_MODE, "library")
      .file("nested/deeper/file.txt", 0600, "made its own folders")
      .finish();
    new ArchiveExtractor().extract(archive, this.target);
    final String localeText = Files.readString(this.target.resolve("locales/en-US.pak"));
    assertEquals("english", localeText);
    final String libraryText = Files.readString(this.target.resolve("libcef.so"));
    assertEquals("library", libraryText);
    final String nestedText = Files.readString(this.target.resolve("nested/deeper/file.txt"));
    assertEquals("made its own folders", nestedText);
    final boolean localesExtractedAsFolder = Files.isDirectory(this.target.resolve(LOCALES_FOLDER));
    assertTrue(localesExtractedAsFolder);
  }

  @Test
  void shouldKeepExecutableFilesExecutableAndMakeNothingWritableForOthers() throws IOException {
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    final InputStream archive = new Archive().file("jcef_helper", 0777, "helper").file("resources.pak", 0666, "data").finish();
    new ArchiveExtractor().extract(archive, this.target);
    final Set<PosixFilePermission> helper = Files.getPosixFilePermissions(this.target.resolve("jcef_helper"));
    final Set<PosixFilePermission> data = Files.getPosixFilePermissions(this.target.resolve("resources.pak"));
    assertEquals(PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS), helper);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), data);
  }

  @Test
  void shouldMakeFoldersWritableByTheOwnerOnlyWhateverTheUmask() throws IOException {
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    final InputStream archive = new Archive().folder(LOCALES_FOLDER).file("swiftshader/deeper/libvk.so", EXECUTABLE_MODE, "vk").finish();
    new ArchiveExtractor().extract(archive, this.target);
    final Set<PosixFilePermission> owner = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    final Set<PosixFilePermission> localesPermissions = Files.getPosixFilePermissions(this.target.resolve(LOCALES_FOLDER));
    assertEquals(owner, localesPermissions);
    final Set<PosixFilePermission> swiftshaderPermissions = Files.getPosixFilePermissions(this.target.resolve("swiftshader"));
    assertEquals(owner, swiftshaderPermissions);
    final Set<PosixFilePermission> deeperPermissions = Files.getPosixFilePermissions(this.target.resolve("swiftshader/deeper"));
    assertEquals(owner, deeperPermissions);
  }

  @Test
  void shouldCreateFoldersWithoutPermissionsWhereTheFileSystemHasNone() throws IOException {
    final Path folder = this.target.resolve("a/b");
    ArchiveExtractor.createFolders(folder, PosixPermissions.SKIPPED);
    final boolean folderCreated = Files.isDirectory(folder);
    assertTrue(folderCreated);
    ArchiveExtractor.createFolders(folder);
    final boolean folderKept = Files.isDirectory(folder);
    assertTrue(folderKept, "an existing folder is left as it is");
  }

  @Test
  void shouldKeepTheArchiveStreamOpen() throws IOException {
    final byte[] archive = new Archive().file("a", REGULAR_MODE, "a").finish().readAllBytes();
    final boolean[] closed = { false };
    final InputStream tracked = new ByteArrayInputStream(archive) {
      @Override
      public void close() {
        closed[0] = true;
      }
    };
    new ArchiveExtractor().extract(tracked, this.target);
    final boolean streamClosed = closed[0];
    assertFalse(streamClosed);
  }

  @Test
  void shouldRefuseLinksAndSpecialFiles() throws IOException {
    final InputStream symbolic = new Archive().link("current", "../../etc", TarConstants.LF_SYMLINK).finish();
    final IOException symbolicFailure = assertThrows(IOException.class, () -> new ArchiveExtractor().extract(symbolic, this.target));
    final String message = symbolicFailure.getMessage();
    assertEquals("Archive entry current is not a plain file or folder!", message);
    final InputStream hard = new Archive().file("a", REGULAR_MODE, "a").link("b", "a", TarConstants.LF_LINK).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(hard, this.target));
    final InputStream fifo = new Archive().link("pipe", "", TarConstants.LF_FIFO).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(fifo, this.target));
    final InputStream character = new Archive().link("tty", "", TarConstants.LF_CHR).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(character, this.target));
    final InputStream block = new Archive().link("disk", "", TarConstants.LF_BLK).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(block, this.target));
    final boolean linkWritten = Files.exists(this.target.resolve("current"));
    assertFalse(linkWritten);
  }

  @Test
  void shouldRefuseNamesThatLeaveTheFolder() throws IOException {
    for (final String name : new String[] { "../escape.txt", "a/../../escape.txt", "/etc/cron.d/evil" }) {
      final InputStream archive = new Archive().file(name, REGULAR_MODE, "x").finish();
      assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target), name);
    }
    final boolean escapeWritten = Files.exists(this.target.getParent().resolve("escape.txt"));
    assertFalse(escapeWritten);
  }

  @Test
  void shouldResolveEntryNamesStrictly() throws IOException {
    final Path root = this.target.toRealPath();
    final Path nestedEntry = ArchiveExtractor.resolve(root, "a/b");
    assertEquals(root.resolve("a/b"), nestedEntry);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a/../b"));
    final Path dotSlashEntry = ArchiveExtractor.resolve(root, "./");
    assertEquals(root, dotSlashEntry);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, ""));
    final IOException nullCharacter = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a\u0000b"));
    final String message = nullCharacter.getMessage();
    assertEquals("Archive entry name a\u0000b is not allowed!", message);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "a\\b"));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, ".."));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, root.resolve("x").toString()));
  }

  @Test
  void shouldRefuseAnEntryBelowALink() throws IOException {
    final Path root = this.target.toRealPath();
    final Path outside = Files.createTempDirectory("mcav-archive-outside");
    try {
      Files.createSymbolicLink(root.resolve("link"), outside);
      final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "link/file"));
      final String message = failure.getMessage();
      assertEquals("Archive entry name link/file passes a link!", message);
      assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, "link/deeper/file"));
    } catch (final UnsupportedOperationException | IOException exception) {
      final String message = "Symbolic links are not available here (%s)!".formatted(exception);
      assumeTrue(false, message);
    } finally {
      Files.deleteIfExists(root.resolve("link"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void shouldExtractTheFilesWithoutPermissionsOnAFileSystemWithoutPosixPermissions() throws IOException {
    final InputStream archive = new Archive().file("libcef.so", EXECUTABLE_MODE, "library").finish();
    new ArchiveExtractor().extract(archive, this.target, PosixPermissions.SKIPPED);
    final String libraryText = Files.readString(this.target.resolve("libcef.so"));
    assertEquals("library", libraryText);
  }

  @Test
  void shouldRefuseAnArchiveWithTooManyEntries() throws IOException {
    final InputStream archive = new Archive()
      .file("a", REGULAR_MODE, "a")
      .file("b", REGULAR_MODE, "b")
      .file("c", REGULAR_MODE, "c")
      .finish();
    final IOException failure = assertThrows(IOException.class, () -> new ArchiveExtractor(2, 1000).extract(archive, this.target));
    final String message = failure.getMessage();
    assertEquals("The archive has more than 2 entries!", message);
  }

  @Test
  void shouldRefuseAnArchiveThatExtractsToTooManyBytes() throws IOException {
    final InputStream archive = new Archive().file("a", REGULAR_MODE, "12345").file("b", REGULAR_MODE, "678").finish();
    final IOException failure = assertThrows(IOException.class, () -> new ArchiveExtractor(10, 7).extract(archive, this.target));
    final String message = failure.getMessage();
    assertEquals("The archive extracts to more than the allowed size!", message);
    new ArchiveExtractor(10, 8).extract(
      new Archive().file("c", REGULAR_MODE, "12345").file("d", REGULAR_MODE, "678").finish(),
      this.target
    );
  }

  @Test
  void shouldNeverOverwriteAnExistingFile() throws IOException {
    Files.writeString(this.target.resolve("a"), "original");
    final InputStream archive = new Archive().file("a", REGULAR_MODE, "replacement").finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target));
    final String originalText = Files.readString(this.target.resolve("a"));
    assertEquals("original", originalText);
  }

  @Test
  void shouldLeaveTheFileOfAFailedExtractionNoWiderThanItsFinalPermissions() throws IOException {
    final Path zip = this.target.resolve("posix.zip");
    final InputStream archive = new Archive().file("natives/libcef.so", REGULAR_MODE, "larger than allowed").finish();
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
      final Path folder = Files.createDirectory(zipped.getPath("/target"));
      assertThrows(IOException.class, () -> new ArchiveExtractor(10, 1).extract(archive, folder));

      final Set<PosixFilePermission> filePermissions = Files.getPosixFilePermissions(folder.resolve("natives/libcef.so"));
      final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(folder.resolve("natives"));
      assertEquals(PosixFilePermissions.fromString("rw-r--r--"), filePermissions, "zipfs would otherwise create it as rwxrwxrwx");
      assertEquals(PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS), folderPermissions);
    }
  }

  @Test
  void shouldFailOnAStreamThatIsNotAnArchive() {
    final InputStream garbage = new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(garbage, this.target));
  }

  @Test
  void shouldExtractAnArchiveWithExactlyTheLargestNumberOfEntries() throws IOException {
    final InputStream archive = new Archive().file("a", REGULAR_MODE, "a").file("b", REGULAR_MODE, "b").finish();
    new ArchiveExtractor(2, 1000).extract(archive, this.target);
    final boolean lastEntryExtracted = Files.isRegularFile(this.target.resolve("b"));
    assertTrue(lastEntryExtracted);
  }

  @Test
  void shouldRefuseANameThatStartsWithABackslash() {
    final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(this.target, "\\evil"));
    final String message = failure.getMessage();
    assertEquals("Archive entry name \\evil is not allowed!", message);
  }

  @Test
  void shouldTakeTheWriteOfTheGroupAndOfOthersAwayAndLeaveLinksAndTheRestAloneWhenTightening() throws IOException {
    assumeTrue(this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW), POSIX_MISSING_MESSAGE);
    final Path installation = Files.createDirectories(this.target.resolve("installation"));
    final Path inner = Files.createDirectories(installation.resolve(LOCALES_FOLDER));
    final Path groupWritable = Files.writeString(inner.resolve("group.pak"), "group");
    final Path othersWritable = Files.writeString(installation.resolve("others.so"), "others");
    final Path untouched = Files.writeString(installation.resolve("untouched.dat"), "untouched");
    final Path outside = Files.writeString(this.target.resolve("outside.txt"), "outside");
    Files.createSymbolicLink(installation.resolve("link"), outside);
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.setPosixFilePermissions(groupWritable, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.setPosixFilePermissions(othersWritable, PosixFilePermissions.fromString("rwxr-xrwx"));
    Files.setPosixFilePermissions(untouched, PosixFilePermissions.fromString("r--------"));
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-rw-rw-"));

    ArchiveExtractor.tighten(installation);

    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    assertEquals(PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS), installationPermissions);
    final Set<PosixFilePermission> innerPermissions = Files.getPosixFilePermissions(inner);
    assertEquals(PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS), innerPermissions);
    final Set<PosixFilePermission> groupWritablePermissions = Files.getPosixFilePermissions(groupWritable);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), groupWritablePermissions);
    final Set<PosixFilePermission> othersWritablePermissions = Files.getPosixFilePermissions(othersWritable);
    assertEquals(PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS), othersWritablePermissions);
    final Set<PosixFilePermission> untouchedPermissions = Files.getPosixFilePermissions(untouched);
    assertEquals(PosixFilePermissions.fromString("r--------"), untouchedPermissions);
    final Set<PosixFilePermission> outsidePermissions = Files.getPosixFilePermissions(outside);
    assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), outsidePermissions, "a link's target is not ours");
  }

  @Test
  void shouldSkipWhatItCannotReadOrChangeAndWhatIsNoFolderWhenTightening() throws IOException {
    final Path file = Files.writeString(this.target.resolve("file.txt"), "file");
    ArchiveExtractor.tighten(file);
    ArchiveExtractor.tighten(this.target.resolve("missing"));
    assumeTrue(this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW), POSIX_MISSING_MESSAGE);
    ArchiveExtractor.takeWriteAway(this.target.resolve("gone"));
    final Path closed = Files.createDirectories(this.target.resolve("closed"));
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("-wx------"));
    try {
      assumeTrue(!Files.isReadable(closed), "The user may read every folder, so none can be closed to it!");
      ArchiveExtractor.tighten(this.target);
      final Set<PosixFilePermission> closedPermissions = Files.getPosixFilePermissions(closed);
      assertEquals(PosixFilePermissions.fromString("-wx------"), closedPermissions);
    } finally {
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void shouldNotTightenAFileSystemWithoutPosixPermissions() throws IOException {
    final Path zip = this.target.resolve("tightened.zip");
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
      final Path folder = Files.createDirectories(zipped.getPath("/natives"));
      ArchiveExtractor.tighten(folder);
      final boolean folderKept = Files.isDirectory(folder);
      assertTrue(folderKept);
    }
  }

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
}

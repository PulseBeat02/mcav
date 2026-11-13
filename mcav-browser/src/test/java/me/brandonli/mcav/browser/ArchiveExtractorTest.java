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
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
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

  private static final String RESOURCES_FILE = "resources.pak";
  private static final String PIPE_ENTRY = "pipe";
  private static final String CHARACTER_DEVICE_ENTRY = "tty";
  private static final String BLOCK_DEVICE_ENTRY = "disk";
  private static final String NULL_CHARACTER_ENTRY = "a\u0000b";
  private static final String FOURTH_ENTRY = "d";
  private static final String ZIPPED_TARGET_FOLDER = "/target";
  private static final String BACKSLASH_PREFIX_ENTRY = "\\evil";
  private static final String GROUP_WRITABLE_FILE = "group.pak";
  private static final String ZIPPED_NATIVES_FOLDER = "/natives";
  private static final String OUTSIDE_FOLDER_PREFIX = "mcav-archive-outside";
  private static final String LOCALE_FILE = "locales/en-US.pak";
  private static final String LIBRARY_FILE = "libcef.so";
  private static final String NESTED_FILE = "nested/deeper/file.txt";
  private static final String HELPER_FILE = "jcef_helper";
  private static final String SWIFTSHADER_LIBRARY_FILE = "swiftshader/deeper/libvk.so";
  private static final String SWIFTSHADER_FOLDER = "swiftshader";
  private static final String SWIFTSHADER_DEEPER_FOLDER = "swiftshader/deeper";
  private static final String FOLDERS_ARCHIVE_FILE = "folders.zip";
  private static final String ZIPPED_NESTED_FOLDER = "/a/b";
  private static final String CURRENT_LINK = "current";
  private static final String PARENT_ESCAPE_FILE = "../escape.txt";
  private static final String NESTED_ESCAPE_FILE = "a/../../escape.txt";
  private static final String ABSOLUTE_ESCAPE_FILE = "/etc/cron.d/evil";
  private static final String ESCAPE_FILE = "escape.txt";
  private static final String NESTED_ENTRY = "a/b";
  private static final String PARENT_TRAVERSAL_ENTRY = "a/../b";
  private static final String BACKSLASH_ENTRY = "a\\b";
  private static final String LINK_NAME = "link";
  private static final String LINK_FILE = "link/file";
  private static final String LINK_DEEPER_FILE = "link/deeper/file";
  private static final String FIRST_ENTRY = "a";
  private static final String SECOND_ENTRY = "b";
  private static final String THIRD_ENTRY = "c";
  private static final String POSIX_ARCHIVE_FILE = "posix.zip";
  private static final String NATIVES_LIBRARY_FILE = "natives/libcef.so";
  private static final String NATIVES_FOLDER = "natives";
  private static final String INSTALLATION_FOLDER = "installation";
  private static final String OTHERS_LIBRARY_FILE = "others.so";
  private static final String UNTOUCHED_FILE = "untouched.dat";
  private static final String OUTSIDE_FILE = "outside.txt";
  private static final String PLAIN_FILE = "file.txt";
  private static final String MISSING_FILE = "missing";
  private static final String GONE_FILE = "gone";
  private static final String CLOSED_FOLDER = "closed";
  private static final String TIGHTENED_ARCHIVE_FILE = "tightened.zip";
  private static final String PERMISSIONLESS_ARCHIVE_FILE = "permissionless.zip";
  private static final String UNREADABLE_FILE = "unreadable";
  private static final String CURRENT_FOLDER = "./";
  private static final String PARENT_FOLDER = "..";
  private static final String ABSOLUTE_ENTRY_NAME = "x";

  private static final String ESCAPING_LINK_TARGET = "../../etc";

  private static final String LIBRARY_TEXT = "library";
  private static final String FIRST_ENTRY_TEXT = "a";
  private static final String SECOND_ENTRY_TEXT = "b";

  private static final String REGULAR_PERMISSIONS = "rw-r--r--";

  private static final String CREATE_OPTION = "create";

  private static final String ENABLED_OPTION = "true";

  private static final int ALL_PERMISSIONS_MODE = 0777;

  private static final int MAXIMUM_ARCHIVE_BYTES = 1000;

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String POSIX_MISSING_MESSAGE = "This file system has no POSIX permissions!";

  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

  private static final int REGULAR_MODE = 0644;
  private static final int EXECUTABLE_MODE = 0755;

  private static final String LOCALES_FOLDER = "locales";

  @TempDir
  private Path target;

  @Test
  void shouldExtractFilesAndFolders() throws IOException {
    final InputStream archive = new Archive()
      .folder(CURRENT_FOLDER)
      .folder(LOCALES_FOLDER)
      .file(LOCALE_FILE, REGULAR_MODE, "english")
      .file(LIBRARY_FILE, EXECUTABLE_MODE, LIBRARY_TEXT)
      .file(NESTED_FILE, 0600, "made its own folders")
      .finish();
    new ArchiveExtractor().extract(archive, this.target);
    final String localeText = Files.readString(this.target.resolve(LOCALE_FILE));
    assertEquals("english", localeText);

    final String libraryText = Files.readString(this.target.resolve(LIBRARY_FILE));
    assertEquals(LIBRARY_TEXT, libraryText);

    final String nestedText = Files.readString(this.target.resolve(NESTED_FILE));
    assertEquals("made its own folders", nestedText);

    final boolean localesExtractedAsFolder = Files.isDirectory(this.target.resolve(LOCALES_FOLDER));
    assertTrue(localesExtractedAsFolder);
  }

  @Test
  void shouldKeepExecutableFilesExecutableAndMakeNothingWritableForOthers() throws IOException {
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    final InputStream archive = new Archive().file(HELPER_FILE, ALL_PERMISSIONS_MODE, "helper").file(RESOURCES_FILE, 0666, "data").finish();
    new ArchiveExtractor().extract(archive, this.target);
    final Set<PosixFilePermission> helper = Files.getPosixFilePermissions(this.target.resolve(HELPER_FILE));
    final Set<PosixFilePermission> data = Files.getPosixFilePermissions(this.target.resolve(RESOURCES_FILE));
    final Set<PosixFilePermission> expectedHelperPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedHelperPermissions, helper);
    final Set<PosixFilePermission> expectedDataPermissions = PosixFilePermissions.fromString(REGULAR_PERMISSIONS);
    assertEquals(expectedDataPermissions, data);
  }

  @Test
  void shouldMakeFoldersWritableByTheOwnerOnlyWhateverTheUmask() throws IOException {
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    final InputStream archive = new Archive().folder(LOCALES_FOLDER).file(SWIFTSHADER_LIBRARY_FILE, EXECUTABLE_MODE, "vk").finish();
    new ArchiveExtractor().extract(archive, this.target);
    final Set<PosixFilePermission> owner = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    final Set<PosixFilePermission> localesPermissions = Files.getPosixFilePermissions(this.target.resolve(LOCALES_FOLDER));
    assertEquals(owner, localesPermissions);

    final Set<PosixFilePermission> swiftshaderPermissions = Files.getPosixFilePermissions(this.target.resolve(SWIFTSHADER_FOLDER));
    assertEquals(owner, swiftshaderPermissions);

    final Set<PosixFilePermission> deeperPermissions = Files.getPosixFilePermissions(this.target.resolve(SWIFTSHADER_DEEPER_FOLDER));
    assertEquals(owner, deeperPermissions);
  }

  @Test
  void shouldCreateFoldersWithoutPermissionsWhereTheFileSystemHasNone() throws IOException {
    final Path zip = this.target.resolve(FOLDERS_ARCHIVE_FILE);
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of(CREATE_OPTION, ENABLED_OPTION))) {
      final Path folder = zipped.getPath(ZIPPED_NESTED_FOLDER);
      ArchiveExtractor.createFolders(folder, PosixPermissions.SKIPPED);
      final boolean folderCreated = Files.isDirectory(folder);
      assertTrue(folderCreated);

      ArchiveExtractor.createFolders(folder);
      final boolean folderKept = Files.isDirectory(folder);
      assertTrue(folderKept, "an existing folder is left as it is");
    }
  }

  @Test
  void shouldKeepTheArchiveStreamOpen() throws IOException {
    final byte[] archive = new Archive().file(FIRST_ENTRY, REGULAR_MODE, FIRST_ENTRY_TEXT).finish().readAllBytes();
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
    final InputStream symbolic = new Archive().link(CURRENT_LINK, ESCAPING_LINK_TARGET, TarConstants.LF_SYMLINK).finish();
    final IOException symbolicFailure = assertThrows(IOException.class, () -> new ArchiveExtractor().extract(symbolic, this.target));
    final String message = symbolicFailure.getMessage();
    assertEquals("Archive entry current is not a plain file or folder!", message);

    final InputStream hard = new Archive()
      .file(FIRST_ENTRY, REGULAR_MODE, FIRST_ENTRY_TEXT)
      .link(SECOND_ENTRY, FIRST_ENTRY, TarConstants.LF_LINK)
      .finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(hard, this.target));

    final InputStream fifo = new Archive().link(PIPE_ENTRY, "", TarConstants.LF_FIFO).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(fifo, this.target));

    final InputStream character = new Archive().link(CHARACTER_DEVICE_ENTRY, "", TarConstants.LF_CHR).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(character, this.target));

    final InputStream block = new Archive().link(BLOCK_DEVICE_ENTRY, "", TarConstants.LF_BLK).finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(block, this.target));

    final boolean linkWritten = Files.exists(this.target.resolve(CURRENT_LINK));
    assertFalse(linkWritten);
  }

  @Test
  void shouldRefuseNamesThatLeaveTheFolder() throws IOException {
    for (final String name : new String[] { PARENT_ESCAPE_FILE, NESTED_ESCAPE_FILE, ABSOLUTE_ESCAPE_FILE }) {
      final InputStream archive = new Archive().file(name, REGULAR_MODE, "x").finish();
      assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target), name);
    }

    final boolean escapeWritten = Files.exists(this.target.getParent().resolve(ESCAPE_FILE));
    assertFalse(escapeWritten);
  }

  @Test
  void shouldResolveEntryNamesStrictly() throws IOException {
    final Path root = this.target.toRealPath();
    final Path nestedEntry = ArchiveExtractor.resolve(root, NESTED_ENTRY);
    final Path expectedNestedEntry = root.resolve(NESTED_ENTRY);
    assertEquals(expectedNestedEntry, nestedEntry);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, PARENT_TRAVERSAL_ENTRY));

    final Path dotSlashEntry = ArchiveExtractor.resolve(root, CURRENT_FOLDER);
    assertEquals(root, dotSlashEntry);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, ""));

    final IOException nullCharacter = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, NULL_CHARACTER_ENTRY));
    final String message = nullCharacter.getMessage();
    assertEquals("Archive entry name a\u0000b is not allowed!", message);
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, BACKSLASH_ENTRY));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, PARENT_FOLDER));
    assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, root.resolve(ABSOLUTE_ENTRY_NAME).toString()));
  }

  @Test
  void shouldRefuseAnEntryBelowALink() throws IOException {
    final Path root = this.target.toRealPath();
    final Path outside = Files.createTempDirectory(OUTSIDE_FOLDER_PREFIX);
    try {
      Files.createSymbolicLink(root.resolve(LINK_NAME), outside);
      final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, LINK_FILE));
      final String message = failure.getMessage();
      assertEquals("Archive entry name link/file passes a link!", message);
      assertThrows(IOException.class, () -> ArchiveExtractor.resolve(root, LINK_DEEPER_FILE));
    } catch (final UnsupportedOperationException | IOException exception) {
      final String message = "Symbolic links are not available here (%s)!".formatted(exception);
      assumeTrue(false, message);
    } finally {
      Files.deleteIfExists(root.resolve(LINK_NAME));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void shouldExtractTheFilesWithoutPermissionsOnAFileSystemWithoutPosixPermissions() throws IOException {
    final Path zip = this.target.resolve(PERMISSIONLESS_ARCHIVE_FILE);
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of(CREATE_OPTION, ENABLED_OPTION))) {
      final Path folder = Files.createDirectory(zipped.getPath(ZIPPED_TARGET_FOLDER));
      final InputStream archive = new Archive().file(LIBRARY_FILE, EXECUTABLE_MODE, LIBRARY_TEXT).finish();
      new ArchiveExtractor().extract(archive, folder);
      final String libraryText = Files.readString(folder.resolve(LIBRARY_FILE));
      assertEquals(LIBRARY_TEXT, libraryText);
    }
  }

  @Test
  void shouldRefuseAnArchiveWithTooManyEntries() throws IOException {
    final InputStream archive = new Archive()
      .file(FIRST_ENTRY, REGULAR_MODE, FIRST_ENTRY_TEXT)
      .file(SECOND_ENTRY, REGULAR_MODE, SECOND_ENTRY_TEXT)
      .file(THIRD_ENTRY, REGULAR_MODE, "c")
      .finish();
    final IOException failure = assertThrows(IOException.class, () ->
      new ArchiveExtractor(2, MAXIMUM_ARCHIVE_BYTES).extract(archive, this.target)
    );
    final String message = failure.getMessage();
    assertEquals("The archive has more than 2 entries!", message);
  }

  @Test
  void shouldRefuseAnArchiveThatExtractsToTooManyBytes() throws IOException {
    final InputStream archive = new Archive().file(FIRST_ENTRY, REGULAR_MODE, "12345").file(SECOND_ENTRY, REGULAR_MODE, "678").finish();
    final IOException failure = assertThrows(IOException.class, () -> new ArchiveExtractor(10, 7).extract(archive, this.target));
    final String message = failure.getMessage();
    assertEquals("The archive extracts to more than the allowed size!", message);

    new ArchiveExtractor(10, 8).extract(
      new Archive().file(THIRD_ENTRY, REGULAR_MODE, "12345").file(FOURTH_ENTRY, REGULAR_MODE, "678").finish(),
      this.target
    );
  }

  @Test
  void shouldNeverOverwriteAnExistingFile() throws IOException {
    Files.writeString(this.target.resolve(FIRST_ENTRY), "original");
    final InputStream archive = new Archive().file(FIRST_ENTRY, REGULAR_MODE, "replacement").finish();
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive, this.target));

    final String originalText = Files.readString(this.target.resolve(FIRST_ENTRY));
    assertEquals("original", originalText);
  }

  @Test
  void shouldLeaveTheFileOfAFailedExtractionNoWiderThanItsFinalPermissions() throws IOException {
    final Path zip = this.target.resolve(POSIX_ARCHIVE_FILE);
    final InputStream archive = new Archive().file(NATIVES_LIBRARY_FILE, REGULAR_MODE, "larger than allowed").finish();
    try (
      final FileSystem zipped = FileSystems.newFileSystem(
        zip,
        Map.of(CREATE_OPTION, ENABLED_OPTION, "enablePosixFileAttributes", ENABLED_OPTION)
      )
    ) {
      final Path folder = Files.createDirectory(zipped.getPath(ZIPPED_TARGET_FOLDER));
      assertThrows(IOException.class, () -> new ArchiveExtractor(10, 1).extract(archive, folder));

      final Set<PosixFilePermission> filePermissions = Files.getPosixFilePermissions(folder.resolve(NATIVES_LIBRARY_FILE));
      final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(folder.resolve(NATIVES_FOLDER));
      final Set<PosixFilePermission> expectedFilePermissions = PosixFilePermissions.fromString(REGULAR_PERMISSIONS);
      assertEquals(expectedFilePermissions, filePermissions, "zipfs would otherwise create it as rwxrwxrwx");
      final Set<PosixFilePermission> expectedFolderPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedFolderPermissions, folderPermissions);
    }
  }

  @Test
  void shouldFailOnAStreamThatIsNotAnArchive() {
    final InputStream garbage = new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> new ArchiveExtractor().extract(garbage, this.target));
  }

  @Test
  void shouldExtractAnArchiveWithExactlyTheLargestNumberOfEntries() throws IOException {
    final InputStream archive = new Archive()
      .file(FIRST_ENTRY, REGULAR_MODE, FIRST_ENTRY_TEXT)
      .file(SECOND_ENTRY, REGULAR_MODE, SECOND_ENTRY_TEXT)
      .finish();
    new ArchiveExtractor(2, MAXIMUM_ARCHIVE_BYTES).extract(archive, this.target);
    final boolean lastEntryExtracted = Files.isRegularFile(this.target.resolve(SECOND_ENTRY));
    assertTrue(lastEntryExtracted);
  }

  @Test
  void shouldRefuseANameThatStartsWithABackslash() {
    final IOException failure = assertThrows(IOException.class, () -> ArchiveExtractor.resolve(this.target, BACKSLASH_PREFIX_ENTRY));
    final String message = failure.getMessage();
    assertEquals("Archive entry name \\evil is not allowed!", message);
  }

  @Test
  void shouldTakeTheWriteOfTheGroupAndOfOthersAwayAndLeaveLinksAndTheRestAloneWhenTightening() throws IOException {
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    final Path installation = Files.createDirectories(this.target.resolve(INSTALLATION_FOLDER));
    final Path inner = Files.createDirectories(installation.resolve(LOCALES_FOLDER));
    final Path groupWritable = Files.writeString(inner.resolve(GROUP_WRITABLE_FILE), "group");
    final Path othersWritable = Files.writeString(installation.resolve(OTHERS_LIBRARY_FILE), "others");
    final Path untouched = Files.writeString(installation.resolve(UNTOUCHED_FILE), "untouched");
    final Path outside = Files.writeString(this.target.resolve(OUTSIDE_FILE), "outside");
    Files.createSymbolicLink(installation.resolve(LINK_NAME), outside);
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.setPosixFilePermissions(groupWritable, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.setPosixFilePermissions(othersWritable, PosixFilePermissions.fromString("rwxr-xrwx"));
    Files.setPosixFilePermissions(untouched, PosixFilePermissions.fromString("r--------"));
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-rw-rw-"));

    ArchiveExtractor.tighten(installation);

    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    final Set<PosixFilePermission> expectedInstallationPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedInstallationPermissions, installationPermissions);

    final Set<PosixFilePermission> innerPermissions = Files.getPosixFilePermissions(inner);
    final Set<PosixFilePermission> expectedInnerPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedInnerPermissions, innerPermissions);

    final Set<PosixFilePermission> groupWritablePermissions = Files.getPosixFilePermissions(groupWritable);
    final Set<PosixFilePermission> expectedGroupWritablePermissions = PosixFilePermissions.fromString(REGULAR_PERMISSIONS);
    assertEquals(expectedGroupWritablePermissions, groupWritablePermissions);

    final Set<PosixFilePermission> othersWritablePermissions = Files.getPosixFilePermissions(othersWritable);
    final Set<PosixFilePermission> expectedOthersWritablePermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedOthersWritablePermissions, othersWritablePermissions);

    final Set<PosixFilePermission> untouchedPermissions = Files.getPosixFilePermissions(untouched);
    final Set<PosixFilePermission> expectedUntouchedPermissions = PosixFilePermissions.fromString("r--------");
    assertEquals(expectedUntouchedPermissions, untouchedPermissions);

    final Set<PosixFilePermission> outsidePermissions = Files.getPosixFilePermissions(outside);
    final Set<PosixFilePermission> expectedOutsidePermissions = PosixFilePermissions.fromString("rw-rw-rw-");
    assertEquals(expectedOutsidePermissions, outsidePermissions, "a link's target is not ours");
  }

  @Test
  void shouldSkipWhatItCannotReadOrChangeAndWhatIsNoFolderWhenTightening() throws IOException {
    final Path file = Files.writeString(this.target.resolve(PLAIN_FILE), "file");
    ArchiveExtractor.tighten(file);
    ArchiveExtractor.tighten(this.target.resolve(MISSING_FILE));
    final boolean supportsPosix = this.target.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    assumeTrue(supportsPosix, POSIX_MISSING_MESSAGE);
    ArchiveExtractor.takeWriteAway(this.target.resolve(GONE_FILE));
    final Path closed = Files.createDirectories(this.target.resolve(CLOSED_FOLDER));
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("-wx------"));
    try {
      final boolean readable = Files.isReadable(closed);
      assumeTrue(!readable, "The user may read every folder, so none can be closed to it!");
      ArchiveExtractor.tighten(this.target);
      final Set<PosixFilePermission> closedPermissions = Files.getPosixFilePermissions(closed);
      final Set<PosixFilePermission> expectedClosedPermissions = PosixFilePermissions.fromString("-wx------");
      assertEquals(expectedClosedPermissions, closedPermissions);
    } finally {
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void shouldNotTightenAFileSystemWithoutPosixPermissions() throws IOException {
    final Path zip = this.target.resolve(TIGHTENED_ARCHIVE_FILE);
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of(CREATE_OPTION, ENABLED_OPTION))) {
      final Path folder = Files.createDirectories(zipped.getPath(ZIPPED_NATIVES_FOLDER));
      ArchiveExtractor.tighten(folder);
      final boolean folderKept = Files.isDirectory(folder);
      assertTrue(folderKept);
    }
  }

  @Test
  void shouldGoOnPastWhatItCannotReadWhenTightening() {
    final ArchiveExtractor.TighteningVisitor visitor = new ArchiveExtractor.TighteningVisitor();
    final Path unreadable = this.target.resolve(UNREADABLE_FILE);
    final FileVisitResult result = visitor.visitFileFailed(unreadable, new AccessDeniedException(unreadable.toString()));
    assertEquals(FileVisitResult.CONTINUE, result);
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
      entry.setMode(ALL_PERMISSIONS_MODE);
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

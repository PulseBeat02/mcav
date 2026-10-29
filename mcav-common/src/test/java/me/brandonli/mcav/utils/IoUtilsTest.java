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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.http.DownloadTooLargeException;
import me.brandonli.mcav.utils.http.HttpStatusException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IoUtilsTest {

  private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] DEF = "def".getBytes(StandardCharsets.US_ASCII);
  private static final Duration SHORT_RETRY_DELAY = Duration.ofMillis(1);
  private static final Duration RETRY_DELAY = Duration.ofMillis(100);
  private static final Duration SECOND_RETRY_DELAY = Duration.ofMillis(200);
  private static final Duration TOTAL_RETRY_DELAY = Duration.ofMillis(300);

  private static final Map<String, String> CREATE_ENVIRONMENT = Map.of("create", "true");

  private static final int MAXIMUM_MOVE_ATTEMPTS = 3;

  private static final int GENEROUS_LIMIT = 100;

  private static final String FILLER_CHARACTER = "x";

  private static final String FILE_CONTENT = "content";
  private static final String EVIL_CONTENT = "evil";
  private static final String ORIGINAL_CONTENT = "original";
  private static final String OVERWRITTEN_CONTENT = "overwritten";
  private static final String ONE_CONTENT = "1234";
  private static final String TWO_CONTENT = "5678";

  private static final String HOME_NAME = "home";
  private static final String TEMPORARY_NAME = "temporary";
  private static final String HOME_CACHE_PATH = ".mcav/cache";
  private static final String TEMPORARY_CACHE_PATH = "mcav/cache";
  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";
  private static final Pattern CACHE_FALLBACK_WARNING = Pattern.compile("cannot be written or cannot run programs");
  private static final Set<PosixFilePermission> READ_ONLY_PERMISSIONS = PosixFilePermissions.fromString("r-x------");
  private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");

  private static final String SAMPLE_DOWNLOADS_RESOURCE = "installers/sample.json";
  private static final String EMPTY_DOWNLOADS_RESOURCE = "installers/empty.json";
  private static final String INVALID_DOWNLOADS_RESOURCE = "installers/invalid.json";
  private static final String MISSING_DOWNLOADS_RESOURCE = "installers/missing.json";
  private static final String ANY_RESOURCE = "x";

  private static final String NESTED_DIRECTORIES_PATH = "a/b/c";
  private static final String FILE_NAME = "file.txt";
  private static final String CHILD_NAME = "child";
  private static final String NESTED_NEW_FILE_PATH = "x/y/file.txt";
  private static final String CHILD_FILE_NAME = "child.txt";
  private static final String RELATIVE_FILE_NAME = "relative.txt";
  private static final String TOOL_PATH = "/tool";
  private static final String MISSING_TOOL_PATH = "/missing";
  private static final String WINDOWS_TOOL_PATH = "C:\\tool.exe";
  private static final String HASHED_FILE_NAME = "abc.txt";
  private static final String MISSING_FILE_NAME = "missing.txt";
  private static final String NAMED_FILE_NAME = "name.txt";
  private static final String NEW_FILE_NAME = "new.txt";
  private static final String REPLACED_FILE_NAME = "target.txt";
  private static final String SOURCE_NAME = "source";
  private static final String TARGET_NAME = "target";
  private static final String TREE_DIRECTORY = "tree";
  private static final String NESTED_DIRECTORY = "nested";
  private static final String MISSING_NAME = "missing";

  private static final String FOLDER_ENTRY = "folder/";
  private static final String INNER_FILE_PATH = "folder/inner.txt";
  private static final String TOP_FILE_NAME = "top.txt";
  private static final String ESCAPE_FILE_NAME = "escape.txt";
  private static final String PARENT_ESCAPE_PATH = "../escape.txt";
  private static final String NESTED_ESCAPE_PATH = "nested/../../escape.txt";
  private static final String SIBLING_ESCAPE_PATH = "../safe-sibling/escape.txt";
  private static final String ABSOLUTE_ESCAPE_PATH = "/escape.txt";
  private static final String NUL_ESCAPE_PATH = "../\u0000escape.txt";
  private static final String BACKSLASH_ESCAPE_PATH = "../\\escape.txt";
  private static final String FOLDER_BACKSLASH_ESCAPE_PATH = "folder/\\escape.txt";
  private static final String SAFE_SIBLING_DIRECTORY = "safe-sibling";
  private static final String LINKED_KEPT_PATH = "linked/kept.txt";
  private static final String LINKED_CREATED_ENTRY = "linked/created/";
  private static final String ENTRY_FILE_NAME = "entry.txt";
  private static final String NESTED_FILE_PATH = "nested/file.txt";
  private static final String INSIDE_DOT_DOT_PATH = "folder/../inside.txt";
  private static final String INSIDE_FILE_NAME = "inside.txt";
  private static final String ONE_FILE_NAME = "one.txt";
  private static final String TWO_FILE_NAME = "two.txt";
  private static final String FIRST_FILE_NAME = "first.txt";
  private static final String SECOND_FILE_NAME = "second.txt";
  private static final String LARGE_FILE_NAME = "large.txt";
  private static final String EMPTY_FILE_NAME = "empty.txt";

  private static final String SAFE_DIRECTORY = "safe";
  private static final String EXTRACTED_DIRECTORY = "extracted";
  private static final String OUTSIDE_DIRECTORY = "outside";
  private static final String KEPT_FILE_NAME = "kept.txt";
  private static final String LINKED_DIRECTORY = "linked";
  private static final String CREATED_DIRECTORY = "created";
  private static final String OUTSIDE_FILE_NAME = "outside.txt";
  private static final String REAL_DIRECTORY = "real";
  private static final String ALIAS_DIRECTORY = "alias";
  private static final String INSIDE_DIRECTORY = "inside";
  private static final String ENTRY_LIMIT_DIRECTORY = "entry-limit";
  private static final String TOTAL_LIMIT_DIRECTORY = "total-limit";
  private static final String LIMITED_DIRECTORY = "limited";
  private static final String MULTI_READ_DIRECTORY = "multi-read-entry";
  private static final String ZERO_LIMIT_DIRECTORY = "zero-limit";
  private static final String INVALID_LIMIT_DIRECTORY = "invalid-limit";
  private static final String EXACT_DIRECTORY = "exact";
  private static final String OUTPUT_DIRECTORY = "out";
  private static final String TRUNCATED_OUTPUT_DIRECTORY = "truncated-output";
  private static final String COMMENTED_OUTPUT_DIRECTORY = "commented-output";
  private static final String LONG_COMMENT_OUTPUT_DIRECTORY = "long-comment-output";
  private static final String EMPTY_OUTPUT_DIRECTORY = "empty-out";

  private static final String ZIP_ROOT = "/";
  private static final String ARCHIVE_NAME = "archive.zip";
  private static final String CONTAINER_ARCHIVE_NAME = "container.zip";
  private static final String MISSING_ARCHIVE_NAME = "missing.zip";
  private static final String NOT_A_ZIP_ARCHIVE_NAME = "not-a-zip.zip";
  private static final String TRUNCATED_ARCHIVE_NAME = "truncated.zip";
  private static final String COMMENTED_ARCHIVE_NAME = "commented.zip";
  private static final String COMMENT_TRUNCATED_ARCHIVE_NAME = "comment-truncated.zip";
  private static final String LONG_COMMENT_ARCHIVE_NAME = "long-comment.zip";
  private static final String LONG_COMMENT_TRUNCATED_ARCHIVE_NAME = "long-comment-truncated.zip";
  private static final String TRUNCATED_HEADER_PREFIX = "header-";
  private static final String NAME_SEPARATOR = "-";
  private static final String ZIP_EXTENSION = ".zip";

  @TempDir
  private Path directory;

  private Path writeZip(final String... namesAndContents) throws IOException {
    final Path archive = this.directory.resolve(ARCHIVE_NAME);
    try (final OutputStream fileOutput = Files.newOutputStream(archive); final ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
      for (int index = 0; index < namesAndContents.length; index += 2) {
        final String name = namesAndContents[index];
        final String content = namesAndContents[index + 1];
        final ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes);
        zip.closeEntry();
      }
    }

    return archive;
  }

  @Test
  void shouldExtractTheFileNameOfUrls() {
    final String file = IoUtils.getFileNameFromUrl("https://example.com/videos/clip.mp4?quality=hd");
    final String directoryUrl = IoUtils.getFileNameFromUrl("https://example.com/videos/");
    final String noPath = IoUtils.getFileNameFromUrl("https://example.com");
    final String opaque = IoUtils.getFileNameFromUrl("mailto:someone@example.com");
    assertEquals("clip.mp4", file);
    assertEquals("", directoryUrl);
    assertEquals("", noPath);
    assertEquals("", opaque);
    assertThrows(IllegalArgumentException.class, () -> IoUtils.getFileNameFromUrl("https://example.com/a b"));
    assertThrows(NullPointerException.class, () -> IoUtils.getFileNameFromUrl(null));
  }

  @Test
  void shouldFindAFreePortForVnc() throws IOException {
    final int port = IoUtils.getNextFreeVncPort();
    final boolean inRange = port >= 5900 && port <= 65535;
    assertTrue(inRange);

    try (final ServerSocket occupied = new ServerSocket(0)) {
      final int occupiedPort = occupied.getLocalPort();
      assertThrows(MCAVException.class, () -> IoUtils.findFreePort(occupiedPort, occupiedPort));
    }
  }

  @Test
  void shouldSkipOccupiedPortsAndTryTheLastPortOfTheRange() throws IOException {
    final int firstFree = IoUtils.findFreePort(40_000, 40_999);
    final int found;
    try (final ServerSocket occupied = new ServerSocket(firstFree)) {
      final int occupiedPort = occupied.getLocalPort();
      found = IoUtils.findFreePort(occupiedPort, occupiedPort + 100);
    }

    final int probePort;
    try (final ServerSocket probe = new ServerSocket(0)) {
      probePort = probe.getLocalPort();
    }

    final int single = IoUtils.findFreePort(probePort, probePort);
    assertEquals(probePort, single, "a range of one port includes that port");

    final boolean skipped = found > firstFree && found <= firstFree + 100;
    assertTrue(skipped, "the occupied port is skipped, got " + found);
  }

  @Test
  void shouldCreateMissingDirectoriesOnly() throws IOException {
    final Path nested = this.directory.resolve(NESTED_DIRECTORIES_PATH);
    final boolean created = IoUtils.createDirectoryIfNotExists(nested);
    final boolean createdAgain = IoUtils.createDirectoryIfNotExists(nested);
    final boolean isDirectory = Files.isDirectory(nested);
    final Path file = this.directory.resolve(FILE_NAME);
    Files.createFile(file);
    final Path belowFile = file.resolve(CHILD_NAME);
    assertTrue(created);
    assertFalse(createdAgain);
    assertTrue(isDirectory);
    assertThrows(FileOperationException.class, () -> IoUtils.createDirectoryIfNotExists(belowFile));
    assertThrows(NullPointerException.class, () -> IoUtils.createDirectoryIfNotExists(null));
  }

  @Test
  void shouldCreateMissingFilesAndTheirParents() {
    final Path nested = this.directory.resolve(NESTED_NEW_FILE_PATH);
    final boolean created = IoUtils.createFileIfNotExists(nested);
    final boolean createdAgain = IoUtils.createFileIfNotExists(nested);
    final boolean isFile = Files.isRegularFile(nested);
    final Path belowFile = nested.resolve(CHILD_FILE_NAME);
    assertTrue(created);
    assertFalse(createdAgain);
    assertTrue(isFile);
    assertThrows(FileOperationException.class, () -> IoUtils.createFileIfNotExists(belowFile));
    assertThrows(NullPointerException.class, () -> IoUtils.createFileIfNotExists(null));
  }

  @Test
  void shouldCreateFilesWithoutAParentInTheWorkingDirectory() throws IOException {
    final Configuration configuration = Configuration.unix();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path relative = fileSystem.getPath(RELATIVE_FILE_NAME);
      final boolean created = IoUtils.createFileIfNotExists(relative);
      final boolean exists = Files.exists(relative);
      assertTrue(created);
      assertTrue(exists);
    }
  }

  @Test
  void shouldMarkFilesExecutableOnPosixFileSystems() throws IOException {
    final Configuration unix = Configuration.unix();
    final Configuration.Builder builder = unix.toBuilder();
    builder.setAttributeViews("basic", "owner", "posix", "unix");
    final Configuration configuration = builder.build();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path tool = fileSystem.getPath(TOOL_PATH);
      Files.createFile(tool);
      IoUtils.markExecutable(tool);
      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tool);
      final Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxr-xr-x");
      final Path missing = fileSystem.getPath(MISSING_TOOL_PATH);
      assertEquals(expected, permissions);
      assertThrows(FileOperationException.class, () -> IoUtils.markExecutable(missing));
    }
  }

  @Test
  void shouldLeaveFilesAloneOnOtherFileSystems() throws IOException {
    final Configuration configuration = Configuration.windows();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path tool = fileSystem.getPath(WINDOWS_TOOL_PATH);
      IoUtils.markExecutable(tool);
      final boolean exists = Files.exists(tool);
      assertFalse(exists, "nothing is touched without POSIX permissions");
    }

    assertThrows(NullPointerException.class, () -> IoUtils.markExecutable(null));
  }

  @Test
  void shouldHashFiles() throws IOException {
    final Path file = this.directory.resolve(HASHED_FILE_NAME);
    Files.write(file, ABC);
    final String sha256 = IoUtils.getSha256Hash(file);
    final String sha1 = IoUtils.getSha1Hash(file);
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256);
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1);
    assertThrows(FileOperationException.class, () -> IoUtils.getSha256Hash(missing));
    assertThrows(IllegalStateException.class, () -> IoUtils.createDigest("NO-SUCH-ALGORITHM"));
    assertThrows(NullPointerException.class, () -> IoUtils.getSha256Hash(null));
    assertThrows(NullPointerException.class, () -> IoUtils.getSha1Hash(null));
  }

  @Test
  void shouldFormatBytesAsZeroPaddedHex() {
    final String hex = IoUtils.bytesToHex(new byte[] { 0x0F, (byte) 0xAB, 0x00 });
    final String empty = IoUtils.bytesToHex(new byte[0]);
    assertEquals("0fab00", hex);
    assertEquals("", empty);
    assertThrows(NullPointerException.class, () -> IoUtils.bytesToHex(null));
  }

  @Test
  void shouldGetTheNameOfPaths() {
    final Path file = this.directory.resolve(NAMED_FILE_NAME);
    final Path root = this.directory.getRoot();
    final String name = IoUtils.getName(file);
    assertEquals(NAMED_FILE_NAME, name);
    assertThrows(IllegalArgumentException.class, () -> IoUtils.getName(root));
    assertThrows(NullPointerException.class, () -> IoUtils.getName(null));
  }

  @Test
  void shouldDownloadImagesIntoTheCache() throws IOException {
    try (
      final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(this.directory);
      final LocalHttpServer server = LocalHttpServer.start()
    ) {
      server.respond("/image.png", 200, ABC);
      server.respond("/image.png", 200, DEF);
      final URI imageUri = server.uri("/image.png");
      final URI missingUri = server.uri("/missing.png");
      final URI fileUri = URI.create("file:///image.png");
      final UriSource image = UriSource.uri(imageUri);
      final UriSource missing = UriSource.uri(missingUri);
      final UriSource file = UriSource.uri(fileUri);

      final Path downloaded = IoUtils.downloadImage(image);
      final byte[] content = Files.readAllBytes(downloaded);
      final Path cache = IoUtils.getCachedFolder();
      final Path home = redirected.getHome();
      final boolean inCache = downloaded.startsWith(cache);
      final boolean cacheInHome = cache.startsWith(home);

      assertArrayEquals(ABC, content);
      assertTrue(inCache);
      assertTrue(cacheInHome);

      final Path downloadedAgain = IoUtils.downloadImage(image);
      final byte[] contentAgain = Files.readAllBytes(downloadedAgain);

      assertEquals(downloaded, downloadedAgain);
      assertArrayEquals(DEF, contentAgain, "downloading the same URL again overwrites the cached copy");
      assertThrows(HttpStatusException.class, () -> IoUtils.downloadImage(missing));
      assertThrows(IllegalArgumentException.class, () -> IoUtils.downloadImage(file));
      assertThrows(NullPointerException.class, () -> IoUtils.downloadImage(null));
    }
  }

  @Test
  void shouldStopAnImageDownloadLargerThanTheLimit() throws IOException {
    try (
      final TemporaryUserHome _ = TemporaryUserHome.redirectTo(this.directory);
      final LocalHttpServer server = LocalHttpServer.start()
    ) {
      final int tooLarge = Math.toIntExact(IoUtils.MAXIMUM_IMAGE_BYTES + 1);
      server.respond("/large.png", 200, new byte[tooLarge]);
      final URI largeUri = server.uri("/large.png");
      final UriSource large = UriSource.uri(largeUri);

      final DownloadTooLargeException thrown = assertThrows(DownloadTooLargeException.class, () -> IoUtils.downloadImage(large));
      final String message = thrown.getMessage();
      final String limit = "larger than the %s bytes".formatted(IoUtils.MAXIMUM_IMAGE_BYTES);
      final boolean namesTheLimit = message.contains(limit);
      final Path cache = IoUtils.getCachedFolder();
      try (final Stream<Path> cached = Files.list(cache)) {
        final long cachedCount = cached.count();
        assertTrue(namesTheLimit);
        assertEquals(0L, cachedCount, "neither the image nor its partial download is kept");
      }
    }
  }

  @Test
  void shouldMoveTheCacheToTheTemporaryDirectoryWhenTheHomeCannotHoldIt() throws IOException {
    final Path home = this.directory.resolve(HOME_NAME);
    Files.writeString(home, FILE_CONTENT);
    final Path temporary = this.directory.resolve(TEMPORARY_NAME);

    final Path cache = this.getCachedFolderIn(home, temporary);
    final Path expected = temporary.resolve(TEMPORARY_CACHE_PATH);
    final boolean created = Files.isDirectory(cache);
    assertEquals(expected, cache, "a regular file where the home directory should be cannot hold the cache");
    assertTrue(created);
  }

  @Test
  void shouldFailWhenNeitherCacheFolderCanBeCreated() throws IOException {
    final Path home = this.directory.resolve(HOME_NAME);
    Files.writeString(home, FILE_CONTENT);
    final Path temporary = this.directory.resolve(TEMPORARY_NAME);
    Files.writeString(temporary, FILE_CONTENT);

    assertThrows(FileOperationException.class, () -> this.getCachedFolderIn(home, temporary));
  }

  @Test
  void shouldMoveTheCacheToTheTemporaryDirectoryWhenTheHomeCacheIsReadOnly() throws IOException {
    final Path home = this.directory.resolve(HOME_NAME);
    final Path homeCache = Files.createDirectories(home.resolve(HOME_CACHE_PATH));
    final Path temporary = this.directory.resolve(TEMPORARY_NAME);
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    Files.setPosixFilePermissions(homeCache, READ_ONLY_PERMISSIONS);
    try {
      final boolean writable = Files.isWritable(homeCache);
      Assumptions.assumeFalse(writable, "The user can write every directory!");

      final Path cache = this.getCachedFolderIn(home, temporary);
      final Path expected = temporary.resolve(TEMPORARY_CACHE_PATH);
      assertEquals(expected, cache);
    } finally {
      Files.setPosixFilePermissions(homeCache, OWNER_DIRECTORY_PERMISSIONS);
    }
  }

  @Test
  void shouldMoveTheCacheToTheTemporaryDirectoryWhenTheHomeCacheCannotRunPrograms() throws IOException {
    final Path home = this.directory.resolve(HOME_NAME);
    final Path temporary = this.directory.resolve(TEMPORARY_NAME);

    final Path cache = this.getCachedFolderIn(home, temporary, file -> false);
    final Path expected = temporary.resolve(TEMPORARY_CACHE_PATH);
    assertEquals(expected, cache, "a home cache on a noexec mount cannot run the programs it holds");

    final Path homeCache = home.resolve(HOME_CACHE_PATH);
    try (final Stream<Path> leftovers = Files.list(homeCache)) {
      final long leftoverCount = leftovers.count();
      assertEquals(0, leftoverCount, "the file that probes the home cache is deleted");
    }
  }

  @Test
  void shouldWarnAboutTheCacheFallbackOnlyOnce() throws IOException {
    final Path home = this.directory.resolve(HOME_NAME);
    Files.writeString(home, FILE_CONTENT);
    final Path temporary = this.directory.resolve(TEMPORARY_NAME);
    final AtomicBoolean fallbackReported = new AtomicBoolean();

    final long firstWarnings = this.countCacheFallbackWarnings(home, temporary, fallbackReported);
    final long secondWarnings = this.countCacheFallbackWarnings(home, temporary, fallbackReported);
    assertEquals(1, firstWarnings, "the first fallback warns");
    assertEquals(0, secondWarnings, "a later fallback is silent");
  }

  private long countCacheFallbackWarnings(final Path home, final Path temporary, final AtomicBoolean fallbackReported) {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
    System.setErr(capture);
    try {
      this.getCachedFolderIn(home, temporary, Files::isExecutable, fallbackReported);
    } finally {
      System.setErr(original);
    }

    final String output = captured.toString(StandardCharsets.UTF_8);
    final Matcher warnings = CACHE_FALLBACK_WARNING.matcher(output);
    return warnings.results().count();
  }

  private Path getCachedFolderIn(final Path home, final Path temporary) {
    return this.getCachedFolderIn(home, temporary, Files::isExecutable);
  }

  private Path getCachedFolderIn(final Path home, final Path temporary, final Predicate<Path> executable) {
    final AtomicBoolean fallbackReported = new AtomicBoolean();
    return this.getCachedFolderIn(home, temporary, executable, fallbackReported);
  }

  private Path getCachedFolderIn(
    final Path home,
    final Path temporary,
    final Predicate<Path> executable,
    final AtomicBoolean fallbackReported
  ) {
    final String previousTemporary = System.getProperty(TEMPORARY_DIRECTORY_PROPERTY);
    final String rawTemporary = temporary.toString();
    System.setProperty(TEMPORARY_DIRECTORY_PROPERTY, rawTemporary);
    try (final TemporaryUserHome _ = TemporaryUserHome.redirectTo(home)) {
      return IoUtils.getCachedFolder(executable, fallbackReported);
    } finally {
      System.setProperty(TEMPORARY_DIRECTORY_PROPERTY, previousTemporary);
    }
  }

  @Test
  void shouldCreateAFolderThatCanRunPrograms() throws IOException {
    final Path folder = this.directory.resolve(HOME_CACHE_PATH);
    final Path file = this.directory.resolve(HOME_NAME);
    Files.writeString(file, FILE_CONTENT);
    final Path belowFile = file.resolve(HOME_CACHE_PATH);

    final boolean runnable = IoUtils.createRunnableFolder(folder);
    final boolean created = Files.isDirectory(folder);
    assertTrue(runnable);
    assertTrue(created);

    final boolean runnableBelowFile = IoUtils.createRunnableFolder(belowFile);
    assertFalse(runnableBelowFile, "a folder that cannot be created cannot run programs");
    assertThrows(NullPointerException.class, () -> IoUtils.createRunnableFolder(null));
  }

  @Test
  void shouldReadDownloadListsFromResources() {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(SAMPLE_DOWNLOADS_RESOURCE);
    final Download[] none = IoUtils.readDownloadsFromJsonResource(EMPTY_DOWNLOADS_RESOURCE);
    final Download linux = downloads[0];
    final Platform platform = linux.getPlatform();
    final Platform expectedPlatform = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
    final String url = linux.getUrl();
    assertEquals(2, downloads.length);
    assertEquals(0, none.length);
    assertEquals(expectedPlatform, platform);
    assertEquals("https://example.com/tool-linux", url);
    assertThrows(FileOperationException.class, () -> IoUtils.readDownloadsFromJsonResource(INVALID_DOWNLOADS_RESOURCE));
    assertThrows(FileOperationException.class, () -> IoUtils.readDownloadsFromJsonResource(MISSING_DOWNLOADS_RESOURCE));
    assertThrows(NullPointerException.class, () -> IoUtils.readDownloadsFromJsonResource(null));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
    strings = {
      "[null]",
      "[{}]",
      "[{\"url\": \"https://example.com/tool\"}]",
      "[{\"platform\": {\"os\": \"BEOS\", \"arch\": \"X86\", \"bits\": \"BITS_64\"}, \"url\": \"https://example.com/tool\"}]",
      "[{\"platform\": {\"os\": \"LINUX\", \"arch\": \"X86\", \"bits\": \"BITS_64\"}}]",
      "[{\"platform\": {\"os\": \"LINUX\", \"arch\": \"X86\", \"bits\": \"BITS_64\"}, \"url\": \" \"}]",
    }
  )
  void shouldRefuseIncompleteDownloads(final String json) {
    final Reader reader = new StringReader(json);

    assertThrows(IOException.class, () -> IoUtils.parseDownloads(reader));
  }

  @Test
  void shouldOpenResources() throws IOException {
    final String content;
    try (final Reader reader = IoUtils.getResourceAsStreamReader(EMPTY_DOWNLOADS_RESOURCE)) {
      final char[] buffer = new char[16];
      final int read = reader.read(buffer);
      content = new String(buffer, 0, read);
    }

    try (final InputStream stream = IoUtils.getResourceAsInputStream(EMPTY_DOWNLOADS_RESOURCE)) {
      final byte[] bytes = stream.readAllBytes();
      final String text = new String(bytes, StandardCharsets.UTF_8);
      assertEquals(content, text);
    }

    assertEquals("null\n", content);
  }

  @Test
  void shouldReportResourcesThatCannotBeFound() {
    assertThrows(FileOperationException.class, () -> IoUtils.getResourceAsInputStream(EMPTY_DOWNLOADS_RESOURCE, String.class));
    assertThrows(FileOperationException.class, () -> IoUtils.getResourceAsInputStream(MISSING_DOWNLOADS_RESOURCE));
    assertThrows(FileOperationException.class, () -> IoUtils.getResourceAsStreamReader(MISSING_DOWNLOADS_RESOURCE));
  }

  @Test
  void shouldRejectMissingResourceArguments() {
    assertThrows(NullPointerException.class, () -> IoUtils.getResourceAsStreamReader(null));
    assertThrows(NullPointerException.class, () -> IoUtils.getResourceAsInputStream(null));
    assertThrows(NullPointerException.class, () -> IoUtils.getResourceAsInputStream(null, IoUtils.class));
    assertThrows(NullPointerException.class, () -> IoUtils.getResourceAsInputStream(ANY_RESOURCE, null));
  }

  @Test
  void shouldExtractZipArchives() throws IOException {
    final Path archive = this.writeZip(FOLDER_ENTRY, "", INNER_FILE_PATH, "inner", TOP_FILE_NAME, "top");
    final Path destination = this.directory.resolve(EXTRACTED_DIRECTORY);
    IoUtils.unzip(archive, destination);
    final Path inner = destination.resolve(INNER_FILE_PATH);
    final Path top = destination.resolve(TOP_FILE_NAME);
    final String innerContent = Files.readString(inner);
    final String topContent = Files.readString(top);
    assertEquals("inner", innerContent);
    assertEquals("top", topContent);
  }

  @Test
  void shouldRefuseADirectoryEntryThatCarriesData() throws IOException {
    final Path archive = this.writeZip(FOLDER_ENTRY, FILLER_CHARACTER.repeat(1 << 16));
    final Path destination = this.directory.resolve(EXTRACTED_DIRECTORY);
    final ZipEntryIntegrityException refused = assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));
    final String message = refused.getMessage();
    assertEquals("Zip directory entry folder/ must not carry data!", message);
  }

  @Test
  void shouldRejectEntriesThatEscapeTheDestination() throws IOException {
    final Path archive = this.writeZip(PARENT_ESCAPE_PATH, EVIL_CONTENT);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    final ZipEntryIntegrityException refused = assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));
    final String message = refused.getMessage();
    assertEquals("Zip entry ../escape.txt escapes the destination directory!", message);
  }

  @Test
  void shouldRejectEveryFormOfPathTraversal() throws IOException {
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    final String[] names = { NESTED_ESCAPE_PATH, SIBLING_ESCAPE_PATH, ABSOLUTE_ESCAPE_PATH };
    for (final String name : names) {
      final Path archive = this.writeZip(name, EVIL_CONTENT);
      assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination), name);
    }

    final Path escaped = this.directory.resolve(ESCAPE_FILE_NAME);
    final Path sibling = this.directory.resolve(SAFE_SIBLING_DIRECTORY);
    final boolean escapedExists = Files.exists(escaped);
    final boolean siblingExists = Files.exists(sibling);
    assertFalse(escapedExists);
    assertFalse(siblingExists, "a sibling whose name starts with the destination name is outside of it");
  }

  @Test
  void shouldRefuseAnEntryWhoseNameTheFileSystemCannotHold() throws IOException {
    final Path archive = this.writeZip(NUL_ESCAPE_PATH, EVIL_CONTENT);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);

    assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));
  }

  @Test
  void shouldRefuseAnEntryThatClimbsOutOfADestinationInAZipFileSystem() throws IOException {
    final Path archive = this.writeZip(BACKSLASH_ESCAPE_PATH, EVIL_CONTENT);
    final Path container = this.directory.resolve(CONTAINER_ARCHIVE_NAME);
    try (final FileSystem fileSystem = FileSystems.newFileSystem(container, CREATE_ENVIRONMENT)) {
      final Path root = fileSystem.getPath(ZIP_ROOT);
      final Path destination = root.resolve(SAFE_DIRECTORY);

      assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));

      final Path escaped = root.resolve(ESCAPE_FILE_NAME);
      final boolean escapedExists = Files.exists(escaped);
      assertFalse(escapedExists);
    }
  }

  @Test
  void shouldRefuseAnEntryWithAnEmptyNameInAZipFileSystem() throws IOException {
    final Path archive = this.writeZip(FOLDER_BACKSLASH_ESCAPE_PATH, EVIL_CONTENT);
    final Path container = this.directory.resolve(CONTAINER_ARCHIVE_NAME);
    try (final FileSystem fileSystem = FileSystems.newFileSystem(container, CREATE_ENVIRONMENT)) {
      final Path root = fileSystem.getPath(ZIP_ROOT);
      final Path destination = root.resolve(SAFE_DIRECTORY);

      final ZipEntryIntegrityException refused = assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));
      final String message = refused.getMessage();
      assertEquals("Zip entry folder/\\escape.txt has an empty name in its path!", message);
    }
  }

  private static void createSymbolicLinkOrSkip(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (final IOException | UnsupportedOperationException exception) {
      final String message = "Symbolic links cannot be created here because of \"%s\"!".formatted(exception);
      Assumptions.abort(message);
    }
  }

  @Test
  void shouldRejectZipEntriesUnderExistingDirectoryLinks() throws IOException {
    final Path outside = this.directory.resolve(OUTSIDE_DIRECTORY);
    Files.createDirectories(outside);
    final Path kept = outside.resolve(KEPT_FILE_NAME);
    Files.writeString(kept, ORIGINAL_CONTENT);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    Files.createDirectories(destination);
    final Path link = destination.resolve(LINKED_DIRECTORY);
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip(LINKED_KEPT_PATH, OVERWRITTEN_CONTENT);

    assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));

    final String content = Files.readString(kept);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertEquals(ORIGINAL_CONTENT, content);
    assertTrue(stillLinked);
  }

  @Test
  void shouldRejectDirectoryEntriesUnderExistingLinks() throws IOException {
    final Path outside = this.directory.resolve(OUTSIDE_DIRECTORY);
    Files.createDirectories(outside);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    Files.createDirectories(destination);
    final Path link = destination.resolve(LINKED_DIRECTORY);
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip(LINKED_CREATED_ENTRY, "");

    assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));

    final Path escaped = outside.resolve(CREATED_DIRECTORY);
    final boolean escapedExists = Files.exists(escaped);
    assertFalse(escapedExists);
  }

  @Test
  void shouldRejectZipEntriesThatReplaceExistingFileLinks() throws IOException {
    final Path kept = this.directory.resolve(OUTSIDE_FILE_NAME);
    Files.writeString(kept, ORIGINAL_CONTENT);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    Files.createDirectories(destination);
    final Path link = destination.resolve(ENTRY_FILE_NAME);
    createSymbolicLinkOrSkip(link, kept);
    final Path archive = this.writeZip(ENTRY_FILE_NAME, OVERWRITTEN_CONTENT);

    assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));

    final String content = Files.readString(kept);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertEquals(ORIGINAL_CONTENT, content);
    assertTrue(stillLinked);
  }

  @Test
  void shouldRejectZipEntriesThatReplaceDanglingLinks() throws IOException {
    final Path outside = this.directory.resolve(OUTSIDE_FILE_NAME);
    final Path destination = this.directory.resolve(SAFE_DIRECTORY);
    Files.createDirectories(destination);
    final Path link = destination.resolve(ENTRY_FILE_NAME);
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip(ENTRY_FILE_NAME, "escaped");

    assertThrows(ZipEntryIntegrityException.class, () -> IoUtils.unzip(archive, destination));

    final boolean outsideExists = Files.exists(outside);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertFalse(outsideExists);
    assertTrue(stillLinked);
  }

  @Test
  void shouldResolveTheDestinationItselfToItsRealDirectory() throws IOException {
    final Path realDestination = this.directory.resolve(REAL_DIRECTORY);
    Files.createDirectories(realDestination);
    final Path destination = this.directory.resolve(ALIAS_DIRECTORY);
    createSymbolicLinkOrSkip(destination, realDestination);
    final Path archive = this.writeZip(NESTED_FILE_PATH, FILE_CONTENT);

    IoUtils.unzip(archive, destination);
    final Path extracted = realDestination.resolve(NESTED_FILE_PATH);
    final String content = Files.readString(extracted);
    assertEquals(FILE_CONTENT, content);
  }

  @Test
  void shouldAllowDotDotSegmentsThatStayInside() throws IOException {
    final Path archive = this.writeZip(INSIDE_DOT_DOT_PATH, "fine");
    final Path destination = this.directory.resolve(INSIDE_DIRECTORY);
    IoUtils.unzip(archive, destination);
    final Path extracted = destination.resolve(INSIDE_FILE_NAME);
    final String content = Files.readString(extracted);
    assertEquals("fine", content);
  }

  @Test
  void shouldRejectArchivesThatAreTooLarge() throws IOException {
    final Path archive = this.writeZip(ONE_FILE_NAME, ONE_CONTENT, TWO_FILE_NAME, TWO_CONTENT);
    final Path entryDestination = this.directory.resolve(ENTRY_LIMIT_DIRECTORY);
    final Path totalDestination = this.directory.resolve(TOTAL_LIMIT_DIRECTORY);
    final ZipEntryIntegrityException entryFailure = assertThrows(ZipEntryIntegrityException.class, () ->
      IoUtils.unzip(archive, entryDestination, 3, GENEROUS_LIMIT)
    );
    final ZipEntryIntegrityException totalFailure = assertThrows(ZipEntryIntegrityException.class, () ->
      IoUtils.unzip(archive, totalDestination, GENEROUS_LIMIT, 5)
    );
    final String entryMessage = entryFailure.getMessage();
    final String totalMessage = totalFailure.getMessage();
    assertEquals("Zip entry one.txt exceeds the maximum size!", entryMessage);
    assertEquals("Total extracted size exceeds the limit!", totalMessage);
  }

  @Test
  void shouldNeverWriteMoreThanTheTotalLimitAcrossEntryChunks() throws IOException {
    final String firstContent = "a".repeat(20_000);
    final String secondContent = "b".repeat(160_000);
    final Path archive = this.writeZip(FIRST_FILE_NAME, firstContent, SECOND_FILE_NAME, secondContent);
    final Path destination = this.directory.resolve(LIMITED_DIRECTORY);
    final long totalLimit = 100_000;

    final ZipEntryIntegrityException failure = assertThrows(ZipEntryIntegrityException.class, () ->
      IoUtils.unzip(archive, destination, 200_000, totalLimit)
    );
    final Path first = destination.resolve(FIRST_FILE_NAME);
    final Path second = destination.resolve(SECOND_FILE_NAME);
    final long firstSize = Files.size(first);
    final long secondSize = Files.size(second);
    final long written = firstSize + secondSize;
    final String message = failure.getMessage();
    assertEquals(20_000, firstSize);

    final boolean withinTotal = written <= totalLimit;
    assertTrue(withinTotal, "bytes written across entries: " + written);
    assertEquals("Total extracted size exceeds the limit!", message);
  }

  @Test
  void shouldEnforceTheEntryLimitAcrossMultipleReads() throws IOException {
    final String content = FILLER_CHARACTER.repeat(200_000);
    final Path archive = this.writeZip(LARGE_FILE_NAME, content);
    final Path destination = this.directory.resolve(MULTI_READ_DIRECTORY);
    final ZipEntryIntegrityException failure = assertThrows(ZipEntryIntegrityException.class, () ->
      IoUtils.unzip(archive, destination, 100_000, 1_000_000)
    );
    final Path extracted = destination.resolve(LARGE_FILE_NAME);
    final long written = Files.size(extracted);
    final String message = failure.getMessage();
    final boolean withinEntryLimit = written <= 100_000;
    assertTrue(withinEntryLimit, "the cumulative entry limit applies before writing each chunk");
    assertEquals("Zip entry large.txt exceeds the maximum size!", message);
  }

  @Test
  void shouldAcceptEmptyFilesWithZeroSizeLimits() throws IOException {
    final Path archive = this.writeZip(EMPTY_FILE_NAME, "");
    final Path destination = this.directory.resolve(ZERO_LIMIT_DIRECTORY);
    IoUtils.unzip(archive, destination, 0, 0);
    final Path extracted = destination.resolve(EMPTY_FILE_NAME);
    final long size = Files.size(extracted);
    assertEquals(0, size);
  }

  @Test
  void shouldRejectNegativeExtractionLimits() throws IOException {
    final Path archive = this.writeZip();
    final Path destination = this.directory.resolve(INVALID_LIMIT_DIRECTORY);
    assertThrows(IllegalArgumentException.class, () -> IoUtils.unzip(archive, destination, -1, GENEROUS_LIMIT));
    assertThrows(IllegalArgumentException.class, () -> IoUtils.unzip(archive, destination, GENEROUS_LIMIT, -1));
  }

  @Test
  void shouldAcceptArchivesThatReachTheLimitsExactly() throws IOException {
    final Path archive = this.writeZip(ONE_FILE_NAME, ONE_CONTENT, TWO_FILE_NAME, TWO_CONTENT);
    final Path destination = this.directory.resolve(EXACT_DIRECTORY);
    IoUtils.unzip(archive, destination, 4, 8);
    final Path second = destination.resolve(TWO_FILE_NAME);
    final String content = Files.readString(second);
    assertEquals(TWO_CONTENT, content, "an entry of exactly 4 bytes and a total of exactly 8 bytes are allowed");
  }

  @Test
  void shouldReportArchivesThatCannotBeRead() {
    final Path missing = this.directory.resolve(MISSING_ARCHIVE_NAME);
    final Path destination = this.directory.resolve(OUTPUT_DIRECTORY);
    assertThrows(FileOperationException.class, () -> IoUtils.unzip(missing, destination));
    assertThrows(NullPointerException.class, () -> IoUtils.unzip(null, destination));
    assertThrows(NullPointerException.class, () -> IoUtils.unzip(missing, null));
  }

  @Test
  void shouldRejectFilesThatAreNotZipArchives() throws IOException {
    // ZipInputStream reads any other file as an archive without entries, which would hide a broken download
    final Path text = this.directory.resolve(NOT_A_ZIP_ARCHIVE_NAME);
    Files.writeString(text, "this is not a zip", StandardCharsets.US_ASCII);
    final Path truncated = this.directory.resolve(TRUNCATED_ARCHIVE_NAME);
    Files.write(truncated, new byte[] { 0x50, 0x4B });
    final Path destination = this.directory.resolve(OUTPUT_DIRECTORY);
    final FileOperationException textFailure = assertThrows(FileOperationException.class, () -> IoUtils.unzip(text, destination));
    final FileOperationException truncatedFailure = assertThrows(FileOperationException.class, () -> IoUtils.unzip(truncated, destination));
    final Throwable textCause = textFailure.getCause();
    final Throwable truncatedCause = truncatedFailure.getCause();
    assertInstanceOf(ZipException.class, textCause);
    assertInstanceOf(ZipException.class, truncatedCause);
  }

  @Test
  void shouldRejectArchivesTruncatedAfterTheCompleteSignature() throws IOException {
    final Path populated = this.writeZip(FILE_NAME, FILE_CONTENT);
    final byte[] populatedBytes = Files.readAllBytes(populated);
    final Path empty = this.writeZip();
    final byte[] emptyBytes = Files.readAllBytes(empty);
    for (final byte[] archiveBytes : List.of(populatedBytes, emptyBytes)) {
      final int headerSize = archiveBytes[2] == 5 ? 22 : 30;
      for (int retained = 4; retained < headerSize; retained++) {
        final Path truncated = this.directory.resolve(TRUNCATED_HEADER_PREFIX + headerSize + NAME_SEPARATOR + retained + ZIP_EXTENSION);
        Files.write(truncated, Arrays.copyOf(archiveBytes, retained));
        final Path destination = this.directory.resolve(TRUNCATED_OUTPUT_DIRECTORY);
        final FileOperationException failure = assertThrows(FileOperationException.class, () -> IoUtils.unzip(truncated, destination));
        final Throwable cause = failure.getCause();
        assertInstanceOf(ZipException.class, cause);
        final String message = cause.getMessage();
        assertEquals("Zip archive %s is truncated!".formatted(truncated), message);
      }
    }
  }

  @Test
  void shouldRequireEmptyArchiveCommentsToBeComplete() throws IOException {
    final Path archive = this.directory.resolve(COMMENTED_ARCHIVE_NAME);
    try (final ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.setComment("archive comment");
    }

    final Path destination = this.directory.resolve(COMMENTED_OUTPUT_DIRECTORY);
    IoUtils.unzip(archive, destination);
    final byte[] bytes = Files.readAllBytes(archive);
    final Path truncated = this.directory.resolve(COMMENT_TRUNCATED_ARCHIVE_NAME);
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
    final FileOperationException failure = assertThrows(FileOperationException.class, () -> IoUtils.unzip(truncated, destination));
    final Throwable cause = failure.getCause();
    assertInstanceOf(ZipException.class, cause);

    final String message = cause.getMessage();
    assertEquals("Zip archive %s is truncated!".formatted(truncated), message);
  }

  @Test
  void shouldReadBothBytesOfTheCommentLengthOfAnEmptyArchive() throws IOException {
    final Path archive = this.directory.resolve(LONG_COMMENT_ARCHIVE_NAME);
    try (final ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.setComment(FILLER_CHARACTER.repeat(300));
    }

    final byte[] bytes = Files.readAllBytes(archive);
    final Path truncated = this.directory.resolve(LONG_COMMENT_TRUNCATED_ARCHIVE_NAME);
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
    final Path destination = this.directory.resolve(LONG_COMMENT_OUTPUT_DIRECTORY);

    final FileOperationException failure = assertThrows(FileOperationException.class, () -> IoUtils.unzip(truncated, destination));
    final Throwable cause = failure.getCause();
    assertInstanceOf(ZipException.class, cause);
  }

  @Test
  void shouldExtractEmptyArchives() throws IOException {
    final Path archive = this.writeZip();
    final Path destination = this.directory.resolve(EMPTY_OUTPUT_DIRECTORY);
    IoUtils.unzip(archive, destination);
    final boolean created = Files.isDirectory(destination);
    final long entries;
    try (final Stream<Path> files = Files.list(destination)) {
      entries = files.count();
    }

    assertTrue(created);
    assertEquals(0, entries);
  }

  @Test
  void shouldMoveFilesIntoPlaceReplacingTheTarget() throws IOException {
    final Path newFile = this.directory.resolve(NEW_FILE_NAME);
    final Path source = Files.writeString(newFile, "new version");
    final Path oldFile = this.directory.resolve(REPLACED_FILE_NAME);
    final Path target = Files.writeString(oldFile, "old version");
    IoUtils.moveReplacing(source, target);
    final String content = Files.readString(target);
    final boolean sourceExists = Files.exists(source);
    assertEquals("new version", content);
    assertFalse(sourceExists);
    assertThrows(NullPointerException.class, () -> IoUtils.moveReplacing(null, target));
    assertThrows(NullPointerException.class, () -> IoUtils.moveReplacing(target, null));
  }

  @Test
  void shouldFallBackToARegularMoveWhereAtomicMovesAreNotSupported() throws IOException {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final List<List<CopyOption>> attempts = new ArrayList<>();
    IoUtils.moveReplacing(
      source,
      target,
      (from, to, options) -> this.recordMoveAndRejectAtomicMoves(from, to, options, attempts),
      SHORT_RETRY_DELAY
    );
    final List<CopyOption> atomic = List.of(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    final List<CopyOption> regular = List.of(StandardCopyOption.REPLACE_EXISTING);
    final List<List<CopyOption>> expected = List.of(atomic, regular);
    assertEquals(expected, attempts);
  }

  private void recordMoveAndRejectAtomicMoves(
    final Path from,
    final Path to,
    final CopyOption[] options,
    final List<List<CopyOption>> attempts
  ) throws AtomicMoveNotSupportedException {
    final List<CopyOption> optionList = List.of(options);
    attempts.add(optionList);

    if (optionList.contains(StandardCopyOption.ATOMIC_MOVE)) {
      final String rawFrom = from.toString();
      final String rawTo = to.toString();
      throw new AtomicMoveNotSupportedException(rawFrom, rawTo, "not supported by this file system!");
    }
  }

  @Test
  void shouldReportOtherMoveFailuresWithoutFallingBack() {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final IOException failure = new IOException("disk full!");
    final AtomicInteger attempts = new AtomicInteger();
    final IOException thrown = assertThrows(IOException.class, () ->
      IoUtils.moveReplacing(source, target, (_, _, _) -> this.countAttemptAndFail(attempts, failure), SHORT_RETRY_DELAY)
    );
    final int attemptCount = attempts.get();
    assertSame(failure, thrown);
    assertEquals(1, attemptCount, "only a denied move is tried again");
  }

  private void countAttemptAndFail(final AtomicInteger attempts, final IOException failure) throws IOException {
    attempts.incrementAndGet();

    throw failure;
  }

  @Test
  void shouldRetryAMoveThatIsDeniedForAMoment() throws IOException {
    // on Windows a virus scanner or the search indexer may hold a freshly written file for a moment
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final AtomicInteger attempts = new AtomicInteger();
    IoUtils.moveReplacing(source, target, (_, to, _) -> this.denyAllButTheLastAttempt(to, attempts), SHORT_RETRY_DELAY);
    final int attemptCount = attempts.get();
    assertEquals(MAXIMUM_MOVE_ATTEMPTS, attemptCount);
  }

  private void denyAllButTheLastAttempt(final Path to, final AtomicInteger attempts) throws AccessDeniedException {
    final int attempt = attempts.incrementAndGet();
    if (attempt < MAXIMUM_MOVE_ATTEMPTS) {
      final String rawTo = to.toString();
      throw new AccessDeniedException(rawTo);
    }
  }

  @Test
  void shouldWaitLongerBeforeEachRetryOfADeniedMove() throws IOException {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final List<Long> attemptTimes = new ArrayList<>();
    IoUtils.moveReplacing(source, target, (_, to, _) -> this.recordAndDenyAllButTheLastAttempt(to, attemptTimes), RETRY_DELAY);
    final long firstAttempt = attemptTimes.getFirst();
    final long secondAttempt = attemptTimes.get(1);
    final long lastAttempt = attemptTimes.getLast();
    final Duration firstWait = Duration.ofNanos(secondAttempt - firstAttempt);
    final Duration secondWait = Duration.ofNanos(lastAttempt - secondAttempt);
    final boolean firstWaitLongEnough = firstWait.compareTo(RETRY_DELAY) >= 0;
    final boolean secondWaitLongEnough = secondWait.compareTo(SECOND_RETRY_DELAY) >= 0;
    final int attemptCount = attemptTimes.size();
    assertEquals(MAXIMUM_MOVE_ATTEMPTS, attemptCount);
    assertTrue(firstWaitLongEnough, "the first retry waits the retry delay");
    assertTrue(secondWaitLongEnough, "the second retry waits twice the retry delay");
  }

  private void recordAndDenyAllButTheLastAttempt(final Path to, final List<Long> attemptTimes) throws AccessDeniedException {
    final long now = System.nanoTime();
    attemptTimes.add(now);
    if (attemptTimes.size() < MAXIMUM_MOVE_ATTEMPTS) {
      final String rawTo = to.toString();
      throw new AccessDeniedException(rawTo);
    }
  }

  @Test
  void shouldGiveUpOnAMoveThatStaysDenied() {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final AtomicReference<AccessDeniedException> lastDenial = new AtomicReference<>();
    final AtomicInteger attempts = new AtomicInteger();
    final AccessDeniedException thrown = assertThrows(AccessDeniedException.class, () ->
      IoUtils.moveReplacing(source, target, (_, _, _) -> this.countAttemptAndDenyAgain(attempts, lastDenial), SHORT_RETRY_DELAY)
    );
    final AccessDeniedException last = lastDenial.get();
    final int attemptCount = attempts.get();
    assertSame(last, thrown, "the last denial is thrown");
    assertEquals(MAXIMUM_MOVE_ATTEMPTS, attemptCount, "a move is tried three times in total");
  }

  private void countAttemptAndDenyAgain(final AtomicInteger attempts, final AtomicReference<AccessDeniedException> lastDenial)
    throws AccessDeniedException {
    attempts.incrementAndGet();

    final AccessDeniedException denied = new AccessDeniedException(TARGET_NAME);
    lastDenial.set(denied);
    throw denied;
  }

  @Test
  void shouldReportAMoveThatStaysDeniedWithTheDenialAsItsCause() throws IOException {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path targetDirectory = Files.createDirectories(this.directory.resolve(TARGET_NAME));
    final Path target = targetDirectory.resolve(FILE_NAME);
    Files.writeString(source, FILE_CONTENT);
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    Files.setPosixFilePermissions(targetDirectory, READ_ONLY_PERMISSIONS);
    try {
      final boolean writable = Files.isWritable(targetDirectory);
      Assumptions.assumeFalse(writable, "The user can write every directory!");

      final long start = System.nanoTime();
      final FileOperationException thrown = assertThrows(FileOperationException.class, () -> IoUtils.moveReplacing(source, target));
      final long end = System.nanoTime();
      final Duration waited = Duration.ofNanos(end - start);
      final Throwable cause = thrown.getCause();
      final boolean waitedForEveryRetry = waited.compareTo(TOTAL_RETRY_DELAY) >= 0;
      assertInstanceOf(AccessDeniedException.class, cause);
      assertTrue(waitedForEveryRetry, "a denied move is retried 100 and 200 milliseconds apart");
    } finally {
      Files.setPosixFilePermissions(targetDirectory, OWNER_DIRECTORY_PERMISSIONS);
    }
  }

  @Test
  void shouldStopRetryingADeniedMoveWhenInterrupted() {
    final Path source = this.directory.resolve(SOURCE_NAME);
    final Path target = this.directory.resolve(TARGET_NAME);
    final AccessDeniedException denied = new AccessDeniedException(TARGET_NAME);
    final AtomicInteger attempts = new AtomicInteger();
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    final InterruptedIOException thrown = assertThrows(InterruptedIOException.class, () ->
      IoUtils.moveReplacing(source, target, (_, _, _) -> this.countAttemptAndFail(attempts, denied), SHORT_RETRY_DELAY)
    );
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = thrown.getCause();
    final int attemptCount = attempts.get();
    assertTrue(interrupted, "the interrupt must be restored");
    assertSame(denied, cause);
    assertEquals(1, attemptCount);
  }

  @Test
  void shouldDeleteADirectoryTree() throws IOException {
    final Path tree = this.directory.resolve(TREE_DIRECTORY);
    final Path nested = tree.resolve(NESTED_DIRECTORY);
    final Path file = nested.resolve(FILE_NAME);
    Files.createDirectories(nested);
    Files.writeString(file, FILE_CONTENT);
    IoUtils.deleteRecursively(tree);
    final boolean treeExists = Files.exists(tree);
    assertFalse(treeExists);
  }

  @Test
  void shouldDeleteASingleFile() throws IOException {
    final Path file = this.directory.resolve(FILE_NAME);
    Files.writeString(file, FILE_CONTENT);
    IoUtils.deleteRecursively(file);
    final boolean fileExists = Files.exists(file);
    assertFalse(fileExists);
  }

  @Test
  void shouldDeleteLinksInATreeWithoutFollowingThem() throws IOException {
    final Path outside = this.directory.resolve(OUTSIDE_DIRECTORY);
    final Path kept = outside.resolve(FILE_NAME);
    Files.createDirectories(outside);
    Files.writeString(kept, FILE_CONTENT);
    final Path tree = this.directory.resolve(TREE_DIRECTORY);
    final Path link = tree.resolve(LINKED_DIRECTORY);
    Files.createDirectories(tree);
    createSymbolicLinkOrSkip(link, outside);
    IoUtils.deleteRecursively(tree);
    final boolean treeExists = Files.exists(tree, LinkOption.NOFOLLOW_LINKS);
    final String content = Files.readString(kept);
    assertFalse(treeExists);
    assertEquals(FILE_CONTENT, content, "the target of a link is kept");
  }

  @Test
  void shouldDeleteALinkWhoseTargetIsGone() {
    final Path missing = this.directory.resolve(MISSING_NAME);
    final Path link = this.directory.resolve(LINKED_DIRECTORY);
    createSymbolicLinkOrSkip(link, missing);
    IoUtils.deleteRecursively(link);
    final boolean linkExists = Files.exists(link, LinkOption.NOFOLLOW_LINKS);
    assertFalse(linkExists);
  }

  @Test
  void shouldIgnoreAMissingPathWhenDeleting() throws IOException {
    final Path missing = this.directory.resolve(MISSING_NAME);
    IoUtils.deleteRecursively(missing);
    final boolean missingExists = Files.exists(missing);
    final boolean directoryExists = Files.isDirectory(this.directory);
    assertFalse(missingExists);
    assertTrue(directoryExists, "only the specified path is deleted");
  }

  @Test
  void shouldRejectANullPathWhenDeleting() {
    assertThrows(NullPointerException.class, () -> IoUtils.deleteRecursively(null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(IoUtils.class);
  }
}

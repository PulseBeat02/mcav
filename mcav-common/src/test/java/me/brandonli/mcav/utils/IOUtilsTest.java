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
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link IOUtils}.
 */
final class IOUtilsTest {

  private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);
  private static final Duration SHORT_RETRY_DELAY = Duration.ofMillis(1);

  @TempDir
  private Path directory;

  private Path writeZip(final String... namesAndContents) throws IOException {
    final Path archive = this.directory.resolve("archive.zip");
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
  void extractsTheFileNameOfUrls() {
    final String file = IOUtils.getFileNameFromUrl("https://example.com/videos/clip.mp4?quality=hd");
    final String directoryUrl = IOUtils.getFileNameFromUrl("https://example.com/videos/");
    final String noPath = IOUtils.getFileNameFromUrl("https://example.com");
    final String opaque = IOUtils.getFileNameFromUrl("mailto:someone@example.com");
    assertEquals("clip.mp4", file);
    assertEquals("", directoryUrl);
    assertEquals("", noPath);
    assertEquals("", opaque);
    assertThrows(NullPointerException.class, () -> IOUtils.getFileNameFromUrl(null));
  }

  @Test
  void findsAFreePortForVnc() throws IOException {
    final int port = IOUtils.getNextFreeVNCPort();
    final boolean inRange = port >= 5900 && port <= 65535;
    assertTrue(inRange);
    try (final ServerSocket occupied = new ServerSocket(0)) {
      final int occupiedPort = occupied.getLocalPort();
      assertThrows(UncheckedIOException.class, () -> IOUtils.findFreePort(occupiedPort, occupiedPort));
    }
  }

  @Test
  void skipsOccupiedPortsAndTriesTheLastPortOfTheRange() throws IOException {
    final int firstFree = IOUtils.findFreePort(40_000, 40_999);
    final int found;
    try (final ServerSocket occupied = new ServerSocket(firstFree)) {
      final int occupiedPort = occupied.getLocalPort();
      found = IOUtils.findFreePort(occupiedPort, occupiedPort + 100);
    }
    final int probePort;
    try (final ServerSocket probe = new ServerSocket(0)) {
      probePort = probe.getLocalPort();
    }
    final int single = IOUtils.findFreePort(probePort, probePort);
    assertEquals(probePort, single, "a range of one port includes that port");
    assertTrue(found > firstFree && found <= firstFree + 100, "the occupied port is skipped, got " + found);
  }

  @Test
  void createsMissingDirectoriesOnly() throws IOException {
    final Path nested = this.directory.resolve("a/b/c");
    final boolean created = IOUtils.createDirectoryIfNotExists(nested);
    final boolean createdAgain = IOUtils.createDirectoryIfNotExists(nested);
    final boolean isDirectory = Files.isDirectory(nested);
    final Path file = this.directory.resolve("file.txt");
    Files.createFile(file);
    final Path belowFile = file.resolve("child");
    assertTrue(created);
    assertFalse(createdAgain);
    assertTrue(isDirectory);
    assertThrows(UncheckedIOException.class, () -> IOUtils.createDirectoryIfNotExists(belowFile));
    assertThrows(NullPointerException.class, () -> IOUtils.createDirectoryIfNotExists(null));
  }

  @Test
  void createsMissingFilesAndTheirParents() {
    final Path nested = this.directory.resolve("x/y/file.txt");
    final boolean created = IOUtils.createFileIfNotExists(nested);
    final boolean createdAgain = IOUtils.createFileIfNotExists(nested);
    final boolean isFile = Files.isRegularFile(nested);
    final Path belowFile = nested.resolve("child.txt");
    assertTrue(created);
    assertFalse(createdAgain);
    assertTrue(isFile);
    assertThrows(UncheckedIOException.class, () -> IOUtils.createFileIfNotExists(belowFile));
    assertThrows(NullPointerException.class, () -> IOUtils.createFileIfNotExists(null));
  }

  @Test
  void createsFilesWithoutAParentInTheWorkingDirectory() throws IOException {
    final Configuration configuration = Configuration.unix();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path relative = fileSystem.getPath("relative.txt");
      final boolean created = IOUtils.createFileIfNotExists(relative);
      final boolean exists = Files.exists(relative);
      assertTrue(created);
      assertTrue(exists);
    }
  }

  @Test
  void marksFilesExecutableOnPosixFileSystems() throws IOException {
    final Configuration unix = Configuration.unix();
    final Configuration.Builder builder = unix.toBuilder();
    builder.setAttributeViews("basic", "owner", "posix", "unix");
    final Configuration configuration = builder.build();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path tool = fileSystem.getPath("/tool");
      Files.createFile(tool);
      IOUtils.markExecutable(tool);
      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tool);
      final Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxr-xr-x");
      final Path missing = fileSystem.getPath("/missing");
      assertEquals(expected, permissions);
      assertThrows(UncheckedIOException.class, () -> IOUtils.markExecutable(missing));
    }
  }

  @Test
  void leavesFilesAloneOnOtherFileSystems() throws IOException {
    final Configuration configuration = Configuration.windows();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
      final Path tool = fileSystem.getPath("C:\\tool.exe");
      IOUtils.markExecutable(tool);
      final boolean exists = Files.exists(tool);
      assertFalse(exists, "nothing is touched without POSIX permissions");
    }
    assertThrows(NullPointerException.class, () -> IOUtils.markExecutable(null));
  }

  @Test
  void hashesFiles() throws IOException {
    final Path file = this.directory.resolve("abc.txt");
    Files.write(file, ABC);
    final String sha256 = IOUtils.getSHA256Hash(file);
    final String sha1 = IOUtils.getSHA1Hash(file);
    final Path missing = this.directory.resolve("missing.txt");
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256);
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1);
    assertThrows(UncheckedIOException.class, () -> IOUtils.getSHA256Hash(missing));
    assertThrows(IllegalStateException.class, () -> IOUtils.createDigest("NO-SUCH-ALGORITHM"));
    assertThrows(NullPointerException.class, () -> IOUtils.getSHA256Hash(null));
    assertThrows(NullPointerException.class, () -> IOUtils.getSHA1Hash(null));
  }

  @Test
  void formatsBytesAsZeroPaddedHex() {
    final String hex = IOUtils.bytesToHex(new byte[] { 0x0F, (byte) 0xAB, 0x00 });
    assertEquals("0fab00", hex);
    assertThrows(NullPointerException.class, () -> IOUtils.bytesToHex(null));
  }

  @Test
  void getsTheNameOfPaths() {
    final Path file = this.directory.resolve("name.txt");
    final Path root = this.directory.getRoot();
    final String name = IOUtils.getName(file);
    assertEquals("name.txt", name);
    assertThrows(IllegalArgumentException.class, () -> IOUtils.getName(root));
    assertThrows(NullPointerException.class, () -> IOUtils.getName(null));
  }

  @Test
  void downloadsImagesIntoTheCache() throws IOException {
    try (
      final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(this.directory);
      final LocalHttpServer server = LocalHttpServer.start()
    ) {
      server.respond("/image.png", 200, ABC);
      final URI imageUri = server.uri("/image.png");
      final URI missingUri = server.uri("/missing.png");
      final UriSource image = UriSource.uri(imageUri);
      final UriSource missing = UriSource.uri(missingUri);

      final Path downloaded = IOUtils.downloadImage(image);
      final byte[] content = Files.readAllBytes(downloaded);
      final Path cache = IOUtils.getCachedFolder();
      final Path home = redirected.getHome();
      final boolean inCache = downloaded.startsWith(cache);
      final boolean cacheInHome = cache.startsWith(home);

      assertArrayEquals(ABC, content);
      assertTrue(inCache);
      assertTrue(cacheInHome);
      assertThrows(UncheckedIOException.class, () -> IOUtils.downloadImage(missing));
      assertThrows(NullPointerException.class, () -> IOUtils.downloadImage(null));
    }
  }

  @Test
  void readsDownloadListsFromResources() {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("sample.json");
    final Download[] none = IOUtils.readDownloadsFromJsonResource("empty.json");
    final Download linux = downloads[0];
    final Platform platform = linux.getPlatform();
    final Platform expectedPlatform = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
    final String url = linux.getUrl();
    assertEquals(2, downloads.length);
    assertEquals(0, none.length);
    assertEquals(expectedPlatform, platform);
    assertEquals("https://example.com/tool-linux", url);
    assertThrows(UncheckedIOException.class, () -> IOUtils.readDownloadsFromJsonResource("invalid.json"));
    assertThrows(UncheckedIOException.class, () -> IOUtils.readDownloadsFromJsonResource("missing.json"));
    assertThrows(NullPointerException.class, () -> IOUtils.readDownloadsFromJsonResource(null));
  }

  @Test
  void opensResources() throws IOException {
    final String content;
    try (final Reader reader = IOUtils.getResourceAsStreamReader("installers/empty.json")) {
      final char[] buffer = new char[16];
      final int read = reader.read(buffer);
      content = new String(buffer, 0, read);
    }
    try (final InputStream stream = IOUtils.getResourceAsInputStream("installers/empty.json")) {
      final byte[] bytes = stream.readAllBytes();
      final String text = new String(bytes, StandardCharsets.UTF_8);
      assertEquals(content, text);
    }
    assertEquals("null\n", content);
  }

  @Test
  void reportsResourcesThatCannotBeFound() {
    // String is loaded by the bootstrap class loader, which the resource lookup cannot use
    assertThrows(UncheckedIOException.class, () -> IOUtils.getResourceAsInputStream("installers/empty.json", String.class));
    assertThrows(UncheckedIOException.class, () -> IOUtils.getResourceAsInputStream("installers/missing.json"));
    assertThrows(UncheckedIOException.class, () -> IOUtils.getResourceAsStreamReader("installers/missing.json"));
  }

  @Test
  void rejectsMissingResourceArguments() {
    assertThrows(NullPointerException.class, () -> IOUtils.getResourceAsStreamReader(null));
    assertThrows(NullPointerException.class, () -> IOUtils.getResourceAsInputStream(null));
    assertThrows(NullPointerException.class, () -> IOUtils.getResourceAsInputStream(null, IOUtils.class));
    assertThrows(NullPointerException.class, () -> IOUtils.getResourceAsInputStream("x", null));
  }

  @Test
  void extractsZipArchives() throws IOException {
    final Path archive = this.writeZip("folder/", "", "folder/inner.txt", "inner", "top.txt", "top");
    final Path destination = this.directory.resolve("extracted");
    IOUtils.unzip(archive, destination);
    final Path inner = destination.resolve("folder/inner.txt");
    final Path top = destination.resolve("top.txt");
    final String innerContent = Files.readString(inner);
    final String topContent = Files.readString(top);
    assertEquals("inner", innerContent);
    assertEquals("top", topContent);
  }

  @Test
  void rejectsEntriesThatEscapeTheDestination() throws IOException {
    final Path archive = this.writeZip("../escape.txt", "evil");
    final Path destination = this.directory.resolve("safe");
    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
  }

  @Test
  void rejectsEveryFormOfPathTraversal() throws IOException {
    final Path destination = this.directory.resolve("safe");
    final String[] names = { "nested/../../escape.txt", "../safe-sibling/escape.txt", "/escape.txt" };
    for (final String name : names) {
      final Path archive = this.writeZip(name, "evil");
      assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination), name);
    }
    final Path escaped = this.directory.resolve("escape.txt");
    final Path sibling = this.directory.resolve("safe-sibling");
    final boolean escapedExists = Files.exists(escaped);
    final boolean siblingExists = Files.exists(sibling);
    assertFalse(escapedExists);
    assertFalse(siblingExists, "a sibling whose name starts with the destination name is outside of it");
  }

  /**
   * Found by {@code UnzipFuzzTest}: an entry named with a character the file system cannot hold, such as the NUL of the
   * fuzzer's archive, made {@link java.nio.file.Path#resolve(String)} throw an {@link java.nio.file.InvalidPathException}
   * out of {@link IOUtils#unzip(Path, Path)}, which documents only its own exceptions for a bad archive.
   */
  @Test
  void refusesAnEntryWhoseNameTheFileSystemCannotHold() throws IOException {
    final Path archive = this.writeZip("../\u0000escape.txt", "evil");
    final Path destination = this.directory.resolve("safe");

    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
  }

  private static void createSymbolicLinkOrSkip(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (final IOException | UnsupportedOperationException exception) {
      Assumptions.abort("Symbolic links cannot be created here: " + exception);
    }
  }

  @Test
  void rejectsZipEntriesUnderExistingDirectoryLinks() throws IOException {
    final Path outside = this.directory.resolve("outside");
    Files.createDirectories(outside);
    final Path kept = outside.resolve("kept.txt");
    Files.writeString(kept, "original");
    final Path destination = this.directory.resolve("safe");
    Files.createDirectories(destination);
    final Path link = destination.resolve("linked");
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip("linked/kept.txt", "overwritten");

    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
    final String content = Files.readString(kept);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertEquals("original", content);
    assertTrue(stillLinked);
  }

  @Test
  void rejectsDirectoryEntriesUnderExistingLinks() throws IOException {
    final Path outside = this.directory.resolve("outside");
    Files.createDirectories(outside);
    final Path destination = this.directory.resolve("safe");
    Files.createDirectories(destination);
    final Path link = destination.resolve("linked");
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip("linked/created/", "");

    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
    final Path escaped = outside.resolve("created");
    final boolean escapedExists = Files.exists(escaped);
    assertFalse(escapedExists);
  }

  @Test
  void rejectsZipEntriesThatReplaceExistingFileLinks() throws IOException {
    final Path kept = this.directory.resolve("outside.txt");
    Files.writeString(kept, "original");
    final Path destination = this.directory.resolve("safe");
    Files.createDirectories(destination);
    final Path link = destination.resolve("entry.txt");
    createSymbolicLinkOrSkip(link, kept);
    final Path archive = this.writeZip("entry.txt", "overwritten");

    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
    final String content = Files.readString(kept);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertEquals("original", content);
    assertTrue(stillLinked);
  }

  @Test
  void rejectsZipEntriesThatReplaceDanglingLinks() throws IOException {
    final Path outside = this.directory.resolve("outside.txt");
    final Path destination = this.directory.resolve("safe");
    Files.createDirectories(destination);
    final Path link = destination.resolve("entry.txt");
    createSymbolicLinkOrSkip(link, outside);
    final Path archive = this.writeZip("entry.txt", "escaped");

    assertThrows(ZipEntryIntegrityException.class, () -> IOUtils.unzip(archive, destination));
    final boolean outsideExists = Files.exists(outside);
    final boolean stillLinked = Files.isSymbolicLink(link);
    assertFalse(outsideExists);
    assertTrue(stillLinked);
  }

  @Test
  void resolvesTheDestinationItselfToItsRealDirectory() throws IOException {
    final Path realDestination = this.directory.resolve("real");
    Files.createDirectories(realDestination);
    final Path destination = this.directory.resolve("alias");
    createSymbolicLinkOrSkip(destination, realDestination);
    final Path archive = this.writeZip("nested/file.txt", "content");

    IOUtils.unzip(archive, destination);
    final Path extracted = realDestination.resolve("nested/file.txt");
    final String content = Files.readString(extracted);
    assertEquals("content", content);
  }

  @Test
  void allowsDotDotSegmentsThatStayInside() throws IOException {
    final Path archive = this.writeZip("folder/../inside.txt", "fine");
    final Path destination = this.directory.resolve("inside");
    IOUtils.unzip(archive, destination);
    final Path extracted = destination.resolve("inside.txt");
    final String content = Files.readString(extracted);
    assertEquals("fine", content);
  }

  @Test
  void rejectsArchivesThatAreTooLarge() throws IOException {
    final Path archive = this.writeZip("one.txt", "1234", "two.txt", "5678");
    final Path entryDestination = this.directory.resolve("entry-limit");
    final Path totalDestination = this.directory.resolve("total-limit");
    final ZipEntryIntegrityException entryFailure = assertThrows(ZipEntryIntegrityException.class, () ->
      IOUtils.unzip(archive, entryDestination, 3, 100)
    );
    final ZipEntryIntegrityException totalFailure = assertThrows(ZipEntryIntegrityException.class, () ->
      IOUtils.unzip(archive, totalDestination, 100, 5)
    );
    final String entryMessage = entryFailure.getMessage();
    final String totalMessage = totalFailure.getMessage();
    assertEquals("Zip entry exceeds the maximum size: one.txt", entryMessage);
    assertEquals("Total extracted size exceeds the limit", totalMessage);
  }

  @Test
  void neverWritesMoreThanTheTotalLimitAcrossEntryChunks() throws IOException {
    final String firstContent = "a".repeat(20_000);
    final String secondContent = "b".repeat(160_000);
    final Path archive = this.writeZip("first.txt", firstContent, "second.txt", secondContent);
    final Path destination = this.directory.resolve("limited");
    final long totalLimit = 100_000;

    final ZipEntryIntegrityException failure = assertThrows(ZipEntryIntegrityException.class, () ->
      IOUtils.unzip(archive, destination, 200_000, totalLimit)
    );
    final Path first = destination.resolve("first.txt");
    final Path second = destination.resolve("second.txt");
    final long firstSize = Files.size(first);
    final long secondSize = Files.size(second);
    final long written = firstSize + secondSize;
    final String message = failure.getMessage();
    assertEquals(20_000, firstSize);
    assertTrue(written <= totalLimit, "bytes written across entries: " + written);
    assertEquals("Total extracted size exceeds the limit", message);
  }

  @Test
  void enforcesTheEntryLimitAcrossMultipleReads() throws IOException {
    final String content = "x".repeat(200_000);
    final Path archive = this.writeZip("large.txt", content);
    final Path destination = this.directory.resolve("multi-read-entry");
    final ZipEntryIntegrityException failure = assertThrows(ZipEntryIntegrityException.class, () ->
      IOUtils.unzip(archive, destination, 100_000, 1_000_000)
    );
    final Path extracted = destination.resolve("large.txt");
    final long written = Files.size(extracted);
    final String message = failure.getMessage();
    assertTrue(written <= 100_000, "the cumulative entry limit applies before writing each chunk");
    assertEquals("Zip entry exceeds the maximum size: large.txt", message);
  }

  @Test
  void acceptsEmptyFilesWithZeroSizeLimits() throws IOException {
    final Path archive = this.writeZip("empty.txt", "");
    final Path destination = this.directory.resolve("zero-limit");
    IOUtils.unzip(archive, destination, 0, 0);
    final Path extracted = destination.resolve("empty.txt");
    final long size = Files.size(extracted);
    assertEquals(0, size);
  }

  @Test
  void rejectsNegativeExtractionLimits() throws IOException {
    final Path archive = this.writeZip();
    final Path destination = this.directory.resolve("invalid-limit");
    assertThrows(IllegalArgumentException.class, () -> IOUtils.unzip(archive, destination, -1, 100));
    assertThrows(IllegalArgumentException.class, () -> IOUtils.unzip(archive, destination, 100, -1));
  }

  @Test
  void acceptsArchivesThatReachTheLimitsExactly() throws IOException {
    final Path archive = this.writeZip("one.txt", "1234", "two.txt", "5678");
    final Path destination = this.directory.resolve("exact");
    IOUtils.unzip(archive, destination, 4, 8);
    final Path second = destination.resolve("two.txt");
    final String content = Files.readString(second);
    assertEquals("5678", content, "an entry of exactly 4 bytes and a total of exactly 8 bytes are allowed");
  }

  @Test
  void reportsArchivesThatCannotBeRead() {
    final Path missing = this.directory.resolve("missing.zip");
    final Path destination = this.directory.resolve("out");
    assertThrows(UncheckedIOException.class, () -> IOUtils.unzip(missing, destination));
    assertThrows(NullPointerException.class, () -> IOUtils.unzip(null, destination));
    assertThrows(NullPointerException.class, () -> IOUtils.unzip(missing, null));
  }

  @Test
  void rejectsFilesThatAreNotZipArchives() throws IOException {
    // ZipInputStream reads any other file as an archive without entries, which would hide a broken download
    final Path text = this.directory.resolve("not-a-zip.zip");
    Files.writeString(text, "this is not a zip", StandardCharsets.US_ASCII);
    final Path truncated = this.directory.resolve("truncated.zip");
    Files.write(truncated, new byte[] { 0x50, 0x4B });
    final Path destination = this.directory.resolve("out");
    final UncheckedIOException textFailure = assertThrows(UncheckedIOException.class, () -> IOUtils.unzip(text, destination));
    final UncheckedIOException truncatedFailure = assertThrows(UncheckedIOException.class, () -> IOUtils.unzip(truncated, destination));
    final Throwable textCause = textFailure.getCause();
    final Throwable truncatedCause = truncatedFailure.getCause();
    assertInstanceOf(ZipException.class, textCause);
    assertInstanceOf(ZipException.class, truncatedCause);
  }

  @Test
  void extractsEmptyArchives() throws IOException {
    final Path archive = this.writeZip();
    final Path destination = this.directory.resolve("empty-out");
    IOUtils.unzip(archive, destination);
    final boolean created = Files.isDirectory(destination);
    final long entries;
    try (final Stream<Path> files = Files.list(destination)) {
      entries = files.count();
    }
    assertTrue(created);
    assertEquals(0, entries);
  }

  @Test
  void movesFilesIntoPlaceReplacingTheTarget() throws IOException {
    final Path newFile = this.directory.resolve("new.txt");
    final Path source = Files.writeString(newFile, "new version");
    final Path oldFile = this.directory.resolve("target.txt");
    final Path target = Files.writeString(oldFile, "old version");
    IOUtils.moveReplacing(source, target);
    final String content = Files.readString(target);
    final boolean sourceExists = Files.exists(source);
    assertEquals("new version", content);
    assertFalse(sourceExists);
    assertThrows(NullPointerException.class, () -> IOUtils.moveReplacing(null, target));
    assertThrows(NullPointerException.class, () -> IOUtils.moveReplacing(target, null));
  }

  @Test
  void fallsBackToARegularMoveWhereAtomicMovesAreNotSupported() throws IOException {
    final Path source = this.directory.resolve("source");
    final Path target = this.directory.resolve("target");
    final List<List<CopyOption>> attempts = new ArrayList<>();
    IOUtils.moveReplacing(
      source,
      target,
      (from, to, options) -> {
        final List<CopyOption> optionList = List.of(options);
        attempts.add(optionList);
        if (optionList.contains(StandardCopyOption.ATOMIC_MOVE)) {
          final String rawFrom = from.toString();
          final String rawTo = to.toString();
          throw new AtomicMoveNotSupportedException(rawFrom, rawTo, "not supported by this file system");
        }
      },
      SHORT_RETRY_DELAY
    );
    final List<CopyOption> atomic = List.of(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    final List<CopyOption> regular = List.of(StandardCopyOption.REPLACE_EXISTING);
    final List<List<CopyOption>> expected = List.of(atomic, regular);
    assertEquals(expected, attempts);
  }

  @Test
  void reportsOtherMoveFailuresWithoutFallingBack() {
    final Path source = this.directory.resolve("source");
    final Path target = this.directory.resolve("target");
    final IOException failure = new IOException("disk full");
    final AtomicInteger attempts = new AtomicInteger();
    final IOException thrown = assertThrows(IOException.class, () ->
      IOUtils.moveReplacing(
        source,
        target,
        (_, _, _) -> {
          attempts.incrementAndGet();
          throw failure;
        },
        SHORT_RETRY_DELAY
      )
    );
    final int attemptCount = attempts.get();
    assertSame(failure, thrown);
    assertEquals(1, attemptCount, "only a denied move is tried again");
  }

  @Test
  void retriesAMoveThatIsDeniedForAMoment() throws IOException {
    // on Windows a virus scanner or the search indexer may hold a freshly written file for a moment
    final Path source = this.directory.resolve("source");
    final Path target = this.directory.resolve("target");
    final AtomicInteger attempts = new AtomicInteger();
    IOUtils.moveReplacing(
      source,
      target,
      (_, to, _) -> {
        final int attempt = attempts.incrementAndGet();
        if (attempt < 3) {
          final String rawTo = to.toString();
          throw new AccessDeniedException(rawTo);
        }
      },
      SHORT_RETRY_DELAY
    );
    final int attemptCount = attempts.get();
    assertEquals(3, attemptCount);
  }

  @Test
  void givesUpOnAMoveThatStaysDenied() {
    final Path source = this.directory.resolve("source");
    final Path target = this.directory.resolve("target");
    final AccessDeniedException denied = new AccessDeniedException("target");
    final AtomicInteger attempts = new AtomicInteger();
    final AccessDeniedException thrown = assertThrows(AccessDeniedException.class, () ->
      IOUtils.moveReplacing(
        source,
        target,
        (_, _, _) -> {
          attempts.incrementAndGet();
          throw denied;
        },
        SHORT_RETRY_DELAY
      )
    );
    final int attemptCount = attempts.get();
    assertSame(denied, thrown);
    assertEquals(3, attemptCount, "a move is tried three times in total");
  }

  @Test
  void stopsRetryingADeniedMoveWhenInterrupted() {
    final Path source = this.directory.resolve("source");
    final Path target = this.directory.resolve("target");
    final AccessDeniedException denied = new AccessDeniedException("target");
    final AtomicInteger attempts = new AtomicInteger();
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    final InterruptedIOException thrown = assertThrows(InterruptedIOException.class, () ->
      IOUtils.moveReplacing(
        source,
        target,
        (_, _, _) -> {
          attempts.incrementAndGet();
          throw denied;
        },
        SHORT_RETRY_DELAY
      )
    );
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = thrown.getCause();
    final int attemptCount = attempts.get();
    assertTrue(interrupted, "the interrupt must be restored");
    assertSame(denied, cause);
    assertEquals(1, attemptCount);
  }

  @Test
  void deletesADirectoryTree() throws IOException {
    final Path tree = this.directory.resolve("tree");
    final Path nested = tree.resolve("nested");
    final Path file = nested.resolve("file.txt");
    Files.createDirectories(nested);
    Files.writeString(file, "content");
    IOUtils.deleteRecursively(tree);
    final boolean treeExists = Files.exists(tree);
    assertFalse(treeExists);
  }

  @Test
  void deletesASingleFile() throws IOException {
    final Path file = this.directory.resolve("file.txt");
    Files.writeString(file, "content");
    IOUtils.deleteRecursively(file);
    final boolean fileExists = Files.exists(file);
    assertFalse(fileExists);
  }

  @Test
  void ignoresAMissingPathWhenDeleting() throws IOException {
    final Path missing = this.directory.resolve("missing");
    IOUtils.deleteRecursively(missing);
    final boolean missingExists = Files.exists(missing);
    final boolean directoryExists = Files.isDirectory(this.directory);
    assertFalse(missingExists);
    assertTrue(directoryExists, "only the specified path is deleted");
  }

  @Test
  void rejectsANullPathWhenDeleting() {
    assertThrows(NullPointerException.class, () -> IOUtils.deleteRecursively(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(IOUtils.class);
  }
}

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Os;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class LinuxLibrariesTest {

  private static final String LIBRARY = "usr/lib/x86_64-linux-gnu/libmcavtest.so.1.2.3";
  private static final String POOL = "pool/main/m/mcavtest/libmcavtest1_1.2.3-1_amd64.deb";

  private static final byte[] XZ_MAGIC = { (byte) 0xFD, '7', 'z', 'X', 'Z', 0 };
  // LZMA2 stores the dictionary size in one byte: (2 | byte & 1) << (byte / 2 + 11)
  private static final int DICTIONARY_64_MEBIBYTES = 28;
  private static final int DICTIONARY_192_MEBIBYTES = 31;

  private static final String PLATFORM = "linux-amd64";
  private static final String ARM_PLATFORM = "linux-arm64";
  private static final String LIBRARY_NAME = "libmcavtest.so.1";

  private static final String ROOT = "/";
  private static final String LOADER_CONFIGURATION = "etc/ld.so.conf";
  private static final String LOADER_INCLUDES = "etc/ld.so.conf.d";

  private static final int ELF_32_BIT = 1;
  private static final int ELF_64_BIT = 2;
  private static final int LITTLE_ENDIAN = 1;

  private static final int I386_MACHINE = 3;
  private static final int AMD64_MACHINE = 62;
  private static final int ARM64_MACHINE = 183;

  private static final long START_TIMEOUT_MILLISECONDS = 1_000L;

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{12}");

  @TempDir
  private Path folder;

  private final List<URI> downloads = new CopyOnWriteArrayList<>();

  private byte[] debianPackage(final Map<String, byte[]> files, final Map<String, String> links, final String dataName) throws IOException {
    return this.debianPackage(files, links, dataName, "./", List.of());
  }

  private byte[] debianPackage(
    final Map<String, byte[]> files,
    final Map<String, String> links,
    final String dataName,
    final String prefix,
    final List<String> folders
  ) throws IOException {
    final ByteArrayOutputStream data = new ByteArrayOutputStream();
    try (final TarArchiveOutputStream tar = new TarArchiveOutputStream(new XZCompressorOutputStream(data))) {
      for (final String folder : folders) {
        tar.putArchiveEntry(new TarArchiveEntry(prefix + folder, TarArchiveEntry.LF_DIR));
        tar.closeArchiveEntry();
      }
      for (final Map.Entry<String, byte[]> file : files.entrySet()) {
        final TarArchiveEntry entry = new TarArchiveEntry(prefix + file.getKey());
        entry.setSize(file.getValue().length);
        tar.putArchiveEntry(entry);
        tar.write(file.getValue());
        tar.closeArchiveEntry();
      }
      for (final Map.Entry<String, String> link : links.entrySet()) {
        final boolean hard = link.getValue().startsWith("=");
        final TarArchiveEntry entry = new TarArchiveEntry(
          prefix + link.getKey(),
          hard ? TarArchiveEntry.LF_LINK : TarArchiveEntry.LF_SYMLINK
        );
        entry.setLinkName(hard ? link.getValue().substring(1) : link.getValue());
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
      }
    }

    final ByteArrayOutputStream debianPackage = new ByteArrayOutputStream();
    try (final ArArchiveOutputStream archive = new ArArchiveOutputStream(debianPackage)) {
      final byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
      archive.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
      archive.write(version);
      archive.closeArchiveEntry();
      final byte[] tarData = data.toByteArray();
      archive.putArchiveEntry(new ArArchiveEntry(dataName, tarData.length));
      archive.write(tarData);
      archive.closeArchiveEntry();
    }

    return debianPackage.toByteArray();
  }

  private byte[] testPackage() throws IOException {
    return this.debianPackage(
      Map.of(LIBRARY, "the library".getBytes(StandardCharsets.US_ASCII), "usr/share/doc/libmcavtest1/copyright", new byte[3]),
      Map.of("usr/lib/x86_64-linux-gnu/libmcavtest.so.1", "libmcavtest.so.1.2.3"),
      "data.tar.xz"
    );
  }

  // class 1 is 32-bit, 2 is 64-bit; order 1 is little-endian, 2 big-endian; machine 62 is x86-64, 183 AArch64, 3 i386
  private byte[] elf(final int elfClass, final int order, final int machine) {
    final byte[] header = new byte[64];
    header[0] = 0x7F;
    header[1] = 'E';
    header[2] = 'L';
    header[3] = 'F';
    header[4] = (byte) elfClass;
    header[5] = (byte) order;
    header[18] = (byte) machine;
    header[19] = (byte) (machine >> 8);

    return header;
  }

  // rewrites the dictionary size in the header of the first xz block, and the CRC32 of that header
  private byte[] withDictionary(final byte[] debianPackage, final int dictionary) {
    final byte[] patched = debianPackage.clone();
    int stream = 0;
    while (!Arrays.equals(patched, stream, stream + XZ_MAGIC.length, XZ_MAGIC, 0, XZ_MAGIC.length)) {
      stream++;
    }

    final int block = stream + 12;
    final int headerBytes = ((patched[block] & 0xFF) + 1) * 4;
    final byte filterId = patched[block + 2];
    assertEquals(0x21, filterId, "the block holds LZMA2");

    final byte propertiesSize = patched[block + 3];
    assertEquals(1, propertiesSize, "LZMA2 has one byte of properties");

    patched[block + 4] = (byte) dictionary;
    final CRC32 crc = new CRC32();
    crc.update(patched, block, headerBytes - Integer.BYTES);
    final long checksum = crc.getValue();
    for (int index = 0; index < Integer.BYTES; index++) {
      patched[block + headerBytes - Integer.BYTES + index] = (byte) (checksum >>> (Byte.SIZE * index));
    }

    return patched;
  }

  private String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String pin(final byte[] debianPackage) {
    return (
      "linux-amd64 libmcavtest1 1.2.3-1 " +
      this.sha256(debianPackage) +
      " " +
      debianPackage.length +
      " " +
      POOL +
      " libmcavtest.so.1=" +
      LIBRARY
    );
  }

  // downloads come from memory; a mirror named "broken" fails
  private LinuxLibraries installer(final byte[] debianPackage, final List<String> mirrors, final List<String> pins) {
    return new LinuxLibraries(
      this.folder.resolve("cache"),
      (uri, destination, sha256, size) -> this.downloadPackage(debianPackage, uri, destination, sha256, size),
      mirrors,
      pins
    );
  }

  private void downloadPackage(final byte[] debianPackage, final URI uri, final Path destination, final String sha256, final long size)
    throws IOException {
    this.downloads.add(uri);
    assertEquals(debianPackage.length, size, "the pinned size bounds the download");

    if (uri.getHost().equals("broken.test")) {
      throw new IOException("mirror down");
    }

    if (!sha256.equals(this.sha256(debianPackage))) {
      throw new IOException("hash mismatch");
    }

    Files.write(destination, debianPackage);
  }

  @Test
  void shouldPinBothLinuxPlatformsFromTheArchiveOfDebian11InTheResource() {
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256, size) -> {},
      LinuxLibraries.MIRRORS,
      LinuxLibraries.readResource(LinuxLibraries.class.getClassLoader())
    );
    for (final String platform : List.of(PLATFORM, ARM_PLATFORM)) {
      final List<LinuxLibraries.Pin> pins = libraries.getPins(platform);
      final int pinCount = pins.size();
      assertEquals(52, pinCount, platform);

      // Chromium's X11 layer loads it by name and CHECKs that it did, which no NEEDED entry of libcef shows
      final boolean pinsLibX11Xcb = pins.stream().anyMatch(pin -> pin.sonames().contains("libX11-xcb.so.1"));
      assertTrue(pinsLibX11Xcb, platform);

      for (final LinuxLibraries.Pin pin : pins) {
        final boolean fromTheMainPool = pin.pool().startsWith("pool/main/") || pin.pool().startsWith("pool/updates/main/");
        assertTrue(fromTheMainPool, pin.pool());

        final boolean noFiles = pin.files().isEmpty();
        assertFalse(noFiles);
      }
      final LinuxLibraries.Pin nss = pins
        .stream()
        .filter(pin -> pin.name().equals("libnss3"))
        .findFirst()
        .orElseThrow();
      final String mainLibrary = nss.sonames().getFirst();
      assertEquals("libnss3.so", mainLibrary, "NSS is linked as a whole, decided by its main library");

      final boolean holdsTheSoftwareToken = nss.sonames().contains("libsoftokn3.so");
      assertTrue(holdsTheSoftwareToken);

      final boolean twelveHexadecimalDigits = FINGERPRINT.matcher(LinuxLibraries.fingerprint(pins)).matches();
      assertTrue(twelveHexadecimalDigits);
    }

    assertEquals(List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/"), LinuxLibraries.MIRRORS);
  }

  @Test
  void shouldTreatAResourceThatIsMissingOrUnreadableAsABrokenBuild() {
    final ClassLoader empty = new ClassLoader(null) {};

    final IllegalStateException missing = assertThrows(IllegalStateException.class, () -> LinuxLibraries.readResource(empty));
    final String message = missing.getMessage();
    assertEquals("The resource mcav/browser/linux-libraries.txt is missing!", message);

    final ClassLoader broken = new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(final String name) {
        return new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("disk gone");
          }

          @Override
          public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            throw new IOException("disk gone");
          }
        };
      }
    };
    final IllegalStateException unreadable = assertThrows(IllegalStateException.class, () -> LinuxLibraries.readResource(broken));
    final Throwable cause = unreadable.getCause();
    assertInstanceOf(UncheckedIOException.class, cause);
  }

  @Test
  void shouldRefuseInvalidPins() {
    final String hash = "0".repeat(64);
    for (final String line : List.of(
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb",
      "Linux_amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash.substring(1) + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 ../x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb x=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb libx.so.1=etc/passwd",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " many pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "host",
      "host linux-amd64 libc"
    )) {
      assertThrows(
        IllegalArgumentException.class,
        () -> new LinuxLibraries(this.folder, (uri, destination, sha256, size) -> {}, List.of(), List.of(line)),
        line
      );
    }
  }

  @Test
  void shouldNameTheWholeEntryOfALibraryWhoseSonameIsEmpty() {
    final String line = "linux-amd64 libx 1 " + "0".repeat(64) + " 1 pool/main/x/x/x.deb =usr/lib/x/libx.so.1";
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      new LinuxLibraries(this.folder, (uri, destination, sha256, size) -> {}, List.of(), List.of(line))
    );

    final String message = refused.getMessage();
    final boolean namesTheEntry = message.endsWith("invalid value =usr/lib/x/libx.so.1!");
    assertTrue(namesTheEntry, message);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogTheSizeOfTheDownloadInMegabytes() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String pin = this.pin(debianPackage).replace(" " + debianPackage.length + " ", " 3000000 ");
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve("cache"),
      (uri, destination, sha256, size) -> Files.write(destination, debianPackage),
      List.of("https://mirror.test/debian/"),
      List.of(pin)
    );
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      libraries.install(PLATFORM);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean sizeLogged = log.contains("(3 MB, once)");
    assertTrue(sizeLogged, log);
  }

  @Test
  void shouldDownloadVerifyAndKeepTheLibrariesAsFilesNamedBySoname() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of("# a comment", "", this.pin(debianPackage))
    );
    final Path installation = libraries.install(PLATFORM);
    assertEquals(List.of(URI.create("https://mirror.test/debian/" + POOL)), this.downloads);
    try (final Stream<Path> files = Files.list(installation)) {
      final List<String> fileNames = files
        .map(file -> file.getFileName().toString())
        .sorted()
        .toList();
      assertEquals(List.of("install.lock", LIBRARY_NAME), fileNames, "neither the link nor the documentation of the package");
    }

    final Path library = installation.resolve(LIBRARY_NAME);

    final byte[] libraryBytes = Files.readAllBytes(library);
    assertArrayEquals("the library".getBytes(StandardCharsets.US_ASCII), libraryBytes);

    if (library.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), libraryPermissions);

      final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), installationPermissions);

      final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(this.folder.resolve("cache"));
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), cachePermissions);
    }

    final Path secondInstallation = libraries.install(PLATFORM);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.size();
    assertEquals(1, downloadCount);

    try (final Stream<Path> left = Files.list(this.folder.resolve("cache"))) {
      final long cacheEntryCount = left.count();
      assertEquals(2, cacheEntryCount, "the installation and its lock file, nothing staged");
    }
  }

  @Test
  void shouldDeleteEachPackageBeforeTheNextOneDownloads() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String sha256 = this.sha256(debianPackage);
    final String otherPin = "linux-amd64 libmcavother1 1.2.3-1 %s %s %s libmcavother.so.1=%s".formatted(
      sha256,
      debianPackage.length,
      POOL,
      LIBRARY
    );
    final List<Boolean> leftOver = new ArrayList<>();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve("cache"),
      (_, destination, _, _) -> this.downloadAndRecordLeftOver(debianPackage, destination, leftOver),
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage), otherPin)
    );
    final Path installation = libraries.install(PLATFORM);
    final boolean otherLibraryInstalled = Files.isRegularFile(installation.resolve("libmcavother.so.1"));
    assertTrue(otherLibraryInstalled);

    assertEquals(List.of(false, false), leftOver, "each package downloads into a free spot");
  }

  private void downloadAndRecordLeftOver(final byte[] debianPackage, final Path destination, final List<Boolean> leftOver)
    throws IOException {
    final boolean exists = Files.exists(destination);
    leftOver.add(exists);
    Files.write(destination, debianPackage);
  }

  @Test
  void shouldTakeTheGroupWritePermissionFromLibrariesAnEarlierVersionInstalled() throws IOException {
    assumeTrue(this.folder.getFileSystem().supportedFileAttributeViews().contains("posix"), "This file system has no POSIX permissions!");
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage))
    );
    final Path installation = libraries.install(PLATFORM);
    final Path cache = this.folder.resolve("cache");
    final Path library = installation.resolve(LIBRARY_NAME);
    Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rwxrwxrwx"));
    final Path secondInstallation = libraries.install(PLATFORM);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.size();
    assertEquals(1, downloadCount, "nothing is installed again");

    final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(cache);
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), cachePermissions);

    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), installationPermissions);

    final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), libraryPermissions);
  }

  @Test
  void shouldTryTheNextMirrorWhenTheDownloadOfTheFirstCannotBeMovedIntoPlace() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve("cache"),
      (uri, destination, _, _) -> this.downloadFailingToMoveFromTheFirstMirror(debianPackage, uri, destination),
      List.of("https://first.test/debian/", "https://second.test/debian/"),
      List.of(this.pin(debianPackage))
    );

    final Path installation = libraries.install(PLATFORM);
    final List<URI> expected = List.of(URI.create("https://first.test/debian/" + POOL), URI.create("https://second.test/debian/" + POOL));
    assertEquals(expected, this.downloads);

    final boolean installed = Files.isRegularFile(installation.resolve(LIBRARY_NAME));
    assertTrue(installed);
  }

  private void downloadFailingToMoveFromTheFirstMirror(final byte[] debianPackage, final URI uri, final Path destination)
    throws IOException {
    this.downloads.add(uri);

    if (uri.getHost().equals("first.test")) {
      throw new FileOperationException("The part file could not be moved into place!");
    }

    Files.write(destination, debianPackage);
  }

  @Test
  void shouldFollowAFailingMirrorWithTheNextAndReportWhenAllFail() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries fallback = this.installer(
      debianPackage,
      List.of("https://broken.test/debian/", "https://mirror.test/debian/"),
      List.of(this.pin(debianPackage))
    );
    fallback.install(PLATFORM);
    final int downloadCount = this.downloads.size();
    assertEquals(2, downloadCount);

    this.downloads.clear();
    final LinuxLibraries none = new LinuxLibraries(
      this.folder.resolve("other-cache"),
      (uri, destination, _, _) -> this.writeAndFailDownload(uri, destination),
      List.of("https://first.test/debian/", "https://second.test/debian/"),
      List.of(this.pin(debianPackage))
    );
    final IOException failure = assertThrows(IOException.class, () -> none.install(PLATFORM));
    final String failureMessage = failure.getMessage();
    assertEquals("The package libmcavtest1 could not be downloaded from any mirror!", failureMessage);

    final String firstMirrorMessage = failure.getCause().getMessage();
    assertEquals("down first.test", firstMirrorMessage);

    final String secondMirrorMessage = failure.getCause().getSuppressed()[0].getMessage();
    assertEquals("down second.test", secondMirrorMessage);

    try (final Stream<Path> left = Files.list(this.folder.resolve("other-cache"))) {
      final List<String> leftFileNames = left.map(file -> file.getFileName().toString()).toList();
      assertEquals(List.of("debian-11-linux-amd64-" + LinuxLibraries.fingerprint(none.getPins(PLATFORM)) + ".lock"), leftFileNames);
    }
  }

  private void writeAndFailDownload(final URI uri, final Path destination) throws IOException {
    this.downloads.add(uri);
    Files.write(destination, new byte[1]);
    throw new IOException("down " + uri.getHost());
  }

  @Test
  void shouldRefuseAPlatformWithoutLibraries() {
    final LinuxLibraries libraries = new LinuxLibraries(this.folder, (uri, destination, sha256, size) -> {}, List.of(), List.of());
    final IOException failure = assertThrows(IOException.class, () -> libraries.install("linux-riscv64"));
    final String message = failure.getMessage();
    assertEquals("Platform linux-riscv64 has no libraries in mcav!", message);
  }

  @Test
  void shouldRequireAPackageToHoldItsDataAndEveryPinnedLibraryAsARegularFile() throws IOException {
    final Map<String, String> wanted = Map.of(LIBRARY, LIBRARY_NAME);
    final Path noData = Files.write(
      this.folder.resolve("no-data.deb"),
      this.debianPackage(Map.of(LIBRARY, new byte[1]), Map.of(), "data.tar.zst")
    );
    final IOException member = assertThrows(IOException.class, () -> this.extractIntoNewFolder(noData, wanted, "a"));
    final Throwable noDataCause = member.getCause();
    assertInstanceOf(InstallationException.class, noDataCause);

    final Path linkOnly = Files.write(
      this.folder.resolve("link.deb"),
      this.debianPackage(Map.of(), Map.of(LIBRARY, "elsewhere.so"), "data.tar.xz")
    );
    final IOException link = assertThrows(IOException.class, () -> this.extractIntoNewFolder(linkOnly, wanted, "b"));
    final String linkMessage = link.getMessage();
    assertEquals("The package link.deb holds none of [%s]!".formatted(LIBRARY), linkMessage);

    final boolean unpackedKept = Files.exists(this.folder.resolve("link.deb.unpacked"));
    assertFalse(unpackedKept, "the unpacked package is removed");

    final Path hardLink = Files.write(
      this.folder.resolve("hard.deb"),
      this.debianPackage(Map.of(), Map.of(LIBRARY, "=elsewhere.so"), "data.tar.xz")
    );
    final IOException hard = assertThrows(IOException.class, () -> this.extractIntoNewFolder(hardLink, wanted, "hard"));
    final Throwable hardLinkCause = hard.getCause();
    assertInstanceOf(InstallationException.class, hardLinkCause);
  }

  private void extractIntoNewFolder(final Path debianPackage, final Map<String, String> wanted, final String folderName)
    throws IOException {
    LinuxLibraries.extract(debianPackage, wanted, Files.createDirectory(this.folder.resolve(folderName)));
  }

  @Test
  void shouldNotLetAPackageAskForMoreMemoryThanTheStrongestCompressionOfDpkg() throws IOException {
    final Map<String, String> wanted = Map.of(LIBRARY, LIBRARY_NAME);
    final byte[] greedy = this.withDictionary(this.testPackage(), DICTIONARY_192_MEBIBYTES);
    final Path debianPackage = Files.write(this.folder.resolve("greedy.deb"), greedy);
    final Path target = Files.createDirectory(this.folder.resolve("greedy"));

    assertThrows(IOException.class, () -> LinuxLibraries.extract(debianPackage, wanted, target));

    try (final Stream<Path> written = Files.list(target)) {
      final List<Path> writtenFiles = written.toList();
      assertEquals(List.of(), writtenFiles, "nothing of a refused package is written");
    }
  }

  @Test
  void shouldReadAPackageCompressedWithTheStrongestCompressionOfDpkg() throws IOException {
    final byte[] strongest = this.withDictionary(this.testPackage(), DICTIONARY_64_MEBIBYTES);
    final Path debianPackage = Files.write(this.folder.resolve("strongest.deb"), strongest);
    final Path target = Files.createDirectory(this.folder.resolve("strongest"));

    LinuxLibraries.extract(debianPackage, Map.of(LIBRARY, LIBRARY_NAME), target);

    final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
    assertArrayEquals("the library".getBytes(StandardCharsets.US_ASCII), libraryBytes);
  }

  @Test
  void shouldWriteLibrariesOnFileSystemsWithoutPosixPermissions() throws IOException {
    final Path debianPackage = Files.write(this.folder.resolve("package.deb"), this.testPackage());
    try (final FileSystem zip = FileSystems.newFileSystem(this.folder.resolve("plain.zip"), Map.of("create", "true"))) {
      final Path target = zip.getPath(ROOT);
      LinuxLibraries.extract(debianPackage, Map.of(LIBRARY, LIBRARY_NAME), target);
      final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
      assertArrayEquals("the library".getBytes(StandardCharsets.US_ASCII), libraryBytes);
    }
  }

  @Test
  void shouldOnlyLinkThePackagesTheServerLacksIntoTheSession() throws IOException {
    final Path host = Files.createDirectories(this.folder.resolve("host"));
    Files.write(host.resolve("libpresent.so.1"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    Files.write(host.resolve("libz.so.1"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final String hash = "0".repeat(64);
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256, size) -> {},
      List.of(),
      List.of(
        "host linux-amd64 libz.so.1",
        "linux-amd64 libpresent1 1 " + hash + " 1 pool/main/p/p/p.deb libpresent.so.1=usr/lib/x86_64-linux-gnu/libpresent.so.1",
        "linux-amd64 libmissing1 1 " +
          hash +
          " 1 pool/main/m/m/m.deb libmissing.so.1=usr/lib/x86_64-linux-gnu/libmissing.so.1 " +
          "libmissingmodule.so=usr/lib/x86_64-linux-gnu/nss/libmissingmodule.so"
      )
    );
    final Path installation = this.folder.resolve("installation");
    final Path session = Files.createDirectory(this.folder.resolve("session"));
    final Path linked = LinuxLibraries.link(installation, session, libraries.findMissing(PLATFORM, List.of(host)));
    assertEquals(session.resolve(LinuxLibraries.SESSION_FOLDER), linked);

    try (final Stream<Path> links = Files.list(linked)) {
      final List<String> linkNames = links
        .map(link -> link.getFileName().toString())
        .sorted()
        .toList();
      assertEquals(List.of("libmissing.so.1", "libmissingmodule.so"), linkNames);
    }

    final Path linkTarget = Files.readSymbolicLink(linked.resolve("libmissing.so.1"));
    assertEquals(installation.resolve("libmissing.so.1"), linkTarget);

    final Path bare = Files.createDirectory(this.folder.resolve("bare-host"));
    final BrowserException lacking = assertThrows(BrowserUnavailableException.class, () -> libraries.findMissing(PLATFORM, List.of(bare)));
    final String message = lacking.getMessage();
    assertEquals("The browser needs libz.so.1, which this server lacks and mcav does not bring!", message);
  }

  @Test
  void shouldTakeTheFoldersOfTheLoaderFromItsConfigurationAndTheDefaults() throws IOException {
    final Path root = this.folder.resolve("root");
    final Path configuration = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(
      root.resolve(LOADER_CONFIGURATION),
      "# the loader\ninclude /etc/ld.so.conf.d/*.conf\n/opt/game/lib # after\nhwcap 0 nosegneg\n\ninclude /missing/*.conf\n"
    );
    Files.writeString(configuration.resolve("a.conf"), "/usr/local/lib\n");
    Files.writeString(configuration.resolve("b.conf"), "include /etc/ld.so.conf.d/b.conf\n/opt/deep\n");
    Files.writeString(configuration.resolve("c.txt"), "/not/included\n");
    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final List<Path> expected = new ArrayList<>();
    for (final String path : List.of(
      "usr/local/lib",
      "opt/deep",
      "opt/game/lib",
      "lib/x86_64-linux-gnu",
      "usr/lib/x86_64-linux-gnu",
      "lib64",
      "usr/lib64",
      "lib",
      "usr/lib"
    )) {
      expected.add(root.resolve(path));
    }

    assertEquals(expected, folders);

    final List<Path> arm = LinuxLibraries.hostFolders(this.folder.resolve("empty-root"), ARM_PLATFORM);
    final Path firstFolder = arm.getFirst();
    assertEquals(this.folder.resolve("empty-root/lib/aarch64-linux-gnu"), firstFolder);

    final int folderCount = arm.size();
    assertEquals(6, folderCount, "a system without a configuration has the default folders only");

    final List<Path> rootMatches = LinuxLibraries.glob(Path.of(ROOT), ROOT);
    assertEquals(List.of(), rootMatches, "the root names no files");

    final boolean present = LinuxLibraries.isPresent("libmcav-nothing.so", List.of(), PLATFORM);
    assertFalse(present);
  }

  @Test
  void shouldFollowThePinnedHashesInTheFingerprint() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of(), List.of(this.pin(debianPackage)));
    final String fingerprint = LinuxLibraries.fingerprint(libraries.getPins(PLATFORM));
    assertEquals(this.sha256(this.sha256(debianPackage).getBytes(StandardCharsets.US_ASCII)).substring(0, 12), fingerprint);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldLinkTheLibrariesTheServerLacksIntoTheDefaultSessionsOfLinux() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final HelperLauncher withLibraries = CefBrowserPlayer.DefaultSessionFactory.withLibraries(linux, libraries);
    final Path session = Files.createDirectory(this.folder.resolve("session"));
    final Path linked = withLibraries.linkLibraries(session);
    final boolean libraryLinked = Files.isSymbolicLink(linked.resolve(LIBRARY_NAME));
    assertTrue(libraryLinked, "this machine lacks the test library");

    final HelperLauncher windows = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.WINDOWS);
    final HelperLauncher launcherWithLibraries = CefBrowserPlayer.DefaultSessionFactory.withLibraries(windows, libraries);
    assertSame(windows, launcherWithLibraries);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldDownloadNothingWhenTheServerHasEveryLibrary() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    final Path root = Files.createDirectories(this.folder.resolve("provisioned"));
    final Path loader = Files.createDirectories(LinuxLibraries.hostFolders(root, platform).getFirst());
    Files.write(
      loader.resolve(LIBRARY_NAME),
      this.elf(ELF_64_BIT, LITTLE_ENDIAN, platform.endsWith("arm64") ? ARM64_MACHINE : AMD64_MACHINE)
    );
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final HelperLauncher launcherWithLibraries = CefBrowserPlayer.DefaultSessionFactory.withLibraries(linux, libraries, root);
    assertSame(linux, launcherWithLibraries);
    assertEquals(List.of(), this.downloads, "a server behind a firewall that has every library needs no mirror");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRefuseAHeadlessJavaRuntimeWithWhatToInstall() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    // as Ubuntu's openjdk-25-jre-headless: no libjawt.so, which libjcef.so links against
    final Path runtime = Files.createDirectories(this.folder.resolve("java-25-openjdk"));
    final Path library = Files.createDirectories(runtime.resolve("lib"));
    Files.write(library.resolve("libawt_headless.so"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final HelperLauncher headless = new HelperLauncher(
      runtime.resolve("bin").resolve("java"),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      Os.LINUX,
      Map.of(),
      START_TIMEOUT_MILLISECONDS
    );
    final Path server = Files.createDirectories(this.folder.resolve("server"));
    final Executable linking = () -> CefBrowserPlayer.DefaultSessionFactory.withLibraries(headless, libraries, server);
    final BrowserException refused = assertThrows(BrowserUnavailableException.class, linking);
    final String message = refused.getMessage();
    assertEquals(
      "The browser needs the Java runtime's libjawt.so, which " +
        runtime +
        " lacks: the headless Java packages of Debian and Ubuntu (openjdk-*-jre-headless) leave it out; install the full" +
        " package of the same version (openjdk-*-jre), or run the server on a full Java runtime!",
      message
    );
    assertEquals(List.of(), this.downloads, "no library is downloaded for a runtime the browser cannot use");

    Files.write(library.resolve("libjawt.so"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final Path loader = Files.createDirectories(LinuxLibraries.hostFolders(server, platform).getFirst());
    Files.write(
      loader.resolve(LIBRARY_NAME),
      this.elf(ELF_64_BIT, LITTLE_ENDIAN, platform.endsWith("arm64") ? ARM64_MACHINE : AMD64_MACHINE)
    );
    final HelperLauncher launcherWithLibraries = CefBrowserPlayer.DefaultSessionFactory.withLibraries(headless, libraries, server);
    assertSame(headless, launcherWithLibraries, "a full runtime is used");
  }

  @Test
  void shouldReadAPackageWhoseNamesHaveNoPrefixAndThatHoldsFolders() throws IOException {
    final Path plain = Files.write(
      this.folder.resolve("plain.deb"),
      this.debianPackage(Map.of(LIBRARY, new byte[] { 7 }), Map.of(), "data.tar.xz", "", List.of("usr/"))
    );
    final Path target = Files.createDirectories(this.folder.resolve("plain"));
    LinuxLibraries.extract(plain, Map.of(LIBRARY, LIBRARY_NAME), target);
    final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
    assertArrayEquals(new byte[] { 7 }, libraryBytes);
  }

  @Test
  void shouldNotInstallAgainLibrariesAnotherProcessInstalledWhileThisOneWaited() throws Exception {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of("https://deb.test/debian/"), List.of(this.pin(debianPackage)));
    final Path installation = this.folder
      .resolve("cache")
      .resolve("debian-11-linux-amd64-" + LinuxLibraries.fingerprint(libraries.getPins(PLATFORM)));
    final AtomicReference<Thread> installer = new AtomicReference<>();
    final CompletableFuture<Path> installed;
    synchronized (LinuxLibraries.INSTALL_LOCK) {
      installed = CompletableFuture.supplyAsync(() -> this.installRecordingTheCaller(installer, libraries));
      Await.until("the installer waits for the lock", () -> installer.get() != null && installer.get().getState() == Thread.State.BLOCKED);
      Files.createDirectories(installation);
      Files.writeString(installation.resolve(LinuxLibraries.INSTALL_MARKER), "done");
    }

    final Path waitedInstallation = installed.get(30, TimeUnit.SECONDS);
    assertEquals(installation, waitedInstallation);
    assertEquals(List.of(), this.downloads, "nothing was downloaded again");
  }

  private Path installRecordingTheCaller(final AtomicReference<Thread> installer, final LinuxLibraries libraries) {
    installer.set(Thread.currentThread());
    try {
      return libraries.install(PLATFORM);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void shouldReadAPatternWithoutARootBelowTheRoot() throws IOException {
    final Path configurationDirectory = Files.createDirectories(this.folder.resolve(LOADER_INCLUDES));
    Files.writeString(configurationDirectory.resolve("a.conf"), "/opt/a");
    Files.writeString(configurationDirectory.resolve("b.txt"), "/opt/b");
    final List<Path> configurationMatches = LinuxLibraries.glob(this.folder, "etc/ld.so.conf.d/*.conf");
    assertEquals(List.of(configurationDirectory.resolve("a.conf")), configurationMatches);

    Files.writeString(this.folder.resolve("top.conf"), "/opt/top");
    final List<Path> topMatches = LinuxLibraries.glob(this.folder, "*.conf");
    assertEquals(List.of(this.folder.resolve("top.conf")), topMatches);

    final List<Path> rootMatches = LinuxLibraries.glob(this.folder, ROOT);
    assertEquals(List.of(), rootMatches);

    final List<Path> trailingSlashMatches = LinuxLibraries.glob(this.folder, "etc/");
    assertEquals(List.of(), trailingSlashMatches);
  }

  @Test
  void shouldCountALibraryOfTheServerOnlyWhenItIsBuiltForThePlatform() throws IOException {
    final Path folder = Files.createDirectories(this.folder.resolve("multiarch"));
    final Path amd64 = Files.write(folder.resolve("amd64.so"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final Path arm64 = Files.write(folder.resolve("arm64.so"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, ARM64_MACHINE));
    final boolean amd64LibraryFits = LinuxLibraries.isBuiltFor(amd64, PLATFORM);
    assertTrue(amd64LibraryFits);

    final boolean amd64LibraryFitsArm = LinuxLibraries.isBuiltFor(amd64, ARM_PLATFORM);
    assertFalse(amd64LibraryFitsArm);

    final boolean arm64LibraryFits = LinuxLibraries.isBuiltFor(arm64, ARM_PLATFORM);
    assertTrue(arm64LibraryFits);

    final boolean thirtyTwoBitLibraryFits = LinuxLibraries.isBuiltFor(
      Files.write(folder.resolve("i386.so"), this.elf(ELF_32_BIT, LITTLE_ENDIAN, I386_MACHINE)),
      PLATFORM
    );
    assertFalse(thirtyTwoBitLibraryFits, "32 bits");

    final boolean bigEndianLibraryFits = LinuxLibraries.isBuiltFor(Files.write(folder.resolve("big.so"), this.elf(2, 2, 62)), PLATFORM);
    assertFalse(bigEndianLibraryFits, "big-endian");

    final boolean otherMachineFits = LinuxLibraries.isBuiltFor(
      Files.write(folder.resolve("other.so"), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE + 0x100)),
      PLATFORM
    );
    assertFalse(otherMachineFits, "a machine that matches x86-64 only in its low byte");

    final byte[] notElf = this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE);
    notElf[1] = 'X';
    final boolean textFileFits = LinuxLibraries.isBuiltFor(Files.write(folder.resolve("text.so"), notElf), PLATFORM);
    assertFalse(textFileFits, "no ELF file");

    final boolean shortFileFits = LinuxLibraries.isBuiltFor(Files.write(folder.resolve("short.so"), new byte[19]), PLATFORM);
    assertFalse(shortFileFits, "too short");

    final boolean missingFileFits = LinuxLibraries.isBuiltFor(folder.resolve("missing.so"), PLATFORM);
    assertFalse(missingFileFits, "no file");

    final Path i386 = Files.createDirectories(this.folder.resolve("i386"));
    Files.write(i386.resolve("libX11.so.6"), this.elf(ELF_32_BIT, LITTLE_ENDIAN, I386_MACHINE));
    final boolean present = LinuxLibraries.isPresent("libX11.so.6", List.of(i386), PLATFORM);
    assertFalse(present);
  }

  @Test
  void shouldLetAPlatformExpectNoLibraryOfTheServerAndKeepTheLibrariesInTheCacheOfMcav() {
    final LinuxLibraries nothingExpected = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256, size) -> {},
      List.of(),
      List.of("host linux-amd64")
    );
    final List<LinuxLibraries.Pin> pins = nothingExpected.getPins(PLATFORM);
    assertEquals(List.of(), pins);

    final Path librariesFolder = new LinuxLibraries().getFolder();
    assertEquals(IoUtils.getCachedFolder().resolve("jcef-libraries"), librariesFolder);
  }

  @Test
  void shouldReplaceAnInstallationThatWasNeverFinished() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of("https://mirror.test/debian/"),
      List.of(this.pin(debianPackage))
    );
    final String name = "debian-11-linux-amd64-" + LinuxLibraries.fingerprint(libraries.getPins(PLATFORM));
    final Path unfinished = Files.createDirectories(this.folder.resolve("cache").resolve(name));
    Files.writeString(unfinished.resolve("left-over"), "partial");
    final Path installation = libraries.install(PLATFORM);
    assertEquals(unfinished, installation);

    final boolean libraryInstalled = Files.isRegularFile(installation.resolve(LIBRARY_NAME));
    assertTrue(libraryInstalled);

    final boolean leftOverKept = Files.exists(installation.resolve("left-over"));
    assertFalse(leftOverKept, "nothing of the unfinished installation is kept");
  }

  @Test
  void shouldFollowIncludesEightDeep() throws IOException {
    final Path root = this.folder.resolve("deep-root");
    final Path etc = Files.createDirectories(root.resolve("etc"));
    Files.writeString(etc.resolve("ld.so.conf"), "include /etc/1.conf\n");
    for (int depth = 1; depth <= 9; depth++) {
      Files.writeString(etc.resolve(depth + ".conf"), "/opt/depth" + depth + "\ninclude /etc/" + (depth + 1) + ".conf\n");
    }

    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final boolean followsEightDeep = folders.contains(root.resolve("opt/depth8"));
    assertTrue(followsEightDeep, folders::toString);

    final boolean followsNineDeep = folders.contains(root.resolve("opt/depth9"));
    assertFalse(followsNineDeep, "an include nine deep is beyond the limit");
  }

  @Test
  void shouldReadIncludedFilesInTheOrderOfTheirNames() throws IOException {
    final Path root = this.folder.resolve("sorted-root");
    final Path configuration = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(root.resolve(LOADER_CONFIGURATION), "include /etc/ld.so.conf.d/*.conf\n");
    final List<String> names = List.of("zeta", "alpha", "mike", "delta", "kilo", "bravo", "yankee", "echo");
    for (final String name : names) {
      Files.writeString(configuration.resolve(name + ".conf"), "/opt/" + name + "\n");
    }

    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final List<Path> expected = names
      .stream()
      .sorted()
      .map(name -> root.resolve("opt/" + name))
      .toList();
    final List<Path> includedFolders = folders.subList(0, names.size());
    assertEquals(expected, includedFolders);
  }

  @Test
  void shouldResolveARelativeIncludeOfTheLoaderBesideItsFile() throws IOException {
    final Path root = this.folder.resolve("relative-root");
    final Path etc = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(root.resolve(LOADER_CONFIGURATION), "include ld.so.conf.d/*.conf\n");
    Files.writeString(etc.resolve("game.conf"), "/opt/relative\n");
    final Path firstFolder = LinuxLibraries.hostFolders(root, PLATFORM).getFirst();
    assertEquals(root.resolve("opt/relative"), firstFolder);
  }

  @Test
  void shouldFetchAPackageOfTheSecurityUpdatesFromTheirMirrors() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final List<URI> asked = new ArrayList<>();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve("security-cache"),
      (uri, destination, _, _) -> this.recordAndWritePackage(asked, debianPackage, uri, destination),
      List.of("https://main.test/debian/"),
      List.of("https://security.test/debian-security/"),
      List.of(this.pin(debianPackage).replace(POOL, "pool/updates/main/m/mcavtest/libmcavtest1_1.2.3-1+deb11u1_amd64.deb"))
    );
    libraries.install(PLATFORM);
    assertEquals(
      List.of(URI.create("https://security.test/debian-security/pool/updates/main/m/mcavtest/libmcavtest1_1.2.3-1+deb11u1_amd64.deb")),
      asked
    );

    final String secondSecurityMirror = LinuxLibraries.SECURITY_MIRRORS.get(1);
    assertEquals("https://snapshot.debian.org/archive/debian-security/20260901T000000Z/", secondSecurityMirror);
  }

  private void recordAndWritePackage(final List<URI> asked, final byte[] debianPackage, final URI uri, final Path destination)
    throws IOException {
    asked.add(uri);

    Files.write(destination, debianPackage);
  }
}

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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

  private static final String HOST_DIRECTIVE = "host";
  private static final String HASH_FILLER = "0";
  private static final String LIBRARY_TEXT = "the library";

  private static final String OTHER_LIBRARY_FILE = "libmcavother.so.1";
  private static final String OTHER_CACHE_FOLDER = "other-cache";
  private static final String UNPACKED_LINK_PACKAGE_FILE = "link.deb.unpacked";
  private static final String PRESENT_LIBRARY_FILE = "libpresent.so.1";
  private static final String COMPRESSION_LIBRARY_FILE = "libz.so.1";
  private static final String MISSING_SONAME_FILE = "libmissing.so.1";
  private static final String BARE_HOST_FOLDER = "bare-host";
  private static final String EMPTY_ROOT_FOLDER = "empty-root";
  private static final String JAVA_RUNTIME_FOLDER = "java-25-openjdk";
  private static final String LIBRARY_CACHE_FOLDER = "jcef-libraries";
  private static final String LEFTOVER_FILE = "left-over";
  private static final String DEEP_ROOT_FOLDER = "deep-root";
  private static final String LOADER_CONFIGURATION_FILE = "ld.so.conf";
  private static final String DEPTH_INCLUDE_PREFIX = "\ninclude /etc/";
  private static final String SORTED_ROOT_FOLDER = "sorted-root";
  private static final String RELATIVE_ROOT_FOLDER = "relative-root";
  private static final String SECURITY_CACHE_FOLDER = "security-cache";
  private static final String COPYRIGHT_FILE = "usr/share/doc/libmcavtest1/copyright";
  private static final String LIBRARY_LINK_FILE = "usr/lib/x86_64-linux-gnu/libmcavtest.so.1";
  private static final String CACHE_FOLDER = "cache";
  private static final String MAIN_POOL_PREFIX = "pool/main/";
  private static final String UPDATES_POOL_PREFIX = "pool/updates/main/";
  private static final String NSS_LIBRARY_FILE = "libnss3.so";
  private static final String SOFTWARE_TOKEN_LIBRARY_FILE = "libsoftokn3.so";
  private static final String INSTALLATION_LOCK_FILE = "install.lock";
  private static final String LOCK_FILE_SUFFIX = ".lock";
  private static final String NO_DATA_PACKAGE_FILE = "no-data.deb";
  private static final String LINK_PACKAGE_FILE = "link.deb";
  private static final String ELSEWHERE_LIBRARY_FILE = "elsewhere.so";
  private static final String HARD_LINK_PACKAGE_FILE = "hard.deb";
  private static final String GREEDY_PACKAGE_FILE = "greedy.deb";
  private static final String GREEDY_FOLDER = "greedy";
  private static final String STRONGEST_PACKAGE_FILE = "strongest.deb";
  private static final String STRONGEST_FOLDER = "strongest";
  private static final String PACKAGE_FILE = "package.deb";
  private static final String PLAIN_ARCHIVE_FILE = "plain.zip";
  private static final String HOST_FOLDER = "host";
  private static final String INSTALLATION_FOLDER = "installation";
  private static final String MISSING_MODULE_FILE = "libmissingmodule.so";
  private static final String ROOT_FOLDER = "root";
  private static final String FIRST_CONFIGURATION_FILE = "a.conf";
  private static final String SECOND_CONFIGURATION_FILE = "b.conf";
  private static final String EXCLUDED_CONFIGURATION_FILE = "c.txt";
  private static final String LOCAL_LIBRARY_FOLDER = "usr/local/lib";
  private static final String DEEP_LIBRARY_FOLDER = "opt/deep";
  private static final String GAME_LIBRARY_FOLDER = "opt/game/lib";
  private static final String ARCHITECTURE_LIBRARY_FOLDER = "lib/x86_64-linux-gnu";
  private static final String ARCHITECTURE_USER_LIBRARY_FOLDER = "usr/lib/x86_64-linux-gnu";
  private static final String USER_64_BIT_LIBRARY_FOLDER = "usr/lib64";
  private static final String USER_LIBRARY_FOLDER = "usr/lib";
  private static final String FIRST_ARM_LIBRARY_FOLDER = "empty-root/lib/aarch64-linux-gnu";
  private static final String ABSENT_LIBRARY_FILE = "libmcav-nothing.so";
  private static final String PROVISIONED_FOLDER = "provisioned";
  private static final String LIBRARY_FOLDER = "lib";
  private static final String HEADLESS_AWT_LIBRARY_FILE = "libawt_headless.so";
  private static final String JAVA_BIN_FOLDER = "bin";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String SERVER_FOLDER = "server";
  private static final String JAWT_LIBRARY_FILE = "libjawt.so";
  private static final String PLAIN_PACKAGE_FILE = "plain.deb";
  private static final String USER_FOLDER_ENTRY = "usr/";
  private static final String PLAIN_FOLDER = "plain";
  private static final String INSTALLATION_PREFIX = "debian-11-linux-amd64-";
  private static final String FIRST_OPTIONAL_LIBRARY_PATH = "/opt/a";
  private static final String SECOND_OPTIONAL_LIBRARY_PATH = "/opt/b";
  private static final String EXCLUDED_TEXT_FILE = "b.txt";
  private static final String TOP_LIBRARY_PATH = "/opt/top";
  private static final String TOP_CONFIGURATION_FILE = "top.conf";
  private static final String MULTIARCH_FOLDER = "multiarch";
  private static final String AMD64_LIBRARY_FILE = "amd64.so";
  private static final String ARM64_LIBRARY_FILE = "arm64.so";
  private static final String I386_LIBRARY_FILE = "i386.so";
  private static final String BIG_ENDIAN_LIBRARY_FILE = "big.so";
  private static final String OTHER_MACHINE_LIBRARY_FILE = "other.so";
  private static final String TEXT_LIBRARY_FILE = "text.so";
  private static final String SHORT_LIBRARY_FILE = "short.so";
  private static final String MISSING_LIBRARY_FILE = "missing.so";
  private static final String I386_FOLDER = "i386";
  private static final String X11_LIBRARY_FILE = "libX11.so.6";
  private static final String ETC_FOLDER = "etc";
  private static final String DEPTH_LIBRARY_PREFIX = "/opt/depth";
  private static final String EIGHTH_DEPTH_LIBRARY_FOLDER = "opt/depth8";
  private static final String NINTH_DEPTH_LIBRARY_FOLDER = "opt/depth9";
  private static final String ABSOLUTE_OPTIONAL_LIBRARY_PREFIX = "/opt/";
  private static final String OPTIONAL_LIBRARY_PREFIX = "opt/";
  private static final String GAME_CONFIGURATION_FILE = "game.conf";
  private static final String RELATIVE_LIBRARY_FOLDER = "opt/relative";
  private static final String UPDATED_PACKAGE_PATH = "pool/updates/main/m/mcavtest/libmcavtest1_1.2.3-1+deb11u1_amd64.deb";
  private static final String DATA_ARCHIVE_FILE = "data.tar.xz";
  private static final String LIBRARY_PIN_PREFIX = "linux-amd64 libx 1 ";

  private static final String SECOND_LOADER_CONFIGURATION = "include /etc/ld.so.conf.d/b.conf\n/opt/deep\n";
  private static final String FIRST_INCLUDE_CONFIGURATION = "include /etc/1.conf\n";
  private static final String SORTED_INCLUDE_CONFIGURATION = "include /etc/ld.so.conf.d/*.conf\n";
  private static final String RELATIVE_INCLUDE_CONFIGURATION = "include ld.so.conf.d/*.conf\n";
  private static final String LOCAL_LIBRARY_CONFIGURATION = "/usr/local/lib\n";
  private static final String EXCLUDED_FILE_CONFIGURATION = "/not/included\n";
  private static final String LOADER_CONFIGURATION_PATTERN = "etc/ld.so.conf.d/*.conf";
  private static final String CONFIGURATION_SUFFIX = ".conf";
  private static final String CONFIGURATION_LINE_SUFFIX = ".conf\n";
  private static final String RELATIVE_LIBRARY_CONFIGURATION = "/opt/relative\n";

  private static final int SHA256_CHARACTERS = 64;

  private static final String LIBRARY_LINK_TARGET = "libmcavtest.so.1.2.3";

  private static final String ETC_FOLDER_PATTERN = "etc/";

  private static final String PIN_SEPARATOR = " ";

  private static final String MIRROR_URL = "https://mirror.test/debian/";
  private static final String FIRST_MIRROR_URL = "https://first.test/debian/";
  private static final String SECOND_MIRROR_URL = "https://second.test/debian/";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

  private static final String ARM64_PLATFORM_SUFFIX = "arm64";

  private static final String SESSION_FOLDER = "session";

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

  private static final String DISK_GONE_MESSAGE = "The disk is gone!";

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
      Map.of(LIBRARY, LIBRARY_TEXT.getBytes(StandardCharsets.US_ASCII), COPYRIGHT_FILE, new byte[3]),
      Map.of(LIBRARY_LINK_FILE, LIBRARY_LINK_TARGET),
      DATA_ARCHIVE_FILE
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
      throw new AssertionError(exception);
    }
  }

  private String pin(final byte[] debianPackage) {
    return (
      "linux-amd64 libmcavtest1 1.2.3-1 " +
      this.sha256(debianPackage) +
      PIN_SEPARATOR +
      debianPackage.length +
      PIN_SEPARATOR +
      POOL +
      " libmcavtest.so.1=" +
      LIBRARY
    );
  }

  // downloads come from memory; a mirror named "broken" fails
  private LinuxLibraries installer(final byte[] debianPackage, final List<String> mirrors, final List<String> pins) {
    return new LinuxLibraries(
      this.folder.resolve(CACHE_FOLDER),
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
      final String message = "The mirror is down!";
      throw new IOException(message);
    }

    if (!sha256.equals(this.sha256(debianPackage))) {
      final String message = "The hash does not match!";
      throw new IOException(message);
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
        final boolean fromTheMainPool = pin.pool().startsWith(MAIN_POOL_PREFIX) || pin.pool().startsWith(UPDATES_POOL_PREFIX);
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
      assertEquals(NSS_LIBRARY_FILE, mainLibrary, "NSS is linked as a whole, decided by its main library");

      final boolean holdsTheSoftwareToken = nss.sonames().contains(SOFTWARE_TOKEN_LIBRARY_FILE);
      assertTrue(holdsTheSoftwareToken);

      final boolean twelveHexadecimalDigits = FINGERPRINT.matcher(LinuxLibraries.fingerprint(pins)).matches();
      assertTrue(twelveHexadecimalDigits);
    }

    final List<String> expectedMirrors = List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/");
    assertEquals(expectedMirrors, LinuxLibraries.MIRRORS);
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
            throw new IOException(DISK_GONE_MESSAGE);
          }

          @Override
          public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            throw new IOException(DISK_GONE_MESSAGE);
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
    final String hash = HASH_FILLER.repeat(SHA256_CHARACTERS);
    for (final String line : List.of(
      LIBRARY_PIN_PREFIX + hash + " 1 pool/main/x/x/x.deb",
      "Linux_amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      LIBRARY_PIN_PREFIX + hash.substring(1) + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      LIBRARY_PIN_PREFIX + hash + " 1 ../x.deb libx.so.1=usr/lib/x/libx.so.1",
      LIBRARY_PIN_PREFIX + hash + " 1 pool/main/x/x/x.deb x=usr/lib/x/libx.so.1",
      LIBRARY_PIN_PREFIX + hash + " 1 pool/main/x/x/x.deb libx.so.1=etc/passwd",
      LIBRARY_PIN_PREFIX + hash + " 1 pool/main/x/x/x.deb usr/lib/x/libx.so.1",
      LIBRARY_PIN_PREFIX + hash + " many pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      HOST_DIRECTIVE,
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
    final String line = LIBRARY_PIN_PREFIX + HASH_FILLER.repeat(SHA256_CHARACTERS) + " 1 pool/main/x/x/x.deb =usr/lib/x/libx.so.1";
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      new LinuxLibraries(this.folder, (uri, destination, sha256, size) -> {}, List.of(), List.of(line))
    );

    final String message = refused.getMessage();
    final boolean namesTheEntry = message.startsWith("The value =usr/lib/x/libx.so.1 of the pin ");
    assertTrue(namesTheEntry, message);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogTheSizeOfTheDownloadInMegabytes() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String pin = this.pin(debianPackage).replace(PIN_SEPARATOR + debianPackage.length + PIN_SEPARATOR, " 3000000 ");
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve(CACHE_FOLDER),
      (uri, destination, sha256, size) -> Files.write(destination, debianPackage),
      List.of(MIRROR_URL),
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
      List.of(MIRROR_URL),
      List.of("# a comment", "", this.pin(debianPackage))
    );
    final Path installation = libraries.install(PLATFORM);
    final List<URI> expectedDownloads = List.of(URI.create(MIRROR_URL + POOL));
    assertEquals(expectedDownloads, this.downloads);
    try (final Stream<Path> files = Files.list(installation)) {
      final List<String> fileNames = files
        .map(file -> file.getFileName().toString())
        .sorted()
        .toList();
      final List<String> expectedFileNames = List.of(INSTALLATION_LOCK_FILE, LIBRARY_NAME);
      assertEquals(expectedFileNames, fileNames, "neither the link nor the documentation of the package");
    }

    final Path library = installation.resolve(LIBRARY_NAME);

    final byte[] libraryBytes = Files.readAllBytes(library);
    final byte[] expectedLibraryBytes = LIBRARY_TEXT.getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(expectedLibraryBytes, libraryBytes);

    if (library.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
      final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
      final Set<PosixFilePermission> expectedLibraryPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedLibraryPermissions, libraryPermissions);

      final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
      final Set<PosixFilePermission> expectedInstallationPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedInstallationPermissions, installationPermissions);

      final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(this.folder.resolve(CACHE_FOLDER));
      final Set<PosixFilePermission> expectedCachePermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedCachePermissions, cachePermissions);
    }

    final Path secondInstallation = libraries.install(PLATFORM);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.size();
    assertEquals(1, downloadCount);

    try (final Stream<Path> left = Files.list(this.folder.resolve(CACHE_FOLDER))) {
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
      this.folder.resolve(CACHE_FOLDER),
      (_, destination, _, _) -> this.downloadAndRecordLeftOver(debianPackage, destination, leftOver),
      List.of(MIRROR_URL),
      List.of(this.pin(debianPackage), otherPin)
    );
    final Path installation = libraries.install(PLATFORM);
    final boolean otherLibraryInstalled = Files.isRegularFile(installation.resolve(OTHER_LIBRARY_FILE));
    assertTrue(otherLibraryInstalled);

    final List<Boolean> expectedLeftoverStates = List.of(false, false);
    assertEquals(expectedLeftoverStates, leftOver, "each package downloads into a free spot");
  }

  private void downloadAndRecordLeftOver(final byte[] debianPackage, final Path destination, final List<Boolean> leftOver)
    throws IOException {
    final boolean exists = Files.exists(destination);
    leftOver.add(exists);
    Files.write(destination, debianPackage);
  }

  @Test
  void shouldTakeTheGroupWritePermissionFromLibrariesAnEarlierVersionInstalled() throws IOException {
    assumeTrue(
      this.folder.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW),
      "This file system has no POSIX permissions!"
    );
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of(MIRROR_URL), List.of(this.pin(debianPackage)));
    final Path installation = libraries.install(PLATFORM);
    final Path cache = this.folder.resolve(CACHE_FOLDER);
    final Path library = installation.resolve(LIBRARY_NAME);
    Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rwxrwxrwx"));
    final Path secondInstallation = libraries.install(PLATFORM);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.size();
    assertEquals(1, downloadCount, "nothing is installed again");

    final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(cache);
    final Set<PosixFilePermission> expectedCachePermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedCachePermissions, cachePermissions);

    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    final Set<PosixFilePermission> expectedInstallationPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedInstallationPermissions, installationPermissions);

    final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
    final Set<PosixFilePermission> expectedLibraryPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedLibraryPermissions, libraryPermissions);
  }

  @Test
  void shouldTryTheNextMirrorWhenTheDownloadOfTheFirstCannotBeMovedIntoPlace() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve(CACHE_FOLDER),
      (uri, destination, _, _) -> this.downloadFailingToMoveFromTheFirstMirror(debianPackage, uri, destination),
      List.of(FIRST_MIRROR_URL, SECOND_MIRROR_URL),
      List.of(this.pin(debianPackage))
    );

    final Path installation = libraries.install(PLATFORM);
    final List<URI> expected = List.of(URI.create(FIRST_MIRROR_URL + POOL), URI.create(SECOND_MIRROR_URL + POOL));
    assertEquals(expected, this.downloads);

    final boolean installed = Files.isRegularFile(installation.resolve(LIBRARY_NAME));
    assertTrue(installed);
  }

  private void downloadFailingToMoveFromTheFirstMirror(final byte[] debianPackage, final URI uri, final Path destination)
    throws IOException {
    this.downloads.add(uri);

    if (uri.getHost().equals("first.test")) {
      final String message = "The part file could not be moved into place!";
      throw new FileOperationException(message);
    }

    Files.write(destination, debianPackage);
  }

  @Test
  void shouldFollowAFailingMirrorWithTheNextAndReportWhenAllFail() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries fallback = this.installer(
      debianPackage,
      List.of("https://broken.test/debian/", MIRROR_URL),
      List.of(this.pin(debianPackage))
    );
    fallback.install(PLATFORM);
    final int downloadCount = this.downloads.size();
    assertEquals(2, downloadCount);

    this.downloads.clear();
    final LinuxLibraries none = new LinuxLibraries(
      this.folder.resolve(OTHER_CACHE_FOLDER),
      (uri, destination, _, _) -> this.writeAndFailDownload(uri, destination),
      List.of(FIRST_MIRROR_URL, SECOND_MIRROR_URL),
      List.of(this.pin(debianPackage))
    );
    final IOException failure = assertThrows(IOException.class, () -> none.install(PLATFORM));
    final String failureMessage = failure.getMessage();
    assertEquals("The package libmcavtest1 could not be downloaded from any mirror!", failureMessage);

    final String firstMirrorMessage = failure.getCause().getMessage();
    assertEquals("The mirror first.test is down!", firstMirrorMessage);

    final String secondMirrorMessage = failure.getCause().getSuppressed()[0].getMessage();
    assertEquals("The mirror second.test is down!", secondMirrorMessage);

    try (final Stream<Path> left = Files.list(this.folder.resolve(OTHER_CACHE_FOLDER))) {
      final List<String> leftFileNames = left.map(file -> file.getFileName().toString()).toList();
      final List<String> expectedLeftFileNames = List.of(
        INSTALLATION_PREFIX + LinuxLibraries.fingerprint(none.getPins(PLATFORM)) + LOCK_FILE_SUFFIX
      );
      assertEquals(expectedLeftFileNames, leftFileNames);
    }
  }

  private void writeAndFailDownload(final URI uri, final Path destination) throws IOException {
    this.downloads.add(uri);
    Files.write(destination, new byte[1]);
    final String message = "The mirror %s is down!".formatted(uri.getHost());
    throw new IOException(message);
  }

  @Test
  void shouldRefuseAPlatformWithoutLibraries() {
    final LinuxLibraries libraries = new LinuxLibraries(this.folder, (uri, destination, sha256, size) -> {}, List.of(), List.of());
    final IOException failure = assertThrows(IOException.class, () -> libraries.install("linux-riscv64"));
    final String message = failure.getMessage();
    assertEquals("Platform linux-riscv64 has no libraries in MCAV!", message);
  }

  @Test
  void shouldRequireAPackageToHoldItsDataAndEveryPinnedLibraryAsARegularFile() throws IOException {
    final Map<String, String> wanted = Map.of(LIBRARY, LIBRARY_NAME);
    final Path noData = Files.write(
      this.folder.resolve(NO_DATA_PACKAGE_FILE),
      this.debianPackage(Map.of(LIBRARY, new byte[1]), Map.of(), "data.tar.zst")
    );
    final IOException member = assertThrows(IOException.class, () -> this.extractIntoNewFolder(noData, wanted, "a"));
    final Throwable noDataCause = member.getCause();
    assertInstanceOf(InstallationException.class, noDataCause);

    final Path linkOnly = Files.write(
      this.folder.resolve(LINK_PACKAGE_FILE),
      this.debianPackage(Map.of(), Map.of(LIBRARY, ELSEWHERE_LIBRARY_FILE), DATA_ARCHIVE_FILE)
    );
    final IOException link = assertThrows(IOException.class, () -> this.extractIntoNewFolder(linkOnly, wanted, "b"));
    final String linkMessage = link.getMessage();
    final String expectedLinkMessage = "The package link.deb lacks the files [%s] that its pin lists!".formatted(LIBRARY);
    assertEquals(expectedLinkMessage, linkMessage);

    final boolean unpackedKept = Files.exists(this.folder.resolve(UNPACKED_LINK_PACKAGE_FILE));
    assertFalse(unpackedKept, "the unpacked package is removed");

    final Path hardLink = Files.write(
      this.folder.resolve(HARD_LINK_PACKAGE_FILE),
      this.debianPackage(Map.of(), Map.of(LIBRARY, "=elsewhere.so"), DATA_ARCHIVE_FILE)
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
    final Path debianPackage = Files.write(this.folder.resolve(GREEDY_PACKAGE_FILE), greedy);
    final Path target = Files.createDirectory(this.folder.resolve(GREEDY_FOLDER));

    assertThrows(IOException.class, () -> LinuxLibraries.extract(debianPackage, wanted, target));

    try (final Stream<Path> written = Files.list(target)) {
      final List<Path> writtenFiles = written.toList();
      assertEquals(List.of(), writtenFiles, "nothing of a refused package is written");
    }
  }

  @Test
  void shouldReadAPackageCompressedWithTheStrongestCompressionOfDpkg() throws IOException {
    final byte[] strongest = this.withDictionary(this.testPackage(), DICTIONARY_64_MEBIBYTES);
    final Path debianPackage = Files.write(this.folder.resolve(STRONGEST_PACKAGE_FILE), strongest);
    final Path target = Files.createDirectory(this.folder.resolve(STRONGEST_FOLDER));

    LinuxLibraries.extract(debianPackage, Map.of(LIBRARY, LIBRARY_NAME), target);

    final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
    final byte[] expectedLibraryBytes = LIBRARY_TEXT.getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(expectedLibraryBytes, libraryBytes);
  }

  @Test
  void shouldWriteLibrariesOnFileSystemsWithoutPosixPermissions() throws IOException {
    final Path debianPackage = Files.write(this.folder.resolve(PACKAGE_FILE), this.testPackage());
    try (final FileSystem zip = FileSystems.newFileSystem(this.folder.resolve(PLAIN_ARCHIVE_FILE), Map.of("create", "true"))) {
      final Path target = zip.getPath(ROOT);
      LinuxLibraries.extract(debianPackage, Map.of(LIBRARY, LIBRARY_NAME), target);
      final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
      final byte[] expectedLibraryBytes = LIBRARY_TEXT.getBytes(StandardCharsets.US_ASCII);
      assertArrayEquals(expectedLibraryBytes, libraryBytes);
    }
  }

  @Test
  void shouldOnlyLinkThePackagesTheServerLacksIntoTheSession() throws IOException {
    final Path host = Files.createDirectories(this.folder.resolve(HOST_FOLDER));
    Files.write(host.resolve(PRESENT_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    Files.write(host.resolve(COMPRESSION_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final String hash = HASH_FILLER.repeat(SHA256_CHARACTERS);
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
    final Path installation = this.folder.resolve(INSTALLATION_FOLDER);
    final Path session = Files.createDirectory(this.folder.resolve(SESSION_FOLDER));
    final Path linked = LinuxLibraries.link(installation, session, libraries.findMissing(PLATFORM, List.of(host)));
    final Path expectedLinkedFolder = session.resolve(LinuxLibraries.SESSION_FOLDER);
    assertEquals(expectedLinkedFolder, linked);

    try (final Stream<Path> links = Files.list(linked)) {
      final List<String> linkNames = links
        .map(link -> link.getFileName().toString())
        .sorted()
        .toList();
      final List<String> expectedLinkNames = List.of(MISSING_SONAME_FILE, MISSING_MODULE_FILE);
      assertEquals(expectedLinkNames, linkNames);
    }

    final Path linkTarget = Files.readSymbolicLink(linked.resolve(MISSING_SONAME_FILE));
    final Path expectedLinkTarget = installation.resolve(MISSING_SONAME_FILE);
    assertEquals(expectedLinkTarget, linkTarget);

    assertDoesNotThrow(() -> libraries.requireHostLibraries(PLATFORM, List.of(host)));

    final Path bare = Files.createDirectory(this.folder.resolve(BARE_HOST_FOLDER));
    final BrowserException lacking = assertThrows(BrowserUnavailableException.class, () ->
      libraries.requireHostLibraries(PLATFORM, List.of(bare))
    );
    final String message = lacking.getMessage();
    assertEquals("The browser needs libz.so.1, which this server lacks and MCAV does not bring!", message);
  }

  @Test
  void shouldTakeTheFoldersOfTheLoaderFromItsConfigurationAndTheDefaults() throws IOException {
    final Path root = this.folder.resolve(ROOT_FOLDER);
    final Path configuration = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(
      root.resolve(LOADER_CONFIGURATION),
      "# the loader\ninclude /etc/ld.so.conf.d/*.conf\n/opt/game/lib # after\nhwcap 0 nosegneg\n\ninclude /missing/*.conf\n"
    );
    Files.writeString(configuration.resolve(FIRST_CONFIGURATION_FILE), LOCAL_LIBRARY_CONFIGURATION);
    Files.writeString(configuration.resolve(SECOND_CONFIGURATION_FILE), SECOND_LOADER_CONFIGURATION);
    Files.writeString(configuration.resolve(EXCLUDED_CONFIGURATION_FILE), EXCLUDED_FILE_CONFIGURATION);
    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final List<Path> expected = new ArrayList<>();
    for (final String path : List.of(
      LOCAL_LIBRARY_FOLDER,
      DEEP_LIBRARY_FOLDER,
      GAME_LIBRARY_FOLDER,
      ARCHITECTURE_LIBRARY_FOLDER,
      ARCHITECTURE_USER_LIBRARY_FOLDER,
      "lib64",
      USER_64_BIT_LIBRARY_FOLDER,
      LIBRARY_FOLDER,
      USER_LIBRARY_FOLDER
    )) {
      expected.add(root.resolve(path));
    }

    assertEquals(expected, folders);

    final List<Path> arm = LinuxLibraries.hostFolders(this.folder.resolve(EMPTY_ROOT_FOLDER), ARM_PLATFORM);
    final Path firstFolder = arm.getFirst();
    final Path expectedFirstFolder = this.folder.resolve(FIRST_ARM_LIBRARY_FOLDER);
    assertEquals(expectedFirstFolder, firstFolder);

    final int folderCount = arm.size();
    assertEquals(6, folderCount, "a system without a configuration has the default folders only");

    final List<Path> rootMatches = LinuxLibraries.glob(Path.of(ROOT), ROOT);
    assertEquals(List.of(), rootMatches, "the root names no files");

    final boolean present = LinuxLibraries.isPresent(ABSENT_LIBRARY_FILE, List.of(), PLATFORM);
    assertFalse(present);
  }

  @Test
  void shouldFollowThePinnedHashesInTheFingerprint() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of(), List.of(this.pin(debianPackage)));
    final String fingerprint = LinuxLibraries.fingerprint(libraries.getPins(PLATFORM));
    final String expectedFingerprint = this.sha256(this.sha256(debianPackage).getBytes(StandardCharsets.US_ASCII)).substring(0, 12);
    assertEquals(expectedFingerprint, fingerprint);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldLinkTheLibrariesTheServerLacksIntoTheDefaultSessionsOfLinux() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of(MIRROR_URL),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(new JcefNatives(), List.of());
    final HelperLauncher withLibraries = factory.withLibraries(linux, libraries);
    final Path session = Files.createDirectory(this.folder.resolve(SESSION_FOLDER));
    final Path linked = withLibraries.linkLibraries(session);
    final boolean libraryLinked = Files.isSymbolicLink(linked.resolve(LIBRARY_NAME));
    assertTrue(libraryLinked, "this machine lacks the test library");

    final HelperLauncher windows = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.WINDOWS);
    final HelperLauncher launcherWithLibraries = factory.withLibraries(windows, libraries);
    assertSame(windows, launcherWithLibraries);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldDownloadNothingWhenTheServerHasEveryLibrary() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = this.installer(
      debianPackage,
      List.of(MIRROR_URL),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    final Path root = Files.createDirectories(this.folder.resolve(PROVISIONED_FOLDER));
    final Path loader = Files.createDirectories(LinuxLibraries.hostFolders(root, platform).getFirst());
    Files.write(
      loader.resolve(LIBRARY_NAME),
      this.elf(ELF_64_BIT, LITTLE_ENDIAN, platform.endsWith(ARM64_PLATFORM_SUFFIX) ? ARM64_MACHINE : AMD64_MACHINE)
    );
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(new JcefNatives(), List.of());
    final HelperLauncher launcherWithLibraries = factory.withLibraries(linux, libraries, root);
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
      List.of(MIRROR_URL),
      List.of(this.pin(debianPackage).replace(PLATFORM, platform))
    );
    // as Ubuntu's openjdk-25-jre-headless: no libjawt.so, which libjcef.so links against
    final Path runtime = Files.createDirectories(this.folder.resolve(JAVA_RUNTIME_FOLDER));
    final Path library = Files.createDirectories(runtime.resolve(LIBRARY_FOLDER));
    Files.write(library.resolve(HEADLESS_AWT_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final HelperLauncher headless = new HelperLauncher(
      runtime.resolve(JAVA_BIN_FOLDER).resolve(JAVA_EXECUTABLE),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      Os.LINUX,
      Map.of(),
      START_TIMEOUT_MILLISECONDS
    );
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(new JcefNatives(), List.of());
    final Executable linking = () -> factory.withLibraries(headless, libraries, server);
    final BrowserException refused = assertThrows(BrowserUnavailableException.class, linking);
    final String message = refused.getMessage();
    final String expectedMessage =
      "The browser needs the Java runtime's libjawt.so, which " +
      runtime +
      " lacks: the headless Java packages of Debian and Ubuntu (openjdk-*-jre-headless) leave it out; install the full" +
      " package of the same version (openjdk-*-jre), or run the server on a full Java runtime!";
    assertEquals(expectedMessage, message);
    assertEquals(List.of(), this.downloads, "no library is downloaded for a runtime the browser cannot use");

    Files.write(library.resolve(JAWT_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final Path loader = Files.createDirectories(LinuxLibraries.hostFolders(server, platform).getFirst());
    Files.write(
      loader.resolve(LIBRARY_NAME),
      this.elf(ELF_64_BIT, LITTLE_ENDIAN, platform.endsWith(ARM64_PLATFORM_SUFFIX) ? ARM64_MACHINE : AMD64_MACHINE)
    );
    final HelperLauncher launcherWithLibraries = factory.withLibraries(headless, libraries, server);
    assertSame(headless, launcherWithLibraries, "a full runtime is used");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRefuseALinuxServerThatLacksAHostLibrary() throws IOException {
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256, size) -> {},
      List.of(),
      List.of("host %s libz.so.1".formatted(platform))
    );
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(new JcefNatives(), List.of());
    final Executable linking = () -> factory.withLibraries(linux, libraries, server);
    final BrowserException refused = assertThrows(BrowserUnavailableException.class, linking);
    final String message = refused.getMessage();
    assertEquals("The browser needs libz.so.1, which this server lacks and MCAV does not bring!", message);
  }

  @Test
  void shouldReadAPackageWhoseNamesHaveNoPrefixAndThatHoldsFolders() throws IOException {
    final Path plain = Files.write(
      this.folder.resolve(PLAIN_PACKAGE_FILE),
      this.debianPackage(Map.of(LIBRARY, new byte[] { 7 }), Map.of(), DATA_ARCHIVE_FILE, "", List.of(USER_FOLDER_ENTRY))
    );
    final Path target = Files.createDirectories(this.folder.resolve(PLAIN_FOLDER));
    LinuxLibraries.extract(plain, Map.of(LIBRARY, LIBRARY_NAME), target);
    final byte[] libraryBytes = Files.readAllBytes(target.resolve(LIBRARY_NAME));
    final byte[] expectedLibraryBytes = new byte[] { 7 };
    assertArrayEquals(expectedLibraryBytes, libraryBytes);
  }

  @Test
  void shouldNotInstallAgainLibrariesAnotherProcessInstalledWhileThisOneWaited() throws Exception {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of("https://deb.test/debian/"), List.of(this.pin(debianPackage)));
    final Path installation = this.folder
      .resolve(CACHE_FOLDER)
      .resolve(INSTALLATION_PREFIX + LinuxLibraries.fingerprint(libraries.getPins(PLATFORM)));
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
    Files.writeString(configurationDirectory.resolve(FIRST_CONFIGURATION_FILE), FIRST_OPTIONAL_LIBRARY_PATH);
    Files.writeString(configurationDirectory.resolve(EXCLUDED_TEXT_FILE), SECOND_OPTIONAL_LIBRARY_PATH);
    final List<Path> configurationMatches = LinuxLibraries.glob(this.folder, LOADER_CONFIGURATION_PATTERN);
    final List<Path> expectedConfigurationMatches = List.of(configurationDirectory.resolve(FIRST_CONFIGURATION_FILE));
    assertEquals(expectedConfigurationMatches, configurationMatches);

    Files.writeString(this.folder.resolve(TOP_CONFIGURATION_FILE), TOP_LIBRARY_PATH);
    final List<Path> topMatches = LinuxLibraries.glob(this.folder, "*.conf");
    final List<Path> expectedTopMatches = List.of(this.folder.resolve(TOP_CONFIGURATION_FILE));
    assertEquals(expectedTopMatches, topMatches);

    final List<Path> rootMatches = LinuxLibraries.glob(this.folder, ROOT);
    assertEquals(List.of(), rootMatches);

    final List<Path> trailingSlashMatches = LinuxLibraries.glob(this.folder, ETC_FOLDER_PATTERN);
    assertEquals(List.of(), trailingSlashMatches);
  }

  @Test
  void shouldCountALibraryOfTheServerOnlyWhenItIsBuiltForThePlatform() throws IOException {
    final Path folder = Files.createDirectories(this.folder.resolve(MULTIARCH_FOLDER));
    final Path amd64 = Files.write(folder.resolve(AMD64_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final Path arm64 = Files.write(folder.resolve(ARM64_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, ARM64_MACHINE));
    final boolean amd64LibraryFits = LinuxLibraries.isBuiltFor(amd64, PLATFORM);
    assertTrue(amd64LibraryFits);

    final boolean amd64LibraryFitsArm = LinuxLibraries.isBuiltFor(amd64, ARM_PLATFORM);
    assertFalse(amd64LibraryFitsArm);

    final boolean arm64LibraryFits = LinuxLibraries.isBuiltFor(arm64, ARM_PLATFORM);
    assertTrue(arm64LibraryFits);

    final boolean thirtyTwoBitLibraryFits = LinuxLibraries.isBuiltFor(
      Files.write(folder.resolve(I386_LIBRARY_FILE), this.elf(ELF_32_BIT, LITTLE_ENDIAN, I386_MACHINE)),
      PLATFORM
    );
    assertFalse(thirtyTwoBitLibraryFits, "32 bits");

    final boolean bigEndianLibraryFits = LinuxLibraries.isBuiltFor(
      Files.write(folder.resolve(BIG_ENDIAN_LIBRARY_FILE), this.elf(2, 2, 62)),
      PLATFORM
    );
    assertFalse(bigEndianLibraryFits, "big-endian");

    final boolean otherMachineFits = LinuxLibraries.isBuiltFor(
      Files.write(folder.resolve(OTHER_MACHINE_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE + 0x100)),
      PLATFORM
    );
    assertFalse(otherMachineFits, "a machine that matches x86-64 only in its low byte");

    final byte[] notElf = this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE);
    notElf[1] = 'X';
    final boolean textFileFits = LinuxLibraries.isBuiltFor(Files.write(folder.resolve(TEXT_LIBRARY_FILE), notElf), PLATFORM);
    assertFalse(textFileFits, "no ELF file");

    final boolean shortFileFits = LinuxLibraries.isBuiltFor(Files.write(folder.resolve(SHORT_LIBRARY_FILE), new byte[19]), PLATFORM);
    assertFalse(shortFileFits, "too short");

    final boolean missingFileFits = LinuxLibraries.isBuiltFor(folder.resolve(MISSING_LIBRARY_FILE), PLATFORM);
    assertFalse(missingFileFits, "no file");

    final Path i386 = Files.createDirectories(this.folder.resolve(I386_FOLDER));
    Files.write(i386.resolve(X11_LIBRARY_FILE), this.elf(ELF_32_BIT, LITTLE_ENDIAN, I386_MACHINE));
    final boolean present = LinuxLibraries.isPresent(X11_LIBRARY_FILE, List.of(i386), PLATFORM);
    assertFalse(present);
  }

  @Test
  void shouldFindALibraryOfThePlatformBehindOneOfAnotherArchitecture() throws IOException {
    final Path i386 = Files.createDirectories(this.folder.resolve(I386_FOLDER));
    Files.write(i386.resolve(X11_LIBRARY_FILE), this.elf(ELF_32_BIT, LITTLE_ENDIAN, I386_MACHINE));
    final Path amd64 = Files.createDirectories(this.folder.resolve(MULTIARCH_FOLDER));
    Files.write(amd64.resolve(X11_LIBRARY_FILE), this.elf(ELF_64_BIT, LITTLE_ENDIAN, AMD64_MACHINE));
    final boolean present = LinuxLibraries.isPresent(X11_LIBRARY_FILE, List.of(i386, amd64), PLATFORM);
    assertTrue(present, "the loader skips the 32-bit library and loads the 64-bit one behind it");
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
    final Path expectedLibrariesFolder = IoUtils.getCachedFolder().resolve(LIBRARY_CACHE_FOLDER);
    assertEquals(expectedLibrariesFolder, librariesFolder);
  }

  @Test
  void shouldReplaceAnInstallationThatWasNeverFinished() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final LinuxLibraries libraries = this.installer(debianPackage, List.of(MIRROR_URL), List.of(this.pin(debianPackage)));
    final String name = INSTALLATION_PREFIX + LinuxLibraries.fingerprint(libraries.getPins(PLATFORM));
    final Path unfinished = Files.createDirectories(this.folder.resolve(CACHE_FOLDER).resolve(name));
    Files.writeString(unfinished.resolve(LEFTOVER_FILE), "partial");
    final Path installation = libraries.install(PLATFORM);
    assertEquals(unfinished, installation);

    final boolean libraryInstalled = Files.isRegularFile(installation.resolve(LIBRARY_NAME));
    assertTrue(libraryInstalled);

    final boolean leftOverKept = Files.exists(installation.resolve(LEFTOVER_FILE));
    assertFalse(leftOverKept, "nothing of the unfinished installation is kept");
  }

  @Test
  void shouldFollowIncludesEightDeep() throws IOException {
    final Path root = this.folder.resolve(DEEP_ROOT_FOLDER);
    final Path etc = Files.createDirectories(root.resolve(ETC_FOLDER));
    Files.writeString(etc.resolve(LOADER_CONFIGURATION_FILE), FIRST_INCLUDE_CONFIGURATION);
    for (int depth = 1; depth <= 9; depth++) {
      Files.writeString(
        etc.resolve(depth + CONFIGURATION_SUFFIX),
        DEPTH_LIBRARY_PREFIX + depth + DEPTH_INCLUDE_PREFIX + (depth + 1) + CONFIGURATION_LINE_SUFFIX
      );
    }

    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final boolean followsEightDeep = folders.contains(root.resolve(EIGHTH_DEPTH_LIBRARY_FOLDER));
    assertTrue(followsEightDeep, folders::toString);

    final boolean followsNineDeep = folders.contains(root.resolve(NINTH_DEPTH_LIBRARY_FOLDER));
    assertFalse(followsNineDeep, "an include nine deep is beyond the limit");
  }

  @Test
  void shouldReadIncludedFilesInTheOrderOfTheirNames() throws IOException {
    final Path root = this.folder.resolve(SORTED_ROOT_FOLDER);
    final Path configuration = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(root.resolve(LOADER_CONFIGURATION), SORTED_INCLUDE_CONFIGURATION);
    final List<String> names = List.of("zeta", "alpha", "mike", "delta", "kilo", "bravo", "yankee", "echo");
    for (final String name : names) {
      Files.writeString(configuration.resolve(name + CONFIGURATION_SUFFIX), ABSOLUTE_OPTIONAL_LIBRARY_PREFIX + name + "\n");
    }

    final List<Path> folders = LinuxLibraries.hostFolders(root, PLATFORM);
    final List<Path> expected = names
      .stream()
      .sorted()
      .map(name -> root.resolve(OPTIONAL_LIBRARY_PREFIX + name))
      .toList();
    final List<Path> includedFolders = folders.subList(0, names.size());
    assertEquals(expected, includedFolders);
  }

  @Test
  void shouldResolveARelativeIncludeOfTheLoaderBesideItsFile() throws IOException {
    final Path root = this.folder.resolve(RELATIVE_ROOT_FOLDER);
    final Path etc = Files.createDirectories(root.resolve(LOADER_INCLUDES));
    Files.writeString(root.resolve(LOADER_CONFIGURATION), RELATIVE_INCLUDE_CONFIGURATION);
    Files.writeString(etc.resolve(GAME_CONFIGURATION_FILE), RELATIVE_LIBRARY_CONFIGURATION);
    final Path firstFolder = LinuxLibraries.hostFolders(root, PLATFORM).getFirst();
    final Path expectedFirstFolder = root.resolve(RELATIVE_LIBRARY_FOLDER);
    assertEquals(expectedFirstFolder, firstFolder);
  }

  @Test
  void shouldFetchAPackageOfTheSecurityUpdatesFromTheirMirrors() throws IOException {
    final byte[] debianPackage = this.testPackage();
    final List<URI> asked = new ArrayList<>();
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder.resolve(SECURITY_CACHE_FOLDER),
      (uri, destination, _, _) -> this.recordAndWritePackage(asked, debianPackage, uri, destination),
      List.of("https://main.test/debian/"),
      List.of("https://security.test/debian-security/"),
      List.of(this.pin(debianPackage).replace(POOL, UPDATED_PACKAGE_PATH))
    );
    libraries.install(PLATFORM);
    final List<URI> expectedRequests = List.of(
      URI.create("https://security.test/debian-security/pool/updates/main/m/mcavtest/libmcavtest1_1.2.3-1+deb11u1_amd64.deb")
    );
    assertEquals(expectedRequests, asked);

    final String secondSecurityMirror = LinuxLibraries.SECURITY_MIRRORS.get(1);
    assertEquals("https://snapshot.debian.org/archive/debian-security/20260901T000000Z/", secondSecurityMirror);
  }

  private void recordAndWritePackage(final List<URI> asked, final byte[] debianPackage, final URI uri, final Path destination)
    throws IOException {
    asked.add(uri);

    Files.write(destination, debianPackage);
  }
}

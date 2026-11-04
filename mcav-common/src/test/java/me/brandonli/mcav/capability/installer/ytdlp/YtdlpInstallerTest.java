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
package me.brandonli.mcav.capability.installer.ytdlp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class YtdlpInstallerTest {

  private static final String RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/";
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Platform CURRENT_PLATFORM = Platform.getCurrentPlatform();
  private static final String EXECUTABLE = "yt-dlp_test";
  private static final String ZIP_PATH = "/yt-dlp_test.zip";
  private static final byte[] LAUNCHER = "launcher".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LIBRARY = "library".getBytes(StandardCharsets.UTF_8);

  private static final String DOWNLOADS_RESOURCE = "mcav/common/installers/yt-dlp.json";

  private static final String ZIPPED_URL = "https://example.com/files/yt-dlp_linux_armv7l.zip";
  private static final String UPPER_CASE_ZIPPED_URL = "https://example.com/files/YT-DLP.ZIP";
  private static final String STANDALONE_URL = "https://example.com/files/yt-dlp_linux";

  private static final String NAMELESS_ZIP_PATH = "/.zip";
  private static final String STANDALONE_PATH = "/yt-dlp_linux";

  private static final int OK_STATUS = 200;

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private static final String PROGRAM_NAME = "yt-dlp";
  private static final String WINDOWS_EXECUTABLE_NAME = "yt-dlp.exe";
  private static final String POSIX_EXECUTABLE_NAME = "yt-dlp";
  private static final String ZIPPED_EXECUTABLE_NAME = "yt-dlp_linux_armv7l";
  private static final String UPPER_CASE_EXECUTABLE_NAME = "YT-DLP";

  private static final String ZIP_FILE_NAME = "yt-dlp_test.zip";
  private static final String UNPACKED_FOLDER_NAME = "yt-dlp-unpacked";
  private static final String UNPACKING_FOLDER_NAME = "yt-dlp-unpacking";

  private static final String LIBRARY_ENTRY_NAME = "_internal/library.so";
  private static final String NESTED_EXECUTABLE_NAME = "_internal/yt-dlp_test";
  private static final String EXECUTABLE_IN_FOLDER_NAME = "yt-dlp_test/yt-dlp_test";
  private static final String ESCAPING_ENTRY_NAME = "../escaped";
  private static final String ESCAPED_FILE_NAME = "escaped";

  private static final String STALE_FILE_NAME = "stale.txt";
  private static final String LEFTOVER_FILE_NAME = "leftover.txt";
  private static final String DOWNLOADS_FOLDER_NAME = "downloads";

  private static final String READ_ONLY_PERMISSIONS = "r-x------";
  private static final Set<PosixFilePermission> OWNER_PERMISSIONS = PosixFilePermissions.fromString("rwx------");

  private static final String MCAV_FOLDER_NAME = ".mcav";
  private static final String CACHE_FOLDER_NAME = "cache";

  @TempDir
  private Path folder;

  @Test
  void shouldPinEveryBundledDownloadToRelease20260819() {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(DOWNLOADS_RESOURCE);
    assertEquals(8, downloads.length);

    for (final Download download : downloads) {
      final String url = download.getUrl();
      final boolean pinned = url.startsWith(RELEASE_URL);
      final Optional<String> optionalHash = download.getHash();
      final boolean hashFound = optionalHash.isPresent();
      assertTrue(hashFound, url);
      final String hash = optionalHash.orElseThrow();
      final Matcher hashMatcher = SHA_256.matcher(hash);
      final boolean isSha256 = hashMatcher.matches();
      assertTrue(pinned, url);
      assertTrue(isSha256, url);
    }
  }

  @Test
  void shouldBundleOneDownloadPerPlatform() {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(DOWNLOADS_RESOURCE);
    final Set<Platform> platforms = new HashSet<>();
    for (final Download download : downloads) {
      final Platform platform = download.getPlatform();
      final boolean added = platforms.add(platform);
      assertTrue(added, "Only one download may be bundled for %s".formatted(platform));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "WINDOWS, X86, BITS_64, yt-dlp.exe, 66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a",
    "WINDOWS, X86, BITS_32, yt-dlp_x86.exe, a8f91bd41452506bc81ebd2f369b186fea0ee7075413ba00cef9fd346a0a5d0c",
    "WINDOWS, ARM, BITS_64, yt-dlp_arm64.exe, 05b438997bafc3affdfda9d041353c9d73e04dc842207254b655b0887c4445b0",
    "LINUX, X86, BITS_64, yt-dlp_linux, 58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a",
    "LINUX, ARM, BITS_64, yt-dlp_linux_aarch64, b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc",
    "LINUX, ARM, BITS_32, yt-dlp_linux_armv7l.zip, bd51eb5fed7788008f4f30d281a182376a1297910b6c78e747403e757a546508",
    "MAC, ARM, BITS_64, yt-dlp_macos, 0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202",
    "MAC, X86, BITS_64, yt-dlp_macos, 0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202",
  })
  void shouldBundleTheOfficialBuildOfEachPlatform(
    final Os operatingSystem,
    final Arch architecture,
    final Bits bits,
    final String file,
    final String hash
  ) {
    final Platform platform = Platform.ofPlatform(operatingSystem, architecture, bits);
    final Download download = findBundledDownload(platform);
    final String url = download.getUrl();
    final Optional<String> downloadHash = download.getHash();
    final String expectedUrl = RELEASE_URL + file;
    final Optional<String> expectedHash = Optional.of(hash);
    assertEquals(expectedUrl, url);
    assertEquals(expectedHash, downloadHash);
  }

  private static Download findBundledDownload(final Platform platform) {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(DOWNLOADS_RESOURCE);
    for (final Download download : downloads) {
      final Platform downloadPlatform = download.getPlatform();
      final boolean matches = downloadPlatform.equals(platform);
      if (matches) {
        return download;
      }
    }

    final String message = "Platform %s has no bundled yt-dlp download!".formatted(platform);
    throw new AssertionError(message);
  }

  @Test
  void shouldTreatOnlyZipDownloadsAsArchives() {
    final YtdlpInstaller zipped = installerFor(ZIPPED_URL);
    final YtdlpInstaller upperCase = installerFor(UPPER_CASE_ZIPPED_URL);
    final YtdlpInstaller standalone = installerFor(STANDALONE_URL);
    final YtdlpInstaller unsupported = new YtdlpInstaller(this.folder, new Download[0]);
    final boolean zippedArchive = zipped.isArchive();
    final boolean upperCaseArchive = upperCase.isArchive();
    final boolean standaloneArchive = standalone.isArchive();
    final boolean unsupportedArchive = unsupported.isArchive();
    assertTrue(zippedArchive);
    assertTrue(upperCaseArchive);
    assertFalse(standaloneArchive);
    assertFalse(unsupportedArchive);
  }

  @Test
  void shouldFindTheExecutableOfAZipInTheUnpackedFolder() {
    final YtdlpInstaller zipped = installerFor(ZIPPED_URL);
    final YtdlpInstaller upperCase = installerFor(UPPER_CASE_ZIPPED_URL);
    final YtdlpInstaller standalone = installerFor(STANDALONE_URL);
    final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
    final Path zippedExecutable = unpacked.resolve(ZIPPED_EXECUTABLE_NAME);
    final Path upperCaseExecutable = unpacked.resolve(UPPER_CASE_EXECUTABLE_NAME);
    final Os operatingSystem = OsUtils.getOs();
    final String standaloneName = operatingSystem == Os.WINDOWS ? WINDOWS_EXECUTABLE_NAME : POSIX_EXECUTABLE_NAME;
    final Path standaloneExecutable = this.folder.resolve(standaloneName);
    final Path zippedPath = zipped.getPath();
    final Path upperCasePath = upperCase.getPath();
    final Path standalonePath = standalone.getPath();
    assertEquals(zippedExecutable, zippedPath);
    assertEquals(upperCaseExecutable, upperCasePath);
    assertEquals(standaloneExecutable, standalonePath);
  }

  @Test
  void shouldUnpackAZipAndMarkItsExecutable() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER, LIBRARY_ENTRY_NAME, LIBRARY);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
      final Path expected = unpacked.resolve(EXECUTABLE);
      final Path library = unpacked.resolve(LIBRARY_ENTRY_NAME);
      final byte[] launcher = Files.readAllBytes(installed);
      final byte[] libraryContent = Files.readAllBytes(library);
      assertEquals(expected, installed);
      assertArrayEquals(LAUNCHER, launcher);
      assertArrayEquals(LIBRARY, libraryContent);

      this.assertOnlyTheUnpackedFolderIsLeft();
      assertExecutableOnPosix(installed);
    }
  }

  private void assertOnlyTheUnpackedFolderIsLeft() {
    final Path zipFile = this.folder.resolve(ZIP_FILE_NAME);
    final Path unpacking = this.folder.resolve(UNPACKING_FOLDER_NAME);
    final boolean zipLeft = Files.exists(zipFile);
    final boolean unpackingLeft = Files.exists(unpacking);
    assertFalse(zipLeft, "the zip is deleted once it has been unpacked");
    assertFalse(unpackingLeft, "the temporary folder is removed");
  }

  private static void assertExecutableOnPosix(final Path file) {
    final Os operatingSystem = OsUtils.getOs();
    if (operatingSystem == Os.WINDOWS) {
      return;
    }

    final boolean executable = Files.isExecutable(file);
    assertTrue(executable, file.toString());
  }

  @Test
  void shouldReuseAnUnpackedZipWithoutDownloadingItAgain() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller first = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = first.download(FileMode.EXECUTABLE);
      final URI uri = server.uri(ZIP_PATH);
      final String url = uri.toString();
      final YtdlpInstaller second = this.installerFor(url);
      final Path reused = second.download(FileMode.EXECUTABLE);
      final int requests = server.getRequestCount(ZIP_PATH);
      assertEquals(installed, reused);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldReplaceAStaleUnpackedFolderAndLeftoversOfAFailedUnpacking() throws IOException {
    final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
    final Path unpacking = this.folder.resolve(UNPACKING_FOLDER_NAME);
    final Path stale = unpacked.resolve(STALE_FILE_NAME);
    final Path leftover = unpacking.resolve(LEFTOVER_FILE_NAME);
    Files.createDirectories(unpacked);
    Files.createDirectories(unpacking);
    Files.writeString(stale, "stale");
    Files.writeString(leftover, "leftover");
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final boolean staleLeft = Files.exists(stale);
      final Path relocatedLeftover = unpacked.resolve(LEFTOVER_FILE_NAME);
      final boolean leftoverCopied = Files.exists(relocatedLeftover);
      final boolean installedExists = Files.isRegularFile(installed);
      assertFalse(leftoverCopied, "stale extraction files must not be promoted into the new installation");
      assertFalse(staleLeft);
      assertTrue(installedExists);

      this.assertOnlyTheUnpackedFolderIsLeft();
    }
  }

  @Test
  void shouldRejectAZipWithoutTheExecutableAtItsTopLevel() throws IOException {
    final Map<String, byte[]> entries = Map.of(NESTED_EXECUTABLE_NAME, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final InstallationException exception = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final String message = exception.getMessage();
      final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
      final Path unpacking = this.folder.resolve(UNPACKING_FOLDER_NAME);
      final boolean unpackedLeft = Files.exists(unpacked);
      final boolean unpackingLeft = Files.exists(unpacking);
      assertEquals("The yt-dlp zip does not contain yt-dlp_test at its top level!", message);
      assertFalse(unpackedLeft);
      assertFalse(unpackingLeft);
    }
  }

  @Test
  void shouldRejectAZipWhoseExecutableIsAFolder() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE_IN_FOLDER_NAME, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final InstallationException exception = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final String message = exception.getMessage();
      final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
      final Path unpacking = this.folder.resolve(UNPACKING_FOLDER_NAME);
      final boolean unpackedLeft = Files.exists(unpacked);
      final boolean unpackingLeft = Files.exists(unpacking);
      assertEquals("The yt-dlp zip does not contain yt-dlp_test at its top level!", message);
      assertFalse(unpackedLeft);
      assertFalse(unpackingLeft);
    }
  }

  @Test
  void shouldRejectADownloadThatIsNotAZip() throws IOException {
    final byte[] notAZip = "not a zip".getBytes(StandardCharsets.UTF_8);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, notAZip);
      final InstallationException exception = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      final boolean explained = message.endsWith("\" while extracting the yt-dlp zip!");
      assertTrue(explained, message);
      assertInstanceOf(FileOperationException.class, cause);
    }
  }

  @Test
  void shouldRejectAZipWhoseEntriesEscapeTheFolder() throws IOException {
    final Map<String, byte[]> entries = Map.of(ESCAPING_ENTRY_NAME, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final InstallationException exception = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final Throwable cause = exception.getCause();
      final Path escaped = this.folder.resolve(ESCAPED_FILE_NAME);
      final boolean escapedExists = Files.exists(escaped);
      assertInstanceOf(ZipEntryIntegrityException.class, cause);
      assertFalse(escapedExists);
    }
  }

  @Test
  void shouldReportAZipThatCannotBeDeletedAfterUnpacking() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER);
    final byte[] archive = zip(entries);
    final Path downloads = Files.createDirectory(this.folder.resolve(DOWNLOADS_FOLDER_NAME));
    final Path downloaded = Files.write(downloads.resolve(ZIP_FILE_NAME), archive);
    final Set<PosixFilePermission> readOnly = PosixFilePermissions.fromString(READ_ONLY_PERMISSIONS);
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      Files.setPosixFilePermissions(downloads, readOnly);
      final boolean writable = Files.isWritable(downloads);
      Assumptions.assumeFalse(writable, "The user can write every directory!");

      final FileOperationException thrown = assertThrows(FileOperationException.class, () -> installer.install(downloaded));
      final Throwable cause = thrown.getCause();
      assertInstanceOf(AccessDeniedException.class, cause);
    } finally {
      Files.setPosixFilePermissions(downloads, OWNER_PERMISSIONS);
    }
  }

  @Test
  void shouldNameTheExecutableAfterTheProgramWhenTheZipHasNoName() throws IOException {
    final Map<String, byte[]> entries = Map.of(PROGRAM_NAME, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, NAMELESS_ZIP_PATH, archive);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
      final Path expected = unpacked.resolve(PROGRAM_NAME);
      assertEquals(expected, installed);
    }
  }

  @Test
  void shouldInstallAStandaloneExecutableAsDownloaded() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YtdlpInstaller installer = this.serveZip(server, STANDALONE_PATH, LAUNCHER);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final Os operatingSystem = OsUtils.getOs();
      final String executableName = operatingSystem == Os.WINDOWS ? WINDOWS_EXECUTABLE_NAME : POSIX_EXECUTABLE_NAME;
      final Path expected = this.folder.resolve(executableName);
      final byte[] content = Files.readAllBytes(installed);
      final Path unpacked = this.folder.resolve(UNPACKED_FOLDER_NAME);
      final boolean unpackedExists = Files.exists(unpacked);
      assertEquals(expected, installed);
      assertArrayEquals(LAUNCHER, content);
      assertFalse(unpackedExists, "a standalone executable is never unpacked");
      assertExecutableOnPosix(installed);
    }
  }

  private YtdlpInstaller installerFor(final String url) {
    final Download download = new Download(CURRENT_PLATFORM, url);
    final Download[] downloads = { download };
    return new YtdlpInstaller(this.folder, downloads);
  }

  private YtdlpInstaller serveZip(final LocalHttpServer server, final String path, final byte[] body) {
    server.respond(path, OK_STATUS, body);
    final URI uri = server.uri(path);
    final String url = uri.toString();
    final String hash = sha256(body);
    final Download download = new Download(CURRENT_PLATFORM, url, hash);
    final Download[] downloads = { download };
    return new YtdlpInstaller(this.folder, downloads);
  }

  private static byte[] zip(final Map<String, byte[]> entries) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (final ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
        final String name = entry.getKey();
        final byte[] content = entry.getValue();
        final ZipEntry zipEntry = new ZipEntry(name);
        zip.putNextEntry(zipEntry);
        zip.write(content);
        zip.closeEntry();
      }
    }

    return bytes.toByteArray();
  }

  private static String sha256(final byte[] content) {
    final MessageDigest digest = createSha256Digest();
    final byte[] hash = digest.digest(content);
    final HexFormat hexadecimal = HexFormat.of();
    return hexadecimal.formatHex(hash);
  }

  private static MessageDigest createSha256Digest() {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  @ParameterizedTest
  @EnumSource(value = Os.class, names = { "WINDOWS", "MAC" })
  void shouldLetArmMachinesRunTheEmulatedX86Build(final Os operatingSystem) {
    final YtdlpInstaller installer = YtdlpInstaller.create(this.folder);
    final Platform arm = Platform.ofPlatform(operatingSystem, Arch.ARM, Bits.BITS_64);
    final List<Platform> fallbacks = installer.getFallbackPlatforms(arm);
    final Platform x86 = Platform.ofPlatform(operatingSystem, Arch.X86, Bits.BITS_64);
    final List<Platform> expected = List.of(x86);
    assertEquals(expected, fallbacks);
  }

  @Test
  void shouldOfferNoFallbackWhereNoEmulationExists() {
    final YtdlpInstaller installer = YtdlpInstaller.create(this.folder);
    final Platform armLinux = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform armWindows32 = Platform.ofPlatform(Os.WINDOWS, Arch.ARM, Bits.BITS_32);
    final Platform x86Windows = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    final List<Platform> linuxFallbacks = installer.getFallbackPlatforms(armLinux);
    final List<Platform> arm32Fallbacks = installer.getFallbackPlatforms(armWindows32);
    final List<Platform> x86Fallbacks = installer.getFallbackPlatforms(x86Windows);
    final boolean noLinuxFallback = linuxFallbacks.isEmpty();
    final boolean noArm32Fallback = arm32Fallbacks.isEmpty();
    final boolean noX86Fallback = x86Fallbacks.isEmpty();
    assertTrue(noLinuxFallback);
    assertTrue(noArm32Fallback);
    assertTrue(noX86Fallback);
  }

  @Test
  void shouldInstallASingleExecutableIntoTheChosenFolder() {
    final YtdlpInstaller installer = YtdlpInstaller.create(this.folder);
    final Path installerFolder = installer.getFolder();
    final String name = installer.getName();
    final boolean isArchive = installer.isArchive();
    final Path path = installer.getPath();
    final Os operatingSystem = OsUtils.getOs();
    final String executableName = operatingSystem == Os.WINDOWS ? WINDOWS_EXECUTABLE_NAME : POSIX_EXECUTABLE_NAME;
    final Path expectedPath = this.folder.resolve(executableName);
    assertEquals(this.folder, installerFolder);
    assertEquals(PROGRAM_NAME, name);
    assertFalse(isArchive);
    assertEquals(expectedPath, path);
  }

  @Test
  void shouldInstallIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final YtdlpInstaller installer = YtdlpInstaller.create();
      final Path installerFolder = installer.getFolder();
      final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(MCAV_FOLDER_NAME);
      final Path expected = mcavFolder.resolve(CACHE_FOLDER_NAME);
      assertEquals(defaultFolder, installerFolder);
      assertEquals(expected, installerFolder);
    }
  }

  @Test
  void shouldShareOneInstallerOfTheCacheFolder() {
    final YtdlpInstaller first = YtdlpInstaller.shared();
    final YtdlpInstaller second = YtdlpInstaller.shared();
    final Path sharedFolder = first.getFolder();
    final Path folderName = sharedFolder.getFileName();
    final String name = folderName.toString();
    assertSame(first, second, "every user of yt-dlp goes through one installer, so it is downloaded once");
    assertEquals(CACHE_FOLDER_NAME, name);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mainstreamDesktopPlatforms")
  void shouldSupportEveryMainstreamDesktopPlatform(final Platform platform) {
    try (final MockedStatic<Platform> platforms = Mockito.mockStatic(Platform.class, Mockito.CALLS_REAL_METHODS)) {
      platforms.when(Platform::getCurrentPlatform).thenReturn(platform);
      final YtdlpInstaller installer = YtdlpInstaller.create(this.folder);
      final boolean supported = installer.isSupported();
      final Optional<String> optionalUrl = installer.getUrl();
      final String url = optionalUrl.orElseThrow();
      final boolean secure = url.startsWith("https://");
      final boolean namesYtDlp = url.contains(PROGRAM_NAME);
      assertTrue(supported);
      assertTrue(secure, url);
      assertTrue(namesYtDlp, url);
    }
  }

  private static Stream<Platform> mainstreamDesktopPlatforms() {
    return Stream.of(
      Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.WINDOWS, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64)
    );
  }

  @ParameterizedTest
  @EnumSource(value = Arch.class, names = { "X86", "ARM" })
  void shouldFailNamingFreeBsdWhereYtdlpHasNoBuild(final Arch architecture) {
    final Platform freeBsd = Platform.ofPlatform(Os.FREEBSD, architecture, Bits.BITS_64);
    try (final MockedStatic<Platform> platforms = Mockito.mockStatic(Platform.class, Mockito.CALLS_REAL_METHODS)) {
      platforms.when(Platform::getCurrentPlatform).thenReturn(freeBsd);
      final YtdlpInstaller installer = YtdlpInstaller.create(this.folder);
      final boolean supported = installer.isSupported();
      final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final String message = failure.getMessage();
      final String expected = "Platform %s has no yt-dlp download available!".formatted(freeBsd);
      assertFalse(supported);
      assertEquals(expected, message);
    }
  }

  @Test
  void shouldRejectANullFolder() {
    assertThrows(NullPointerException.class, () -> YtdlpInstaller.create(null));
  }
}

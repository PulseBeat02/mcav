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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests {@link YTDLPInstaller}.
 */
final class YTDLPInstallerTest {

  private static final String RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/";
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Platform CURRENT_PLATFORM = Platform.getCurrentPlatform();
  private static final String EXECUTABLE = "yt-dlp_test";
  private static final String ZIP_PATH = "/yt-dlp_test.zip";
  private static final byte[] LAUNCHER = "launcher".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LIBRARY = "library".getBytes(StandardCharsets.UTF_8);

  @TempDir
  private Path folder;

  @Test
  void pinsEveryBundledDownloadToRelease20260819() {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("yt-dlp.json");
    assertEquals(8, downloads.length);
    for (final Download download : downloads) {
      final String url = download.getUrl();
      final boolean pinned = url.startsWith(RELEASE_URL);
      final String hash = download.getHash();
      assertNotNull(hash, url);
      final Matcher hashMatcher = SHA_256.matcher(hash);
      final boolean sha256 = hashMatcher.matches();
      assertTrue(pinned, url);
      assertTrue(sha256, url);
    }
  }

  @Test
  void bundlesOneDownloadPerPlatform() {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("yt-dlp.json");
    final Set<Platform> platforms = new HashSet<>();
    for (final Download download : downloads) {
      final Platform platform = download.getPlatform();
      final boolean added = platforms.add(platform);
      assertTrue(added, "Only one download may be bundled for " + platform);
    }
  }

  @ParameterizedTest
  @CsvSource(
    {
      "WINDOWS, X86, BITS_64, yt-dlp.exe, 66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a",
      "WINDOWS, X86, BITS_32, yt-dlp_x86.exe, a8f91bd41452506bc81ebd2f369b186fea0ee7075413ba00cef9fd346a0a5d0c",
      "WINDOWS, ARM, BITS_64, yt-dlp_arm64.exe, 05b438997bafc3affdfda9d041353c9d73e04dc842207254b655b0887c4445b0",
      "LINUX, X86, BITS_64, yt-dlp_linux, 58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a",
      "LINUX, ARM, BITS_64, yt-dlp_linux_aarch64, b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc",
      "LINUX, ARM, BITS_32, yt-dlp_linux_armv7l.zip, bd51eb5fed7788008f4f30d281a182376a1297910b6c78e747403e757a546508",
      "MAC, ARM, BITS_64, yt-dlp_macos, 0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202",
      "MAC, X86, BITS_64, yt-dlp_macos, 0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202",
    }
  )
  void bundlesTheOfficialBuildOfEachPlatform(
    final OS operatingSystem,
    final Arch architecture,
    final Bits bits,
    final String file,
    final String hash
  ) {
    final Platform platform = Platform.ofPlatform(operatingSystem, architecture, bits);
    final Download download = findBundledDownload(platform);
    final String url = download.getUrl();
    final String downloadHash = download.getHash();
    final String expectedUrl = RELEASE_URL + file;
    assertEquals(expectedUrl, url);
    assertEquals(hash, downloadHash);
  }

  private static Download findBundledDownload(final Platform platform) {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("yt-dlp.json");
    for (final Download download : downloads) {
      final Platform downloadPlatform = download.getPlatform();
      final boolean matches = downloadPlatform.equals(platform);
      if (matches) {
        return download;
      }
    }
    throw new AssertionError("No yt-dlp download is bundled for " + platform);
  }

  @Test
  void treatsOnlyZipDownloadsAsArchives() {
    final YTDLPInstaller zipped = installerFor("https://example.com/files/yt-dlp_linux_armv7l.zip");
    final YTDLPInstaller upperCase = installerFor("https://example.com/files/YT-DLP.ZIP");
    final YTDLPInstaller standalone = installerFor("https://example.com/files/yt-dlp_linux");
    final YTDLPInstaller unsupported = new YTDLPInstaller(this.folder, new Download[0]);
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
  void findsTheExecutableOfAZipInTheUnpackedFolder() {
    final YTDLPInstaller zipped = installerFor("https://example.com/files/yt-dlp_linux_armv7l.zip");
    final YTDLPInstaller upperCase = installerFor("https://example.com/files/YT-DLP.ZIP");
    final YTDLPInstaller standalone = installerFor("https://example.com/files/yt-dlp_linux");
    final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
    final Path zippedExecutable = unpacked.resolve("yt-dlp_linux_armv7l");
    final Path upperCaseExecutable = unpacked.resolve("YT-DLP");
    final OS operatingSystem = OSUtils.getOS();
    final String standaloneName = operatingSystem == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    final Path standaloneExecutable = this.folder.resolve(standaloneName);
    final Path zippedPath = zipped.getPath();
    final Path upperCasePath = upperCase.getPath();
    final Path standalonePath = standalone.getPath();
    assertEquals(zippedExecutable, zippedPath);
    assertEquals(upperCaseExecutable, upperCasePath);
    assertEquals(standaloneExecutable, standalonePath);
  }

  @Test
  void unpacksAZipAndMarksItsExecutable() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER, "_internal/library.so", LIBRARY);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = installer.download(true);
      final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
      final Path expected = unpacked.resolve(EXECUTABLE);
      final Path library = unpacked.resolve("_internal/library.so");
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
    final Path zipFile = this.folder.resolve("yt-dlp_test.zip");
    final Path unpacking = this.folder.resolve("yt-dlp-unpacking");
    final boolean zipLeft = Files.exists(zipFile);
    final boolean unpackingLeft = Files.exists(unpacking);
    assertFalse(zipLeft, "the zip is deleted once it has been unpacked");
    assertFalse(unpackingLeft, "the temporary folder is removed");
  }

  private static void assertExecutableOnPosix(final Path file) {
    final OS operatingSystem = OSUtils.getOS();
    if (operatingSystem == OS.WINDOWS) {
      return;
    }
    final boolean executable = Files.isExecutable(file);
    assertTrue(executable, file.toString());
  }

  @Test
  void reusesAnUnpackedZipWithoutDownloadingItAgain() throws IOException {
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller first = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = first.download(true);
      final URI uri = server.uri(ZIP_PATH);
      final String url = uri.toString();
      final YTDLPInstaller second = this.installerFor(url);
      final Path reused = second.download(true);
      final int requests = server.getRequestCount(ZIP_PATH);
      assertEquals(installed, reused);
      assertEquals(1, requests);
    }
  }

  @Test
  void replacesAStaleUnpackedFolderAndLeftoversOfAFailedUnpacking() throws IOException {
    final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
    final Path unpacking = this.folder.resolve("yt-dlp-unpacking");
    final Path stale = unpacked.resolve("stale.txt");
    final Path leftover = unpacking.resolve("leftover.txt");
    Files.createDirectories(unpacked);
    Files.createDirectories(unpacking);
    Files.writeString(stale, "stale");
    Files.writeString(leftover, "leftover");
    final Map<String, byte[]> entries = Map.of(EXECUTABLE, LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final Path installed = installer.download(true);
      final boolean staleLeft = Files.exists(stale);
      final Path relocatedLeftover = unpacked.resolve("leftover.txt");
      final boolean leftoverCopied = Files.exists(relocatedLeftover);
      final boolean installedExists = Files.isRegularFile(installed);
      assertFalse(leftoverCopied, "stale extraction files must not be promoted into the new installation");
      assertFalse(staleLeft);
      assertTrue(installedExists);
      this.assertOnlyTheUnpackedFolderIsLeft();
    }
  }

  @Test
  void rejectsAZipWithoutTheExecutableAtItsTopLevel() throws IOException {
    final Map<String, byte[]> entries = Map.of("_internal/yt-dlp_test", LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final IOException exception = assertThrows(IOException.class, () -> installer.download(true));
      final String message = exception.getMessage();
      final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
      final Path unpacking = this.folder.resolve("yt-dlp-unpacking");
      final boolean unpackedLeft = Files.exists(unpacked);
      final boolean unpackingLeft = Files.exists(unpacking);
      assertEquals("The yt-dlp zip does not contain yt-dlp_test at its top level", message);
      assertFalse(unpackedLeft);
      assertFalse(unpackingLeft);
    }
  }

  @Test
  void rejectsADownloadThatIsNotAZip() throws IOException {
    final byte[] notAZip = "not a zip".getBytes(StandardCharsets.UTF_8);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, ZIP_PATH, notAZip);
      final IOException exception = assertThrows(IOException.class, () -> installer.download(true));
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      final boolean explained = message.startsWith("The yt-dlp zip cannot be extracted: ");
      assertTrue(explained, message);
      assertInstanceOf(UncheckedIOException.class, cause);
    }
  }

  @Test
  void rejectsAZipWhoseEntriesEscapeTheFolder() throws IOException {
    final Map<String, byte[]> entries = Map.of("../escaped", LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, ZIP_PATH, archive);
      final IOException exception = assertThrows(IOException.class, () -> installer.download(true));
      final Throwable cause = exception.getCause();
      final Path escaped = this.folder.resolve("escaped");
      final boolean escapedExists = Files.exists(escaped);
      assertInstanceOf(ZipEntryIntegrityException.class, cause);
      assertFalse(escapedExists);
    }
  }

  @Test
  void namesTheExecutableAfterTheProgramWhenTheZipHasNoName() throws IOException {
    final Map<String, byte[]> entries = Map.of("yt-dlp", LAUNCHER);
    final byte[] archive = zip(entries);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, "/.zip", archive);
      final Path installed = installer.download(true);
      final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
      final Path expected = unpacked.resolve("yt-dlp");
      assertEquals(expected, installed);
    }
  }

  @Test
  void installsAStandaloneExecutableAsDownloaded() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final YTDLPInstaller installer = this.serveZip(server, "/yt-dlp_linux", LAUNCHER);
      final Path installed = installer.download(true);
      final OS operatingSystem = OSUtils.getOS();
      final String executableName = operatingSystem == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
      final Path expected = this.folder.resolve(executableName);
      final byte[] content = Files.readAllBytes(installed);
      final Path unpacked = this.folder.resolve("yt-dlp-unpacked");
      final boolean unpackedExists = Files.exists(unpacked);
      assertEquals(expected, installed);
      assertArrayEquals(LAUNCHER, content);
      assertFalse(unpackedExists, "a standalone executable is never unpacked");
      assertExecutableOnPosix(installed);
    }
  }

  private YTDLPInstaller installerFor(final String url) {
    final Download download = new Download(CURRENT_PLATFORM, url);
    final Download[] downloads = { download };
    return new YTDLPInstaller(this.folder, downloads);
  }

  private YTDLPInstaller serveZip(final LocalHttpServer server, final String path, final byte[] body) {
    server.respond(path, 200, body);
    final URI uri = server.uri(path);
    final String url = uri.toString();
    final String hash = sha256(body);
    final Download download = new Download(CURRENT_PLATFORM, url, hash);
    final Download[] downloads = { download };
    return new YTDLPInstaller(this.folder, downloads);
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
    final HexFormat hex = HexFormat.of();
    return hex.formatHex(hash);
  }

  private static MessageDigest createSha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Every Java runtime supports SHA-256", exception);
    }
  }

  @ParameterizedTest
  @EnumSource(value = OS.class, names = { "WINDOWS", "MAC" })
  void letsArmMachinesRunTheEmulatedX86Build(final OS operatingSystem) {
    final YTDLPInstaller installer = YTDLPInstaller.create(this.folder);
    final Platform arm = Platform.ofPlatform(operatingSystem, Arch.ARM, Bits.BITS_64);
    final List<Platform> fallbacks = installer.getFallbackPlatforms(arm);
    final Platform x86 = Platform.ofPlatform(operatingSystem, Arch.X86, Bits.BITS_64);
    final List<Platform> expected = List.of(x86);
    assertEquals(expected, fallbacks);
  }

  @Test
  void offersNoFallbackWhereNoEmulationExists() {
    final YTDLPInstaller installer = YTDLPInstaller.create(this.folder);
    final Platform armLinux = Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform armWindows32 = Platform.ofPlatform(OS.WINDOWS, Arch.ARM, Bits.BITS_32);
    final Platform x86Windows = Platform.ofPlatform(OS.WINDOWS, Arch.X86, Bits.BITS_64);
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
  void installsASingleExecutableIntoTheChosenFolder() {
    final YTDLPInstaller installer = YTDLPInstaller.create(this.folder);
    final Path installerFolder = installer.getFolder();
    final String name = installer.getName();
    final boolean archive = installer.isArchive();
    final Path path = installer.getPath();
    final OS operatingSystem = OSUtils.getOS();
    final String executableName = operatingSystem == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    final Path expectedPath = this.folder.resolve(executableName);
    assertEquals(this.folder, installerFolder);
    assertEquals("yt-dlp", name);
    assertFalse(archive);
    assertEquals(expectedPath, path);
  }

  @Test
  void installsIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final YTDLPInstaller installer = YTDLPInstaller.create();
      final Path installerFolder = installer.getFolder();
      final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(".mcav");
      final Path expected = mcavFolder.resolve("cache");
      assertEquals(defaultFolder, installerFolder);
      assertEquals(expected, installerFolder);
    }
  }

  @Test
  void sharesOneInstallerOfTheCacheFolder() {
    final YTDLPInstaller first = YTDLPInstaller.shared();
    final YTDLPInstaller second = YTDLPInstaller.shared();
    final Path sharedFolder = first.getFolder();
    final Path folderName = sharedFolder.getFileName();
    final String name = folderName.toString();
    assertSame(first, second, "every user of yt-dlp goes through one installer, so it is downloaded once");
    assertEquals("cache", name);
  }

  @Test
  void supportsEveryMainstreamDesktopPlatform() {
    final Platform current = Platform.getCurrentPlatform();
    final Arch architecture = current.getArch();
    final Bits bits = current.getBits();
    Assumptions.assumeTrue(architecture == Arch.X86 && bits == Bits.BITS_64, "The bundled list is only checked on 64-bit x86");
    final YTDLPInstaller installer = YTDLPInstaller.create(this.folder);
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final boolean secure = url.startsWith("https://");
    final boolean namesYtDlp = url.contains("yt-dlp");
    assertTrue(supported);
    assertTrue(secure, url);
    assertTrue(namesYtDlp, url);
  }

  @Test
  void rejectsANullFolder() {
    assertThrows(NullPointerException.class, () -> YTDLPInstaller.create(null));
  }
}

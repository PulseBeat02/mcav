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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.LinuxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.OsxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.WindowsInstallationStrategy;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Stubber;

final class VlcInstallerTest {

  private static final String ZIP_NAME = "vlc-3.0.24-win64.zip";
  private static final String ZIP_PATH = "/%s".formatted(ZIP_NAME);
  private static final String ZIP_EXTENSION = ".zip";
  private static final String DISK_IMAGE_EXTENSION = ".dmg";
  private static final String[] ZIP_ENTRIES = { "vlc-3.0.24/libvlc.dll", "vlc-3.0.24/libvlccore.dll", "vlc-3.0.24/plugins/x.dll" };

  private static final String VLC_DIRECTORY = "vlc";
  private static final String LEFTOVER_DIRECTORY = "half-extracted";
  private static final String SOURCE_ZIP_NAME = "vlc-source.zip";
  private static final String APP_IMAGE_FILE = "fixture.AppImage";
  private static final String WINDOWS_EXTRACTION_FIXTURE_FILE = "fixture.cmd";

  private static final String MCAV_DIRECTORY = ".mcav";
  private static final String CACHE_DIRECTORY = "cache";

  private static final String JUNEST_DIRECTORY = "vlc-junest";
  private static final String APP_DIRECTORY = "VLC.app";
  private static final String UNRELATED_DIRECTORY = "vlc-other";

  private static final String OUTSIDE_DIRECTORY = "outside";
  private static final String KEPT_FILE = "kept.txt";
  private static final String INNER_LINK_NAME = "link-to-outside";

  private static final String NESTED_DIRECTORY = "nested";
  private static final String TREE_FILE = "file.txt";
  private static final String NESTED_FILE = "nested.txt";
  private static final String FILE_CONTENT = "content";

  private static final String CONTENTS_DIRECTORY = "Contents";
  private static final String MAC_OS_DIRECTORY = "MacOS";
  private static final String USR_DIRECTORY = "usr";
  private static final String LIBRARY_DIRECTORY = "lib";
  private static final String LINUX_LIBRARY_PATH = "usr/lib";

  private static final String WINDOWS_LIBRARY = "libvlc.dll";
  private static final String WINDOWS_CORE_LIBRARY = "libvlccore.dll";
  private static final String MAC_LIBRARY = "libvlc.dylib";
  private static final String MAC_CORE_LIBRARY = "libvlccore.dylib";
  private static final String LINUX_LIBRARY = "libvlc.so.5";
  private static final String LINUX_CORE_LIBRARY = "libvlccore.so.9";

  private static final String VLC_DOWNLOADS_RESOURCE = "mcav/common/installers/vlc.json";

  private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

  @TempDir
  private Path folder;

  private static final String PINNED_APPIMAGES = "https://github.com/ivan-hc/VLC-appimage/releases/download/";

  private static Download[] noDownloadExpected() {
    final String message = "The downloads must not be resolved!";
    throw new AssertionError(message);
  }

  private static Download[] noDownloads() {
    return new Download[0];
  }

  @Test
  void shouldExtractIntoTheVlcDirectoryOfTheChosenFolder() {
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final Path installDirectory = installer.getInstallDirectory();
    final Path path = installer.getPath();
    final String name = installer.getName();
    final boolean isArchive = installer.isArchive();
    final Path expected = this.folder.resolve(VLC_DIRECTORY);
    assertEquals(expected, installDirectory);
    assertEquals(expected, path);
    assertEquals("vlc", name);
    assertTrue(isArchive);
  }

  @Test
  void shouldInstallIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final VlcInstaller installer = VlcInstaller.create();
      final Path installerFolder = installer.getFolder();
      final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(MCAV_DIRECTORY);
      final Path expected = mcavFolder.resolve(CACHE_DIRECTORY);
      assertEquals(defaultFolder, installerFolder);
      assertEquals(expected, installerFolder);
    }
  }

  @Test
  void shouldResolveItsBundledDownloadsWhenNoneWereSupplied() {
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final Platform platform = Platform.getCurrentPlatform();
    final Os operatingSystem = platform.getOs();
    final Optional<String> optionalUrl = installer.getUrl();

    final String expectedPrefix = switch (operatingSystem) {
      case WINDOWS, MAC -> "https://download.videolan.org/pub/videolan/vlc/";
      case LINUX -> "https://github.com/ivan-hc/VLC-appimage/";
      case FREEBSD, OTHER -> "";
    };
    if (expectedPrefix.isEmpty()) {
      assertEquals(Optional.empty(), optionalUrl);
    } else {
      final String url = optionalUrl.orElseThrow();
      final boolean expectedHost = url.startsWith(expectedPrefix);
      assertTrue(expectedHost, url);
    }

    for (final Os unsupported : new Os[] { Os.FREEBSD, Os.OTHER }) {
      final Platform unavailable = Platform.ofPlatform(unsupported, Arch.X86, Bits.BITS_64);
      try (final MockedStatic<Platform> platforms = Mockito.mockStatic(Platform.class, Mockito.CALLS_REAL_METHODS)) {
        platforms.when(Platform::getCurrentPlatform).thenReturn(unavailable);
        final VlcInstaller missing = VlcInstaller.create(this.folder);
        final Optional<String> missingUrl = missing.getUrl();
        assertEquals(Optional.empty(), missingUrl, "an unsupported platform has no download URL");

        final boolean supported = missing.isSupported();
        assertFalse(supported);
      }
    }
  }

  @Test
  void shouldRejectANullFolder() {
    assertThrows(NullPointerException.class, () -> VlcInstaller.create(null));
  }

  @Test
  void shouldUseTheInstallationStrategyOfEachOperatingSystem() {
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final InstallationStrategy windowsStrategy = installer.createStrategy(Os.WINDOWS);
    final InstallationStrategy macStrategy = installer.createStrategy(Os.MAC);
    final InstallationStrategy linuxStrategy = installer.createStrategy(Os.LINUX);
    assertInstanceOf(WindowsInstallationStrategy.class, windowsStrategy);
    assertInstanceOf(OsxInstallationStrategy.class, macStrategy);
    assertInstanceOf(LinuxInstallationStrategy.class, linuxStrategy);
    assertThrows(UnsupportedOperatingSystemException.class, () -> installer.createStrategy(Os.FREEBSD));
    assertThrows(UnsupportedOperatingSystemException.class, () -> installer.createStrategy(Os.OTHER));
  }

  @Test
  void shouldKnowWhichOperatingSystemsItCanInstallOn() {
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final boolean installsOnMac = installer.hasStrategy(Os.MAC);
    final boolean installsOnWindows = installer.hasStrategy(Os.WINDOWS);
    final boolean installsOnLinux = installer.hasStrategy(Os.LINUX);
    final boolean freeBsd = installer.hasStrategy(Os.FREEBSD);
    final boolean installsOnOther = installer.hasStrategy(Os.OTHER);
    assertTrue(installsOnMac);
    assertTrue(installsOnWindows);
    assertTrue(installsOnLinux);
    assertFalse(freeBsd);
    assertFalse(installsOnOther);
  }

  @Test
  void shouldFindNoInstallationOnSystemsItCannotInstallOn() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final Optional<Path> freeBsd = installer.findExistingInstallation(Os.FREEBSD);
    final Optional<Path> other = installer.findExistingInstallation(Os.OTHER);
    final boolean noFreeBsdInstallation = freeBsd.isEmpty();
    final boolean noOtherInstallation = other.isEmpty();
    assertTrue(noFreeBsdInstallation);
    assertTrue(noOtherInstallation);
  }

  @Test
  void shouldBeSupportedByAnEarlierInstallationWithoutResolvingDownloads() throws IOException {
    this.createInstalledLibrary();
    final VlcInstaller installer = new VlcInstaller(this.folder, VlcInstallerTest::noDownloadExpected);
    final boolean supported = installer.isSupported();
    assertTrue(supported);
  }

  @Test
  void shouldNotBeSupportedWithoutAnInstallationOrADownload() {
    final VlcInstaller installer = new VlcInstaller(this.folder, VlcInstallerTest::noDownloads);
    final boolean supported = installer.isSupported();
    assertFalse(supported);
  }

  @Test
  void shouldBeSupportedWithoutAnInstallationWhenThisPlatformHasADownload() {
    final Platform current = Platform.getCurrentPlatform();
    final Download download = new Download(current, "https://example.invalid/vlc.zip");
    final VlcInstaller installer = new VlcInstaller(this.folder, () -> new Download[] { download });
    final boolean supported = installer.isSupported();
    assertTrue(supported);
  }

  @Test
  void shouldTreatAnInstallationThatCannotBeSearchedAsMissing() throws IOException {
    final VlcInstaller real = new VlcInstaller(this.folder, VlcInstallerTest::noDownloads);
    final VlcInstaller installer = Mockito.spy(real);
    final InstallationException failure = new InstallationException("permission denied!");
    final Stubber stubber = Mockito.doThrow(failure);
    final VlcInstaller stubbed = stubber.when(installer);
    stubbed.findExistingInstallation();
    final boolean supported = installer.isSupported();
    assertFalse(supported);
  }

  @Test
  void shouldDeleteTheInstallationsOfEarlierVersionsOnce() throws IOException {
    final Path junest = createTree(this.folder, JUNEST_DIRECTORY);
    final Path app = createTree(this.folder, APP_DIRECTORY);
    final Path unrelated = createTree(this.folder, UNRELATED_DIRECTORY);
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final Optional<Path> found = installer.findInstallation();
    final boolean nothingFound = found.isEmpty();
    final boolean junestExists = Files.exists(junest);
    final boolean appExists = Files.exists(app);
    final boolean unrelatedExists = Files.exists(unrelated);
    assertTrue(nothingFound);
    assertFalse(junestExists);
    assertFalse(appExists);
    assertTrue(unrelatedExists, "only the exact directories of earlier versions are deleted");

    Files.createDirectories(junest);
    installer.findInstallation();
    final boolean recreatedExists = Files.isDirectory(junest);
    assertTrue(recreatedExists, "the cleanup runs once per installer");
  }

  @Test
  void shouldLeaveFilesNamedLikeOldInstallationsAlone() throws IOException {
    final Path file = this.folder.resolve(JUNEST_DIRECTORY);
    Files.writeString(file, "not a directory");
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    installer.removeLegacyInstallations(this.folder);
    final String content = Files.readString(file);
    assertEquals("not a directory", content);
  }

  @Test
  void shouldNeverFollowLinksWhileDeletingOldInstallations() throws IOException {
    final Path outside = createTree(this.folder, OUTSIDE_DIRECTORY);
    final Path kept = outside.resolve(KEPT_FILE);
    Files.writeString(kept, FILE_CONTENT);
    final Path appLink = this.folder.resolve(APP_DIRECTORY);
    final Path junest = createTree(this.folder, JUNEST_DIRECTORY);
    final Path innerLink = junest.resolve(INNER_LINK_NAME);
    final boolean linksCreated = createSymbolicLink(appLink, outside) && createSymbolicLink(innerLink, outside);
    Assumptions.assumeTrue(linksCreated, "Symbolic links cannot be created here!");

    final VlcInstaller installer = VlcInstaller.create(this.folder);
    installer.removeLegacyInstallations(this.folder);
    final boolean keptExists = Files.exists(kept);
    final boolean appLinkExists = Files.exists(appLink, LinkOption.NOFOLLOW_LINKS);
    final boolean junestExists = Files.exists(junest, LinkOption.NOFOLLOW_LINKS);
    assertTrue(keptExists, "the target of a link is never deleted");
    assertTrue(appLinkExists, "a link named like an old installation is not an old installation");
    assertFalse(junestExists);
  }

  @Test
  void shouldKeepGoingWhenAnOldInstallationCannotBeDeleted() throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    Assumptions.assumeTrue(operatingSystem == Os.WINDOWS, "Only Windows refuses to delete a file that is open!");
    final Path app = createTree(this.folder, APP_DIRECTORY);
    final Path locked = app.resolve(TREE_FILE);
    final File lockedFile = locked.toFile();
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    try (final RandomAccessFile open = new RandomAccessFile(lockedFile, "r")) {
      installer.removeLegacyInstallations(this.folder);
      final long length = open.length();
      final boolean lockedExists = Files.exists(locked);
      final boolean hasContent = length > 0;
      assertTrue(hasContent);
      assertTrue(lockedExists, "a file that cannot be deleted is left behind without failing");
    }
  }

  @Test
  void shouldDeleteTheOtherOldInstallationWhenOneCannotBeDeleted() throws IOException {
    final Path junest = createTree(this.folder, JUNEST_DIRECTORY);
    final Path app = createTree(this.folder, APP_DIRECTORY);
    final FileOperationException refused = new FileOperationException("The old installation is in use!");
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    try (final MockedStatic<IoUtils> ioUtils = Mockito.mockStatic(IoUtils.class)) {
      ioUtils.when(() -> IoUtils.deleteRecursively(junest)).thenThrow(refused);
      ioUtils.when(() -> IoUtils.deleteRecursively(app)).thenCallRealMethod();
      installer.removeLegacyInstallations(this.folder);
    }

    final boolean junestExists = Files.isDirectory(junest);
    final boolean appExists = Files.exists(app);
    assertTrue(junestExists, "a directory that cannot be deleted is left behind without failing");
    assertFalse(appExists);
  }

  @Test
  void shouldReuseAnEarlierInstallationWithoutResolvingDownloads() throws IOException {
    final Path libraryDirectory = this.createInstalledLibrary();
    final VlcInstaller installer = new VlcInstaller(this.folder, VlcInstallerTest::noDownloadExpected);
    final Path installed = installer.download(FileMode.EXECUTABLE);
    final Path path = installer.getPath();
    assertEquals(libraryDirectory, installed);
    assertEquals(libraryDirectory, path);
  }

  @Test
  void shouldDownloadAndExtractTheWindowsZip() throws IOException {
    final Path vlcDirectory = this.folder.resolve(VLC_DIRECTORY);
    final Path leftover = vlcDirectory.resolve(LEFTOVER_DIRECTORY);
    Files.createDirectories(leftover);
    final byte[] zip = this.createVlcZip();

    try (
      final LocalHttpServer server = LocalHttpServer.start();
      final MockedStatic<OsUtils> operatingSystems = Mockito.mockStatic(OsUtils.class, Mockito.CALLS_REAL_METHODS)
    ) {
      operatingSystems.when(OsUtils::getOs).thenReturn(Os.WINDOWS);
      server.respond(ZIP_PATH, 200, zip);
      final VlcInstaller installer = this.installerDownloadingFrom(server);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final Path installDirectory = installer.getInstallDirectory();
      assertEquals(installDirectory, installed);

      this.assertInstalledWithoutLeftovers(installed, leftover);
    }
  }

  @Test
  void shouldInstallALinuxArchiveThroughItsExecutableExtractionProtocol() throws IOException {
    final Path archive = this.writeExtractionFixture();
    try (final MockedStatic<OsUtils> operatingSystems = Mockito.mockStatic(OsUtils.class, Mockito.CALLS_REAL_METHODS)) {
      operatingSystems.when(OsUtils::getOs).thenReturn(Os.LINUX);
      final VlcInstaller installer = VlcInstaller.create(this.folder);
      final Path installed = installer.install(archive);
      final Path installDirectory = installer.getInstallDirectory();
      final Path expected = installDirectory.resolve(LINUX_LIBRARY_PATH);
      final Path core = installed.resolve(LINUX_CORE_LIBRARY);
      final String content = Files.readString(core);
      final boolean archiveRemains = Files.exists(archive);
      assertEquals(expected, installed);
      assertEquals("fixture-core", content);
      assertFalse(archiveRemains);
    }
  }

  // Models the AppImage extraction protocol, not the VLC binary: the returned library must never be loaded. The host
  // runs the fixture, so it is a batch file on Windows and a shell script everywhere else.
  private Path writeExtractionFixture() throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    if (operatingSystem == Os.WINDOWS) {
      final Path archive = this.folder.resolve(WINDOWS_EXTRACTION_FIXTURE_FILE);
      final String script = """
      @echo off
      if not "%~1"=="--appimage-extract" exit /b 1
      mkdir squashfs-root\\usr\\lib\\vlc\\plugins
      <nul set /p =fixture-core> squashfs-root\\usr\\lib\\libvlccore.so.9
      <nul set /p =fixture-api> squashfs-root\\usr\\lib\\libvlc.so.5
      <nul set /p =fixture-plugin> squashfs-root\\usr\\lib\\vlc\\plugins\\test.so
      exit /b 0
      """;
      Files.writeString(archive, script);
      return archive;
    }

    final Path archive = this.folder.resolve(APP_IMAGE_FILE);
    final String script = """
    #!/bin/sh
    set -eu
    test "$1" = --appimage-extract
    mkdir -p squashfs-root/usr/lib/vlc/plugins
    printf fixture-core > squashfs-root/usr/lib/libvlccore.so.9
    printf fixture-api > squashfs-root/usr/lib/libvlc.so.5
    printf fixture-plugin > squashfs-root/usr/lib/vlc/plugins/test.so
    """;
    Files.writeString(archive, script);
    return archive;
  }

  @Test
  void shouldKeepGoingWhenPosixPermissionsPreventLegacyDeletion() throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    Assumptions.assumeTrue(operatingSystem == Os.LINUX, "This fixture exercises POSIX directory permissions!");
    final Path legacy = createTree(this.folder, APP_DIRECTORY);
    final Set<PosixFilePermission> original = Files.getPosixFilePermissions(legacy);
    final Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    try {
      Files.setPosixFilePermissions(legacy, readOnly);
      final boolean writable = Files.isWritable(legacy);
      Assumptions.assumeFalse(writable, "A privileged process can bypass the fixture's directory permissions!");
      installer.removeLegacyInstallations(this.folder);
      final boolean legacyRemains = Files.isDirectory(legacy);
      assertTrue(legacyRemains, "failed cleanup is reported and leaves the undeletable directory in place");
    } finally {
      Files.setPosixFilePermissions(legacy, original);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bundledDesktopPlatforms")
  void shouldOfferTheBundledDownloadOfEveryWindowsAndMacPlatform(final Platform platform) {
    try (final MockedStatic<Platform> platforms = Mockito.mockStatic(Platform.class, Mockito.CALLS_REAL_METHODS)) {
      platforms.when(Platform::getCurrentPlatform).thenReturn(platform);
      final VlcInstaller installer = VlcInstaller.create(this.folder);
      final boolean supported = installer.isSupported();
      final Optional<String> optionalUrl = installer.getUrl();
      final String url = optionalUrl.orElseThrow();
      final Optional<String> hash = installer.getHash();
      final Os operatingSystem = platform.getOs();
      final String extension = operatingSystem == Os.WINDOWS ? ZIP_EXTENSION : DISK_IMAGE_EXTENSION;
      final boolean expectedExtension = url.endsWith(extension);
      assertTrue(supported);
      assertTrue(expectedExtension, url);
      assertSha256(hash, url);
    }
  }

  private static Stream<Platform> bundledDesktopPlatforms() {
    return Stream.of(
      Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_32),
      Platform.ofPlatform(Os.WINDOWS, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64)
    );
  }

  @Test
  void shouldBundleVerifiedDownloadsOfTheAdvertisedVersion() {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(VLC_DOWNLOADS_RESOURCE);
    final String versionDirectory = "/%s/".formatted(VlcInstaller.VERSION);
    final boolean anyDownloads = downloads.length > 0;
    assertTrue(anyDownloads);

    for (final Download download : downloads) {
      final String url = download.getUrl();
      final Optional<String> hash = download.getHash();
      final boolean linux = download.getPlatform().getOs() == Os.LINUX;
      final boolean advertisedVersion = linux ? url.startsWith(PINNED_APPIMAGES) : url.contains(versionDirectory);
      final boolean secure = url.startsWith("https://");
      assertTrue(advertisedVersion, url);
      assertTrue(secure, url);
      assertSha256(hash, url);
    }
  }

  private static void assertSha256(final Optional<String> optionalHash, final String url) {
    final boolean hashFound = optionalHash.isPresent();
    assertTrue(hashFound, url);

    final String hash = optionalHash.orElseThrow();
    final int length = hash.length();
    assertEquals(64, length, url);

    final boolean hexadecimal = SHA256_PATTERN.matcher(hash).matches();
    assertTrue(hexadecimal, "a SHA-256 digest must be hexadecimal: %s".formatted(url));
  }

  private VlcInstaller installerDownloadingFrom(final LocalHttpServer server) {
    final URI uri = server.uri(ZIP_PATH);
    final String url = uri.toString();
    final Platform current = Platform.getCurrentPlatform();
    final Download download = new Download(current, url);
    return new VlcInstaller(this.folder, () -> new Download[] { download });
  }

  private void assertInstalledWithoutLeftovers(final Path installed, final Path leftover) {
    final Path library = installed.resolve(WINDOWS_LIBRARY);
    final Path archive = this.folder.resolve(ZIP_NAME);
    final boolean hasLibrary = Files.isRegularFile(library);
    final boolean leftoverExists = Files.exists(leftover);
    final boolean archiveExists = Files.exists(archive);
    assertTrue(hasLibrary);
    assertFalse(leftoverExists);
    assertFalse(archiveExists);
  }

  private static Path createTree(final Path parent, final String name) throws IOException {
    final Path root = parent.resolve(name);
    final Path nested = root.resolve(NESTED_DIRECTORY);
    Files.createDirectories(nested);
    final Path file = root.resolve(TREE_FILE);
    Files.writeString(file, FILE_CONTENT);
    final Path nestedFile = nested.resolve(NESTED_FILE);
    Files.writeString(nestedFile, FILE_CONTENT);
    return root;
  }

  private static boolean createSymbolicLink(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);

      return true;
    } catch (final IOException | UnsupportedOperationException exception) {
      return false;
    }
  }

  private Path createInstalledLibrary() throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    final VlcInstaller installer = VlcInstaller.create(this.folder);
    final boolean installable = installer.hasStrategy(operatingSystem);
    final String message = "Operating system %s does not support installing VLC automatically!".formatted(operatingSystem);
    Assumptions.assumeTrue(installable, message);
    final Path installDirectory = this.folder.resolve(VLC_DIRECTORY);
    final Path relativeLibraryDirectory = relativeLibraryDirectory(operatingSystem);
    final Path libraryDirectory = installDirectory.resolve(relativeLibraryDirectory);
    final String libraryName = libraryName(operatingSystem);
    final Path library = libraryDirectory.resolve(libraryName);
    Files.createDirectories(libraryDirectory);
    Files.writeString(library, "structural library fixture, never loaded");
    final String counterpart = switch (operatingSystem) {
      case WINDOWS -> WINDOWS_CORE_LIBRARY;
      case MAC -> MAC_CORE_LIBRARY;
      case LINUX, FREEBSD, OTHER -> LINUX_LIBRARY;
    };
    final Path companion = libraryDirectory.resolve(counterpart);
    Files.writeString(companion, "structural companion fixture, never loaded");
    return libraryDirectory;
  }

  private static Path relativeLibraryDirectory(final Os operatingSystem) {
    return switch (operatingSystem) {
      case WINDOWS -> Path.of("");
      case MAC -> Path.of(APP_DIRECTORY, CONTENTS_DIRECTORY, MAC_OS_DIRECTORY, LIBRARY_DIRECTORY);
      case LINUX, FREEBSD, OTHER -> Path.of(USR_DIRECTORY, LIBRARY_DIRECTORY);
    };
  }

  private static String libraryName(final Os operatingSystem) {
    return switch (operatingSystem) {
      case WINDOWS -> WINDOWS_LIBRARY;
      case MAC -> MAC_LIBRARY;
      case LINUX, FREEBSD, OTHER -> LINUX_CORE_LIBRARY;
    };
  }

  private byte[] createVlcZip() throws IOException {
    final Path zip = this.folder.resolve(SOURCE_ZIP_NAME);
    try {
      writeZip(zip);
      return Files.readAllBytes(zip);
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  private static void writeZip(final Path zip) throws IOException {
    try (final OutputStream file = Files.newOutputStream(zip); final ZipOutputStream zipOutput = new ZipOutputStream(file)) {
      for (final String entry : ZIP_ENTRIES) {
        final ZipEntry zipEntry = new ZipEntry(entry);
        zipOutput.putNextEntry(zipEntry);
        final byte[] content = entry.getBytes(StandardCharsets.UTF_8);
        zipOutput.write(content);
        zipOutput.closeEntry();
      }
    }
  }
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.LinuxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.OSXInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.WinInstallationStrategy;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.stubbing.Stubber;

/**
 * Tests {@link VLCInstaller}.
 */
final class VLCInstallerTest {

  private static final String ZIP_NAME = "vlc-3.0.23-win64.zip";
  private static final String ZIP_PATH = "/" + ZIP_NAME;
  private static final String[] ZIP_ENTRIES = { "vlc-3.0.23/libvlc.dll", "vlc-3.0.23/libvlccore.dll", "vlc-3.0.23/plugins/x.dll" };

  @TempDir
  private Path folder;

  private static Download[] noDownloadExpected() {
    throw new AssertionError("The downloads must not be resolved");
  }

  private static Download[] noDownloads() {
    return new Download[0];
  }

  @Test
  void extractsIntoTheVlcDirectoryOfTheChosenFolder() {
    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final Path installDirectory = installer.getInstallDirectory();
    final Path path = installer.getPath();
    final String name = installer.getName();
    final boolean archive = installer.isArchive();
    final Path expected = this.folder.resolve("vlc");
    assertEquals(expected, installDirectory);
    assertEquals(expected, path);
    assertEquals("vlc", name);
    assertTrue(archive);
  }

  @Test
  void installsIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final VLCInstaller installer = VLCInstaller.create();
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
  void resolvesItsBundledDownloadsWhenNoneWereSupplied() {
    // the default constructor's supplier is a lambda, and JaCoCo counts its body against the line that declares
    // it, so a test that never asks for a download leaves that body untested while the line looks covered
    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final Platform platform = Platform.getCurrentPlatform();
    final OS operatingSystem = platform.getOS();
    final String url = installer.getUrl();

    final String expectedPrefix =
      switch (operatingSystem) {
        // Windows and macOS take the zip and the disk image VideoLAN publishes
        case WINDOWS, MAC -> "https://get.videolan.org/vlc/";
        // Linux takes an AppImage, because it installs without root
        case LINUX -> "https://github.com/ivan-hc/VLC-appimage/";
        // no VLC is published for the rest, and an unresolved download answers with an empty URL
        default -> "";
      };
    assertTrue(url.startsWith(expectedPrefix), url);
  }

  @Test
  void rejectsANullFolder() {
    assertThrows(NullPointerException.class, () -> VLCInstaller.create(null));
  }

  @Test
  void usesTheInstallationStrategyOfEachOperatingSystem() {
    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final InstallationStrategy windows = VLCInstaller.createStrategy(installer, OS.WINDOWS);
    final InstallationStrategy mac = VLCInstaller.createStrategy(installer, OS.MAC);
    final InstallationStrategy linux = VLCInstaller.createStrategy(installer, OS.LINUX);
    assertInstanceOf(WinInstallationStrategy.class, windows);
    assertInstanceOf(OSXInstallationStrategy.class, mac);
    assertInstanceOf(LinuxInstallationStrategy.class, linux);
    assertThrows(UnsupportedOperatingSystemException.class, () -> VLCInstaller.createStrategy(installer, OS.FREEBSD));
    assertThrows(UnsupportedOperatingSystemException.class, () -> VLCInstaller.createStrategy(installer, OS.OTHER));
  }

  @Test
  void knowsWhichOperatingSystemsItCanInstallOn() {
    final boolean mac = VLCInstaller.hasStrategy(OS.MAC);
    final boolean windows = VLCInstaller.hasStrategy(OS.WINDOWS);
    final boolean linux = VLCInstaller.hasStrategy(OS.LINUX);
    final boolean freeBsd = VLCInstaller.hasStrategy(OS.FREEBSD);
    final boolean other = VLCInstaller.hasStrategy(OS.OTHER);
    assertTrue(mac);
    assertTrue(windows);
    assertTrue(linux);
    assertFalse(freeBsd);
    assertFalse(other);
  }

  @Test
  void findsNoInstallationOnSystemsItCannotInstallOn() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final Optional<Path> freeBsd = installer.findExistingInstallation(OS.FREEBSD);
    final Optional<Path> other = installer.findExistingInstallation(OS.OTHER);
    final boolean noFreeBsdInstallation = freeBsd.isEmpty();
    final boolean noOtherInstallation = other.isEmpty();
    assertTrue(noFreeBsdInstallation);
    assertTrue(noOtherInstallation);
  }

  @Test
  void isSupportedByAnEarlierInstallationWithoutResolvingDownloads() throws IOException {
    // resolving the Linux downloads asks GitHub, which must not decide whether an installed VLC can be used
    this.createInstalledLibrary();
    final VLCInstaller installer = new VLCInstaller(this.folder, VLCInstallerTest::noDownloadExpected);
    final boolean supported = installer.isSupported();
    assertTrue(supported);
  }

  @Test
  void isNotSupportedWithoutAnInstallationOrADownload() {
    final VLCInstaller installer = new VLCInstaller(this.folder, VLCInstallerTest::noDownloads);
    final boolean supported = installer.isSupported();
    assertFalse(supported);
  }

  @Test
  void treatsAnInstallationThatCannotBeSearchedAsMissing() throws IOException {
    final VLCInstaller real = new VLCInstaller(this.folder, VLCInstallerTest::noDownloads);
    final VLCInstaller installer = Mockito.spy(real);
    final IOException failure = new IOException("permission denied");
    final Stubber stubber = Mockito.doThrow(failure);
    final VLCInstaller stubbed = stubber.when(installer);
    stubbed.findExistingInstallation();
    final boolean supported = installer.isSupported();
    assertFalse(supported);
  }

  @Test
  void deletesTheInstallationsOfEarlierVersionsOnce() throws IOException {
    final Path junest = createTree(this.folder, "vlc-junest");
    final Path app = createTree(this.folder, "VLC.app");
    final Path unrelated = createTree(this.folder, "vlc-other");
    final VLCInstaller installer = VLCInstaller.create(this.folder);
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
  void leavesFilesNamedLikeOldInstallationsAlone() throws IOException {
    final Path file = this.folder.resolve("vlc-junest");
    Files.writeString(file, "not a directory");
    VLCInstaller.removeLegacyInstallations(this.folder);
    final String content = Files.readString(file);
    assertEquals("not a directory", content);
  }

  @Test
  void neverFollowsLinksWhileDeletingOldInstallations() throws IOException {
    final Path outside = createTree(this.folder, "outside");
    final Path kept = outside.resolve("kept.txt");
    // the link target must exist, or the check below could never fail
    Files.writeString(kept, "content");
    final Path appLink = this.folder.resolve("VLC.app");
    final Path junest = createTree(this.folder, "vlc-junest");
    final Path innerLink = junest.resolve("link-to-outside");
    final boolean linksCreated = tryCreateLink(appLink, outside) && tryCreateLink(innerLink, outside);
    Assumptions.assumeTrue(linksCreated, "Symbolic links cannot be created here");

    VLCInstaller.removeLegacyInstallations(this.folder);
    final boolean keptExists = Files.exists(kept);
    final boolean appLinkExists = Files.exists(appLink, LinkOption.NOFOLLOW_LINKS);
    final boolean junestExists = Files.exists(junest, LinkOption.NOFOLLOW_LINKS);
    assertTrue(keptExists, "the target of a link is never deleted");
    assertTrue(appLinkExists, "a link named like an old installation is not an old installation");
    assertFalse(junestExists);
  }

  @Test
  void keepsGoingWhenAnOldInstallationCannotBeDeleted() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    Assumptions.assumeTrue(operatingSystem == OS.WINDOWS, "Only Windows refuses to delete a file that is open");
    final Path app = createTree(this.folder, "VLC.app");
    final Path locked = app.resolve("file.txt");
    final File lockedFile = locked.toFile();
    try (final RandomAccessFile open = new RandomAccessFile(lockedFile, "r")) {
      VLCInstaller.removeLegacyInstallations(this.folder);
      final long length = open.length();
      final boolean lockedExists = Files.exists(locked);
      assertTrue(length > 0);
      assertTrue(lockedExists, "a file that cannot be deleted is left behind without failing");
    }
  }

  @Test
  void reusesAnEarlierInstallationWithoutResolvingDownloads() throws IOException {
    final Path libraryDirectory = this.createInstalledLibrary();
    final VLCInstaller installer = new VLCInstaller(this.folder, VLCInstallerTest::noDownloadExpected);
    final Path installed = installer.download(true);
    final Path path = installer.getPath();
    assertEquals(libraryDirectory, installed);
    assertEquals(libraryDirectory, path);
  }

  @Test
  void downloadsAndExtractsTheWindowsZip() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    Assumptions.assumeTrue(operatingSystem == OS.WINDOWS, "Only the Windows installation can run without external tools");
    final Path vlcDirectory = this.folder.resolve("vlc");
    final Path leftover = vlcDirectory.resolve("half-extracted");
    Files.createDirectories(leftover);
    final byte[] zip = this.createVlcZip();

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(ZIP_PATH, 200, zip);
      final VLCInstaller installer = this.installerDownloadingFrom(server);
      final Path installed = installer.download(true);
      final Path installDirectory = installer.getInstallDirectory();
      assertEquals(installDirectory, installed);
      this.assertInstalledWithoutLeftovers(installed, leftover);
    }
  }

  @Test
  void installsALinuxArchiveThroughItsExecutableExtractionProtocol() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    Assumptions.assumeTrue(operatingSystem == OS.LINUX, "The executable fixture uses a POSIX shell");
    final Path archive = this.folder.resolve("fixture.AppImage");
    // Model the AppImage extraction protocol, not the VLC binary: the returned library must never be loaded.
    final String script =
      """
      #!/bin/sh
      set -eu
      test "$1" = --appimage-extract
      mkdir -p squashfs-root/usr/lib/vlc/plugins
      printf fixture-core > squashfs-root/usr/lib/libvlccore.so.9
      printf fixture-api > squashfs-root/usr/lib/libvlc.so.5
      printf fixture-plugin > squashfs-root/usr/lib/vlc/plugins/test.so
      """;
    Files.writeString(archive, script);
    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final Path installed = installer.install(archive);
    final Path installDirectory = installer.getInstallDirectory();
    final Path expected = installDirectory.resolve("usr/lib");
    final Path core = installed.resolve("libvlccore.so.9");
    final String content = Files.readString(core);
    final boolean archiveRemains = Files.exists(archive);
    assertEquals(expected, installed);
    assertEquals("fixture-core", content);
    assertFalse(archiveRemains);
  }

  @Test
  void keepsGoingWhenPosixPermissionsPreventLegacyDeletion() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    Assumptions.assumeTrue(operatingSystem == OS.LINUX, "This fixture exercises POSIX directory permissions");
    final Path legacy = createTree(this.folder, "VLC.app");
    final Set<PosixFilePermission> original = Files.getPosixFilePermissions(legacy);
    final Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    try {
      Files.setPosixFilePermissions(legacy, readOnly);
      final boolean writable = Files.isWritable(legacy);
      Assumptions.assumeFalse(writable, "A privileged process can bypass the fixture's directory permissions");
      VLCInstaller.removeLegacyInstallations(this.folder);
      final boolean legacyRemains = Files.isDirectory(legacy);
      assertTrue(legacyRemains, "failed cleanup is reported and leaves the undeletable directory in place");
    } finally {
      Files.setPosixFilePermissions(legacy, original);
    }
  }

  @Test
  void offersTheBundledDownloadForThisPlatform() {
    final Platform current = Platform.getCurrentPlatform();
    final OS operatingSystem = current.getOS();
    final Arch architecture = current.getArch();
    Assumptions.assumeTrue(
      operatingSystem == OS.WINDOWS || operatingSystem == OS.MAC,
      "Only Windows and macOS use the bundled download list"
    );
    Assumptions.assumeTrue(architecture == Arch.X86, "The bundled list is only checked on x86");

    final VLCInstaller installer = VLCInstaller.create(this.folder);
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final String hash = installer.getHash();
    final String extension = operatingSystem == OS.WINDOWS ? ".zip" : ".dmg";
    final boolean expectedExtension = url.endsWith(extension);
    assertTrue(supported);
    assertTrue(expectedExtension, url);
    assertSha256(hash, url);
  }

  @Test
  void bundlesVerifiedDownloadsOfTheAdvertisedVersion() {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("vlc.json");
    final String versionDirectory = "/" + VLCInstaller.VERSION + "/";
    assertTrue(downloads.length > 0);
    for (final Download download : downloads) {
      final String url = download.getUrl();
      final String hash = download.getHash();
      final boolean advertisedVersion = url.contains(versionDirectory);
      final boolean secure = url.startsWith("https://");
      assertTrue(advertisedVersion, url);
      assertTrue(secure, url);
      assertSha256(hash, url);
    }
  }

  private static void assertSha256(final String hash, final String url) {
    assertNotNull(hash, url);
    final int length = hash.length();
    assertEquals(64, length, url);
  }

  private VLCInstaller installerDownloadingFrom(final LocalHttpServer server) {
    final URI uri = server.uri(ZIP_PATH);
    final String url = uri.toString();
    final Platform current = Platform.getCurrentPlatform();
    final Download download = new Download(current, url);
    return new VLCInstaller(this.folder, () -> new Download[] { download });
  }

  private void assertInstalledWithoutLeftovers(final Path installed, final Path leftover) {
    final Path library = installed.resolve("libvlc.dll");
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
    final Path nested = root.resolve("nested");
    Files.createDirectories(nested);
    final Path file = root.resolve("file.txt");
    Files.writeString(file, "content");
    final Path nestedFile = nested.resolve("nested.txt");
    Files.writeString(nestedFile, "content");
    return root;
  }

  private static boolean tryCreateLink(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (final IOException | UnsupportedOperationException exception) {
      return false;
    }
  }

  private Path createInstalledLibrary() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    final boolean installable = VLCInstaller.hasStrategy(operatingSystem);
    Assumptions.assumeTrue(installable, "VLC cannot be installed automatically on " + operatingSystem);
    final Path installDirectory = this.folder.resolve("vlc");
    final Path relativeLibraryDirectory = relativeLibraryDirectory(operatingSystem);
    final Path libraryDirectory = installDirectory.resolve(relativeLibraryDirectory);
    final String libraryName = libraryName(operatingSystem);
    final Path library = libraryDirectory.resolve(libraryName);
    Files.createDirectories(libraryDirectory);
    Files.writeString(library, "structural library fixture, never loaded");
    final String counterpart =
      switch (operatingSystem) {
        case WINDOWS -> "libvlccore.dll";
        case MAC -> "libvlccore.dylib";
        case LINUX, FREEBSD, OTHER -> "libvlc.so.5";
      };
    final Path companion = libraryDirectory.resolve(counterpart);
    Files.writeString(companion, "structural companion fixture, never loaded");
    return libraryDirectory;
  }

  private static Path relativeLibraryDirectory(final OS operatingSystem) {
    return switch (operatingSystem) {
      case WINDOWS -> Path.of("");
      case MAC -> Path.of("VLC.app", "Contents", "MacOS", "lib");
      case LINUX, FREEBSD, OTHER -> Path.of("usr", "lib");
    };
  }

  private static String libraryName(final OS operatingSystem) {
    return switch (operatingSystem) {
      case WINDOWS -> "libvlc.dll";
      case MAC -> "libvlc.dylib";
      case LINUX, FREEBSD, OTHER -> "libvlccore.so.9";
    };
  }

  private byte[] createVlcZip() throws IOException {
    final Path zip = this.folder.resolve("vlc-source.zip");
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

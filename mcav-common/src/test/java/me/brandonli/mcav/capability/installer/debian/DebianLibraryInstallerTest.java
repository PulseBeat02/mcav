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
package me.brandonli.mcav.capability.installer.debian;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.hash.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DebianLibraryInstallerTest {

  private static final String NAME = "debian-test";
  private static final int OK = 200;

  private static final Platform ARM64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Platform X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform WINDOWS = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);

  private static final String FIRST_PACKAGE = "/first.deb";
  private static final String SECOND_PACKAGE = "/second.deb";
  private static final String X86_PACKAGE = "/x86.deb";

  private static final String LIBRARY_FOLDER = "usr/lib/aarch64-linux-gnu";
  private static final String HELPER_FOLDER = "usr/lib/aarch64-linux-gnu/pulseaudio";
  private static final String LEGACY_FOLDER = "lib/aarch64-linux-gnu";
  private static final String FIRST_LIBRARY = "usr/lib/aarch64-linux-gnu/liba.so.1.0";
  private static final String HELPER_LIBRARY = "usr/lib/aarch64-linux-gnu/pulseaudio/libhelper.so.4";
  private static final String SECOND_LIBRARY = "usr/lib/aarch64-linux-gnu/libb.so.2";
  private static final String LEGACY_LIBRARY = "lib/aarch64-linux-gnu/libold.so.3";
  private static final List<String> LOAD_ORDER = List.of(FIRST_LIBRARY, HELPER_LIBRARY, SECOND_LIBRARY, LEGACY_LIBRARY);
  private static final String LOAD_ORDER_FILE = "load-order.txt";

  private static final byte[] CONTENT = "library".getBytes(StandardCharsets.US_ASCII);

  private static final String SKIPPED_MESSAGE = "Library %s of debian-test could not be loaded and is skipped.";

  @TempDir
  private Path temporary;

  private LocalHttpServer server;
  private Path folder;
  private byte[] firstPackage;
  private byte[] secondPackage;
  private List<Path> loaded;

  @BeforeEach
  void setUp() {
    this.server = LocalHttpServer.start();
    this.folder = this.temporary.resolve(NAME);
    this.loaded = new ArrayList<>();
    this.firstPackage = new DebianPackageBuilder()
      .file("./usr/lib/aarch64-linux-gnu/liba.so.1.0", CONTENT)
      .symbolicLink("./usr/lib/aarch64-linux-gnu/liba.so.1", "liba.so.1.0")
      .file("./usr/lib/aarch64-linux-gnu/pulseaudio/libhelper.so.4", CONTENT)
      .symbolicLink("./usr/lib/aarch64-linux-gnu/linked", "pulseaudio")
      .file("./usr/lib/aarch64-linux-gnu/vlc/plugins/libplugin.so", CONTENT)
      .file("./usr/lib/aarch64-linux-gnu/README", CONTENT)
      .file("./usr/lib/aarch64-linux-gnu/liba.a", CONTENT)
      .build();
    this.secondPackage = new DebianPackageBuilder()
      .file("./usr/lib/aarch64-linux-gnu/liba.so.1.0", CONTENT)
      .file("./usr/lib/aarch64-linux-gnu/libb.so.2", CONTENT)
      .file("./lib/aarch64-linux-gnu/libold.so.3", CONTENT)
      .build();
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @Test
  void shouldInstallThePackagesAndRememberTheLoadOrder() throws IOException {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    this.serveBothPackages();
    final Path libraryDirectory = installer.download(FileMode.EXECUTABLE);

    final Path expectedDirectory = this.folder.resolve("usr/lib/aarch64-linux-gnu");
    assertEquals(expectedDirectory, libraryDirectory);

    final List<String> loadOrder = Files.readAllLines(this.folder.resolve(LOAD_ORDER_FILE));
    assertEquals(LOAD_ORDER, loadOrder);

    final List<Path> leftOver = this.listTemporary();
    assertEquals(List.of(this.folder), leftOver);
  }

  @Test
  void shouldReuseAnEarlierInstallationWithoutDownloading() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);
    final DebianLibraryInstaller again = this.createInstaller(ARM64, this::record);
    final Optional<Path> found = again.findInstallation();
    final Path reused = again.download(FileMode.EXECUTABLE);

    final Path expectedDirectory = this.folder.resolve("usr/lib/aarch64-linux-gnu");
    assertEquals(Optional.of(expectedDirectory), found);
    assertEquals(expectedDirectory, reused);

    final int requests = this.server.getTotalRequestCount();
    assertEquals(2, requests);
  }

  @Test
  void shouldFindNoInstallationBeforeTheDownload() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);

    final Optional<Path> found = installer.findInstallation();
    assertEquals(Optional.empty(), found);
  }

  @Test
  void shouldLoadEveryLibraryInTheOrderOfThePackages() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();

    final List<Path> expected = this.resolveAll(LOAD_ORDER);
    assertEquals(expected, this.loaded);
  }

  @Test
  void shouldRetryALibraryAfterTheOthersLoaded() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::recordAfterSecond);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();

    final List<Path> expected = this.resolveAll(List.of(HELPER_LIBRARY, SECOND_LIBRARY, LEGACY_LIBRARY, FIRST_LIBRARY));
    assertEquals(expected, this.loaded);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldWarnAboutALibraryThatNeverLoads() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, library -> this.recordAllBut(LEGACY_LIBRARY, library));
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);
    final String log = this.captureStandardError(installer::loadLibraries);

    final List<Path> expected = this.resolveAll(List.of(FIRST_LIBRARY, HELPER_LIBRARY, SECOND_LIBRARY));
    assertEquals(expected, this.loaded);

    final String warning = SKIPPED_MESSAGE.formatted(this.folder.resolve(LEGACY_LIBRARY));
    final boolean warned = log.contains(warning);
    assertTrue(warned, log);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldSkipALibraryOfASubfolderWithoutAWarning() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, library -> this.recordAllBut(HELPER_LIBRARY, library));
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);
    final String log = this.captureStandardError(installer::loadLibraries);

    final List<Path> expected = this.resolveAll(List.of(FIRST_LIBRARY, SECOND_LIBRARY, LEGACY_LIBRARY));
    assertEquals(expected, this.loaded);

    final String warning = SKIPPED_MESSAGE.formatted(this.folder.resolve(HELPER_LIBRARY));
    final boolean warned = log.contains(warning);
    assertFalse(warned, log);
  }

  @Test
  void shouldListTheFoldersOfTheLibrariesInTheirLoadOrder() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);

    final List<Path> folders = installer.listLibraryFolders();
    final List<Path> expected = this.resolveAll(List.of(LIBRARY_FOLDER, HELPER_FOLDER, LEGACY_FOLDER));
    assertEquals(expected, folders);
  }

  @Test
  void shouldSkipAFileThatTheJvmCannotLoad() {
    final Download[] downloads = this.createDownloads();
    final DebianLibraryInstaller installer = new DebianLibraryInstaller(this.folder, NAME, downloads, ARM64);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);

    assertDoesNotThrow(installer::loadLibraries);
  }

  @Test
  void shouldRefuseToLoadWithoutAnInstallation() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);

    final InstallationException failure = assertThrows(InstallationException.class, installer::loadLibraries);
    final String message = failure.getMessage();
    final String expected = "Installation of debian-test at %s could not be read!".formatted(this.folder);
    assertEquals(expected, message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "../escape.so", "" })
  void shouldRefuseALoadOrderThatLeavesTheInstallation(final String library) throws IOException {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    Files.createDirectories(this.folder);
    Files.write(this.folder.resolve(LOAD_ORDER_FILE), List.of(library));

    final InstallationException failure = assertThrows(InstallationException.class, installer::loadLibraries);
    final String message = failure.getMessage();
    final String expected = "Library %s of debian-test is outside its installation!".formatted(library);
    assertEquals(expected, message);
  }

  @Test
  void shouldRefuseAPlatformWithoutPackages() {
    final DebianLibraryInstaller installer = this.createInstaller(WINDOWS, this::record);
    final boolean supported = installer.isSupported();
    assertFalse(supported);

    final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
    final String message = failure.getMessage();
    final String expected = "Platform %s has no debian-test packages!".formatted(WINDOWS);
    assertEquals(expected, message);
  }

  @Test
  void shouldSupportAPlatformWithPackages() {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);

    final boolean supported = installer.isSupported();
    assertTrue(supported);
  }

  @Test
  void shouldNameTheLibraryFolderOfTheArchitecture() {
    final DebianLibraryInstaller installer = this.createInstaller(X86_64, this::record);

    final Path path = installer.getPath();
    final Path expected = this.folder.resolve("usr/lib/x86_64-linux-gnu");
    assertEquals(expected, path);
  }

  @Test
  void shouldRefuseAPackageWithoutAPinnedHash() {
    final String url = this.server.uri(FIRST_PACKAGE).toString();
    final Download[] downloads = { new Download(ARM64, url) };
    final DebianLibraryInstaller installer = new DebianLibraryInstaller(this.folder, NAME, downloads, ARM64, this::record);

    final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
    final String message = failure.getMessage();
    final String expected = "Debian package %s has no pinned hash!".formatted(url);
    assertEquals(expected, message);
  }

  @Test
  void shouldLeaveNothingBehindWhenADownloadFails() throws IOException {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    this.server.respond(FIRST_PACKAGE, OK, this.firstPackage);
    this.server.respond(SECOND_PACKAGE, OK, this.firstPackage);

    assertThrows(DownloadException.class, () -> installer.download(FileMode.EXECUTABLE));

    final List<Path> leftOver = this.listTemporary();
    assertEquals(List.of(), leftOver);
  }

  @Test
  void shouldLeaveNothingBehindWhenAPackageCannotBeExtracted() throws IOException {
    final byte[] escapingPackage = new DebianPackageBuilder()
      .file("./usr/lib/aarch64-linux-gnu/liba.so.1.0", CONTENT)
      .symbolicLink("./usr/lib/aarch64-linux-gnu/escape", "../../../..")
      .build();
    final Download[] downloads = { this.createDownload(ARM64, FIRST_PACKAGE, escapingPackage) };
    final DebianLibraryInstaller installer = new DebianLibraryInstaller(this.folder, NAME, downloads, ARM64, this::record);
    this.server.respond(FIRST_PACKAGE, OK, escapingPackage);

    assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));

    final List<Path> leftOver = this.listTemporary();
    assertEquals(List.of(), leftOver);
  }

  @Test
  void shouldReplaceABrokenInstallation() throws IOException {
    final DebianLibraryInstaller installer = this.createInstaller(ARM64, this::record);
    final Path junk = this.folder.resolve("junk");
    Files.createDirectories(this.folder);
    Files.write(junk, CONTENT);
    this.serveBothPackages();
    installer.download(FileMode.EXECUTABLE);

    final boolean junkExists = Files.exists(junk);
    final boolean installed = Files.exists(this.folder.resolve(LOAD_ORDER_FILE));
    assertFalse(junkExists);
    assertTrue(installed);
  }

  @Test
  void shouldReportAnInstallationFolderThatCannotBeCreated() throws IOException {
    final Path blocker = this.temporary.resolve("blocker");
    Files.write(blocker, CONTENT);
    final Path blockedFolder = blocker.resolve(NAME);
    final Download[] downloads = this.createDownloads();
    final DebianLibraryInstaller installer = new DebianLibraryInstaller(blockedFolder, NAME, downloads, ARM64, this::record);

    final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
    final String message = failure.getMessage();
    final Throwable cause = failure.getCause();
    final String expected = "Debian packages of debian-test could not be installed to %s!".formatted(blockedFolder);
    assertEquals(expected, message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldPinEveryBundledPackageWithAHash() {
    final Download[] vlc = IoUtils.readDownloadsFromJsonResource("mcav/common/installers/debian-vlc.json");
    final Download[] gtk2 = IoUtils.readDownloadsFromJsonResource("mcav/common/installers/debian-gtk2.json");
    final Download[] ffmpeg = IoUtils.readDownloadsFromJsonResource("mcav/common/installers/debian-ffmpeg.json");

    final List<Platform> vlcPlatforms = Arrays.stream(vlc).map(Download::getPlatform).distinct().toList();
    final List<Platform> gtk2Platforms = Arrays.stream(gtk2).map(Download::getPlatform).distinct().toList();
    final List<Platform> ffmpegPlatforms = Arrays.stream(ffmpeg).map(Download::getPlatform).distinct().toList();
    final List<Platform> expectedPlatforms = List.of(X86_64, ARM64);
    assertEquals(expectedPlatforms, vlcPlatforms);
    assertEquals(expectedPlatforms, gtk2Platforms);
    assertEquals(expectedPlatforms, ffmpegPlatforms);

    final Stream<Download> downloads = Stream.of(vlc, gtk2, ffmpeg).flatMap(Arrays::stream);
    final boolean allHashed = downloads.allMatch(download -> download.getHash().isPresent());
    assertTrue(allHashed);
  }

  @Test
  void shouldCreateTheBundledInstallersInTheCache() {
    final DebianLibraryInstaller vlc = DebianLibraryInstaller.createVlcInstaller();
    final DebianLibraryInstaller gtk2 = DebianLibraryInstaller.createGtk2Installer();
    final DebianLibraryInstaller ffmpeg = DebianLibraryInstaller.createFfmpegInstaller();

    final Path cache = IoUtils.getCachedFolder();
    final boolean vlcInCache = vlc.getPath().startsWith(cache.resolve("debian-vlc"));
    final boolean gtk2InCache = gtk2.getPath().startsWith(cache.resolve("debian-gtk2"));
    final boolean ffmpegInCache = ffmpeg.getPath().startsWith(cache.resolve("debian-ffmpeg"));
    assertTrue(vlcInCache);
    assertTrue(gtk2InCache);
    assertTrue(ffmpegInCache);
  }

  private DebianLibraryInstaller createInstaller(final Platform platform, final Consumer<String> loader) {
    final Download[] downloads = this.createDownloads();
    return new DebianLibraryInstaller(this.folder, NAME, downloads, platform, loader);
  }

  private Download[] createDownloads() {
    return new Download[] {
      this.createDownload(ARM64, FIRST_PACKAGE, this.firstPackage),
      this.createDownload(X86_64, X86_PACKAGE, this.secondPackage),
      this.createDownload(ARM64, SECOND_PACKAGE, this.secondPackage),
    };
  }

  private Download createDownload(final Platform platform, final String path, final byte[] content) {
    final String url = this.server.uri(path).toString();
    final String sha256 = Hashing.sha256().hashBytes(content).toString();
    return new Download(platform, url, sha256);
  }

  private void serveBothPackages() {
    this.server.respond(FIRST_PACKAGE, OK, this.firstPackage);
    this.server.respond(SECOND_PACKAGE, OK, this.secondPackage);
  }

  private List<Path> listTemporary() throws IOException {
    try (final Stream<Path> files = Files.list(this.temporary)) {
      return files.toList();
    }
  }

  private List<Path> resolveAll(final List<String> libraries) {
    return libraries.stream().map(this.folder::resolve).toList();
  }

  private void record(final String library) {
    final Path path = Path.of(library);
    this.loaded.add(path);
  }

  private void recordAfterSecond(final String library) {
    final Path path = Path.of(library);
    final Path second = this.folder.resolve(SECOND_LIBRARY);
    final boolean first = path.equals(this.folder.resolve(FIRST_LIBRARY));
    if (first && !this.loaded.contains(second)) {
      throw new UnsatisfiedLinkError("libb.so.2: cannot open shared object file");
    }

    this.loaded.add(path);
  }

  private void recordAllBut(final String refusedLibrary, final String library) {
    final Path path = Path.of(library);
    final boolean refused = path.equals(this.folder.resolve(refusedLibrary));
    if (refused) {
      final String message = "%s: version GLIBC_2.99 not found".formatted(library);
      throw new UnsatisfiedLinkError(message);
    }

    this.loaded.add(path);
  }

  private String captureStandardError(final Runnable action) {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
    System.setErr(capture);
    try {
      action.run();
    } finally {
      System.setErr(original);
    }

    return captured.toString(StandardCharsets.UTF_8);
  }
}

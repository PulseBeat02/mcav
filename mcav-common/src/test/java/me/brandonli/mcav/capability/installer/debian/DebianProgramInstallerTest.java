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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DebianProgramInstallerTest {

  private static final String NAME = "debian-test";
  private static final String RESOURCE = "mcav/common/installers/debian-gtk2.json";
  private static final List<String> TREES = List.of("usr/bin", "usr/share/tool");
  private static final int OK = 200;

  private static final Platform X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform ARM64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Platform WINDOWS = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);

  private static final String FIRST_PACKAGE = "/first.deb";
  private static final String SECOND_PACKAGE = "/second.deb";
  private static final String ARM_PACKAGE = "/arm.deb";

  private static final String PROGRAM = "usr/bin/tool";
  private static final String LIBRARY = "usr/lib/x86_64-linux-gnu/liba.so.1";
  private static final String LIBRARY_FOLDER = "usr/lib/x86_64-linux-gnu";
  private static final String LEGACY_LIBRARY_FOLDER = "lib/x86_64-linux-gnu";
  private static final String POSIX_VIEW = "posix";

  private static final byte[] CONTENT = "program".getBytes(StandardCharsets.US_ASCII);

  @TempDir
  private Path temporary;

  private LocalHttpServer server;
  private Path folder;
  private byte[] firstPackage;
  private byte[] secondPackage;

  @BeforeEach
  void setUp() {
    this.server = LocalHttpServer.start();
    this.folder = this.temporary.resolve(NAME);
    this.firstPackage = new DebianPackageBuilder()
      .executableFile("./usr/bin/tool", CONTENT)
      .symbolicLink("./usr/bin/tool-link", "tool")
      .file("./usr/lib/x86_64-linux-gnu/liba.so.1", CONTENT)
      .file("./usr/share/tool/firmware.bin", CONTENT)
      .file("./usr/share/doc/tool/copyright", CONTENT)
      .build();
    this.secondPackage = new DebianPackageBuilder()
      .file("./usr/lib/x86_64-linux-gnu/libb.so.2", CONTENT)
      .file("./lib/x86_64-linux-gnu/libold.so.3", CONTENT)
      .build();
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @Test
  void shouldInstallTheNamedTreesAndReturnTheRoot() throws IOException {
    final DebianProgramInstaller installer = this.createInstaller(X86_64);
    this.serveBothPackages();
    final Path root = installer.download(FileMode.UNCHANGED);

    assertEquals(this.folder, root);

    final byte[] program = Files.readAllBytes(root.resolve(PROGRAM));
    final byte[] linked = Files.readAllBytes(root.resolve("usr/bin/tool-link"));
    final byte[] firmware = Files.readAllBytes(root.resolve("usr/share/tool/firmware.bin"));
    assertArrayEquals(CONTENT, program);
    assertArrayEquals(CONTENT, linked);
    assertArrayEquals(CONTENT, firmware);

    final boolean documentation = Files.exists(root.resolve("usr/share/doc"));
    assertFalse(documentation);
  }

  @Test
  void shouldMakeOnlyTheProgramsExecutable() throws IOException {
    final FileSystem fileSystem = FileSystems.getDefault();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean posix = views.contains(POSIX_VIEW);
    Assumptions.assumeTrue(posix, "Executable bits need a POSIX file system!");

    final DebianProgramInstaller installer = this.createInstaller(X86_64);
    this.serveBothPackages();
    final Path root = installer.download(FileMode.UNCHANGED);

    final Set<PosixFilePermission> program = Files.getPosixFilePermissions(root.resolve(PROGRAM));
    final Set<PosixFilePermission> library = Files.getPosixFilePermissions(root.resolve(LIBRARY));
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), program);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), library);
  }

  @Test
  void shouldListTheLibraryFoldersInTheOrderOfThePackages() {
    final DebianProgramInstaller installer = this.createInstaller(X86_64);
    this.serveBothPackages();
    installer.download(FileMode.UNCHANGED);

    final List<Path> libraryFolders = installer.listLibraryFolders();
    final List<Path> expected = List.of(this.folder.resolve(LIBRARY_FOLDER), this.folder.resolve(LEGACY_LIBRARY_FOLDER));
    assertEquals(expected, libraryFolders);
  }

  @Test
  void shouldRefuseToListLibrariesWithoutAnInstallation() {
    final DebianProgramInstaller installer = this.createInstaller(X86_64);

    final InstallationException failure = assertThrows(InstallationException.class, installer::listLibraryFolders);
    final String message = failure.getMessage();
    final String expected = "Installation of debian-test at %s could not be read!".formatted(this.folder);
    assertEquals(expected, message);
  }

  @Test
  void shouldFindOnlyACompleteInstallation() {
    final DebianProgramInstaller installer = this.createInstaller(X86_64);

    final Optional<Path> missing = installer.findInstallation();
    assertEquals(Optional.empty(), missing);

    this.serveBothPackages();
    installer.download(FileMode.UNCHANGED);

    final Optional<Path> found = installer.findInstallation();
    assertEquals(Optional.of(this.folder), found);
  }

  @Test
  void shouldNameTheRootBeforeInstalling() {
    final DebianProgramInstaller installer = this.createInstaller(X86_64);

    final Path path = installer.getPath();
    assertEquals(this.folder, path);
  }

  @Test
  void shouldRefuseAPlatformWithoutPackages() {
    final DebianProgramInstaller installer = this.createInstaller(WINDOWS);
    final boolean supported = installer.isSupported();
    assertFalse(supported);

    final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.UNCHANGED));
    final String message = failure.getMessage();
    final String expected = "Platform %s has no debian-test packages!".formatted(WINDOWS);
    assertEquals(expected, message);
  }

  @Test
  void shouldSupportAPlatformWithPackages() {
    final DebianProgramInstaller installer = this.createInstaller(ARM64);

    final boolean supported = installer.isSupported();
    assertTrue(supported);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "..", ".cache", "a/b", "a\\b", "a b" })
  void shouldRefuseANameThatIsNotAFolderName(final String name) {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      DebianProgramInstaller.create(RESOURCE, name, TREES)
    );

    final String message = failure.getMessage();
    final String expected = "Name %s must be a folder name!".formatted(name);
    assertEquals(expected, message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "/usr/bin", "usr/../etc", "./usr", "usr//bin", "usr/bin/", "usr\\bin" })
  void shouldRefuseATreeThatIsNotARelativePathOfFolderNames(final String tree) {
    final List<String> trees = List.of("usr/bin", tree);
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      DebianProgramInstaller.create(RESOURCE, NAME, trees)
    );

    final String message = failure.getMessage();
    final String expected = "Tree %s must be a relative path of folder names!".formatted(tree);
    assertEquals(expected, message);
  }

  @Test
  void shouldRefuseNullArguments() {
    final List<String> nullTree = Arrays.asList("usr/bin", null);
    final DebianProgramInstaller installer = this.createInstaller(X86_64);

    final NullPointerException resource = assertThrows(NullPointerException.class, () -> DebianProgramInstaller.create(null, NAME, TREES));
    final String resourceMessage = resource.getMessage();
    assertEquals("Resource must not be null!", resourceMessage);

    final NullPointerException name = assertThrows(NullPointerException.class, () -> DebianProgramInstaller.create(RESOURCE, null, TREES));
    final String nameMessage = name.getMessage();
    assertEquals("Name must not be null!", nameMessage);

    final NullPointerException trees = assertThrows(NullPointerException.class, () -> DebianProgramInstaller.create(RESOURCE, NAME, null));
    final String treesMessage = trees.getMessage();
    assertEquals("Trees must not be null!", treesMessage);

    final NullPointerException tree = assertThrows(NullPointerException.class, () ->
      DebianProgramInstaller.create(RESOURCE, NAME, nullTree)
    );
    final String treeMessage = tree.getMessage();
    assertEquals("Tree must not be null!", treeMessage);

    final NullPointerException mode = assertThrows(NullPointerException.class, () -> installer.download(null));
    final String modeMessage = mode.getMessage();
    assertEquals("Mode must not be null!", modeMessage);
  }

  @Test
  void shouldCreateTheInstallerInTheCache() {
    final DebianProgramInstaller installer = DebianProgramInstaller.create(RESOURCE, "debian-program-test", TREES);

    final Path path = installer.getPath();
    final Path cache = IoUtils.getCachedFolder();
    final Path expected = cache.resolve("debian-program-test");
    assertEquals(expected, path);
  }

  private DebianProgramInstaller createInstaller(final Platform platform) {
    final Download[] downloads = {
      this.createDownload(X86_64, FIRST_PACKAGE, this.firstPackage),
      this.createDownload(ARM64, ARM_PACKAGE, this.secondPackage),
      this.createDownload(X86_64, SECOND_PACKAGE, this.secondPackage),
    };
    return new DebianProgramInstaller(this.folder, NAME, downloads, platform, TREES);
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
}

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
package me.brandonli.mcav.capability.installer.vlc.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManualInstallationStrategyTest {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dll");
  private static final Pattern CORE_LIBRARY = Pattern.compile("libvlccore\\.dll");

  private static final String LIBRARY_NAME = "libvlc.dll";
  private static final String CORE_LIBRARY_NAME = "libvlccore.dll";
  private static final String OLD_LIBRARY_NAME = "libvlc.dll.old";
  private static final String PREFIXED_LIBRARY_NAME = "mylibvlc.dll";

  private static final String VLC_DIRECTORY_NAME = "vlc";
  private static final String VERSION_DIRECTORY_NAME = "vlc-3.0.24";
  private static final String OTHER_DIRECTORY_NAME = "other";
  private static final String LEVEL_PREFIX = "level";
  private static final String MISSING_NAME = "missing";

  private static final String TREE_DIRECTORY_NAME = "tree";
  private static final String FILE_NAME = "file.txt";

  private static final String TARGET_DIRECTORY_NAME = "target";
  private static final String LINK_NAME = "link";

  private static final String ARCHIVE_NAME = "archive.zip";

  private static final String WORK_DIRECTORY_NAME = "work";
  private static final String MARKER_NAME = "marker.txt";

  private static final String LOCKED_DIRECTORY_NAME = "locked";
  private static final Set<PosixFilePermission> OWNER_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> READ_ONLY_PERMISSIONS = PosixFilePermissions.fromString("r-x------");

  @TempDir
  private Path temporaryDirectory;

  private VlcInstaller installer;
  private TestStrategy strategy;

  @BeforeEach
  void setUp() {
    this.installer = VlcInstaller.create(this.temporaryDirectory);
    this.strategy = new TestStrategy(this.installer);
  }

  @Test
  void shouldExposeTheInstallerAndItsInstallationDirectory() {
    final VlcInstaller strategyInstaller = this.strategy.getInstaller();
    final Path installDirectory = this.strategy.getInstallDirectory();
    final Path expectedDirectory = this.temporaryDirectory.resolve(VLC_DIRECTORY_NAME);
    assertSame(this.installer, strategyInstaller);
    assertEquals(expectedDirectory, installDirectory);
  }

  @Test
  void shouldRejectMissingCollaborators() {
    assertThrows(NullPointerException.class, () -> new TestStrategy(null));
    assertThrows(NullPointerException.class, () -> new TestStrategy(this.installer, null));
    assertThrows(NullPointerException.class, () -> this.strategy.findLibraryDirectory(null, LIBRARY));
    assertThrows(NullPointerException.class, () -> this.strategy.findLibraryDirectory(this.temporaryDirectory, null));
    assertThrows(NullPointerException.class, () -> this.strategy.findLibraryDirectory(this.temporaryDirectory, LIBRARY, (Pattern[]) null));
    assertThrows(NullPointerException.class, () -> this.strategy.findLibraryDirectory(this.temporaryDirectory, LIBRARY, LIBRARY, null));
  }

  @Test
  void shouldFindTheDirectoryOfANestedLibrary() throws IOException {
    final Path relativeNested = Path.of(VLC_DIRECTORY_NAME, VERSION_DIRECTORY_NAME);
    final Path nested = this.temporaryDirectory.resolve(relativeNested);
    Files.createDirectories(nested);
    createFile(nested, LIBRARY_NAME);
    final Optional<Path> found = this.strategy.findLibraryDirectory(this.temporaryDirectory, LIBRARY);
    final Optional<Path> expected = Optional.of(nested);
    assertEquals(expected, found);
  }

  @Test
  void shouldFindNothingInAMissingDirectory() throws IOException {
    final Path missing = this.temporaryDirectory.resolve(MISSING_NAME);
    final Optional<Path> found = this.strategy.findLibraryDirectory(missing, LIBRARY);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void shouldIgnoreDirectoriesAndPartialNameMatches() throws IOException {
    createDirectory(this.temporaryDirectory, LIBRARY_NAME);
    createFile(this.temporaryDirectory, OLD_LIBRARY_NAME);
    createFile(this.temporaryDirectory, PREFIXED_LIBRARY_NAME);
    final Optional<Path> found = this.strategy.findLibraryDirectory(this.temporaryDirectory, LIBRARY);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void shouldFindFilesAtMostEightLevelsBelowTheSearchedDirectory() throws IOException {
    final Path eightDeep = nest(this.temporaryDirectory, 7);
    final Path other = this.temporaryDirectory.resolve(OTHER_DIRECTORY_NAME);
    final Path nineDeep = nest(other, 7);
    createFile(eightDeep, LIBRARY_NAME);
    createFile(nineDeep, CORE_LIBRARY_NAME);
    final Optional<Path> reachable = this.strategy.findLibraryDirectory(this.temporaryDirectory, LIBRARY);
    final Optional<Path> unreachable = this.strategy.findLibraryDirectory(this.temporaryDirectory, CORE_LIBRARY);
    final Optional<Path> expected = Optional.of(eightDeep);
    final boolean nothingUnreachableFound = unreachable.isEmpty();
    assertEquals(expected, reachable);
    assertTrue(nothingUnreachableFound);
  }

  @Test
  void shouldNotFollowDirectoryLinks() throws IOException {
    final Path target = createDirectory(this.temporaryDirectory, TARGET_DIRECTORY_NAME);
    createFile(target, LIBRARY_NAME);
    final Path tree = createDirectory(this.temporaryDirectory, TREE_DIRECTORY_NAME);
    final Path link = tree.resolve(LINK_NAME);
    createLinkOrAbort(link, target);
    final Optional<Path> found = this.strategy.findLibraryDirectory(tree, LIBRARY);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void shouldDeleteAFileThroughTheInstanceHelper() throws IOException {
    final Path file = createFile(this.temporaryDirectory, ARCHIVE_NAME);
    this.strategy.deleteFile(file);
    this.strategy.deleteFile(file);
    final boolean exists = Files.exists(file);
    assertFalse(exists);
    assertThrows(NullPointerException.class, () -> this.strategy.deleteFile(null));
  }

  @Test
  void shouldReportADirectoryThatCannotBeDeletedAsAFile() throws IOException {
    final Path tree = Files.createDirectory(this.temporaryDirectory.resolve(TREE_DIRECTORY_NAME));
    createFile(tree, FILE_NAME);
    final FileOperationException thrown = assertThrows(FileOperationException.class, () -> this.strategy.deleteFile(tree));
    final Throwable cause = thrown.getCause();
    assertInstanceOf(DirectoryNotEmptyException.class, cause);
  }

  @Test
  void shouldReportATreeThatCannotBeDeleted() throws IOException {
    final Path tree = createDirectory(this.temporaryDirectory, TREE_DIRECTORY_NAME);
    final Path locked = createDirectory(tree, LOCKED_DIRECTORY_NAME);
    final Path file = createFile(locked, FILE_NAME);
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    Files.setPosixFilePermissions(locked, READ_ONLY_PERMISSIONS);
    try {
      final boolean writable = Files.isWritable(locked);
      Assumptions.assumeFalse(writable, "The user can write every directory!");

      final FileOperationException thrown = assertThrows(FileOperationException.class, () -> IoUtils.deleteRecursively(tree));
      final Throwable cause = thrown.getCause();
      assertInstanceOf(AccessDeniedException.class, cause);

      final boolean fileExists = Files.exists(file);
      assertTrue(fileExists);
    } finally {
      Files.setPosixFilePermissions(locked, OWNER_PERMISSIONS);
    }
  }

  @Test
  void shouldReportADirectoryThatCannotBeSearched() throws IOException {
    final Path locked = Files.createDirectory(this.temporaryDirectory.resolve(VLC_DIRECTORY_NAME));
    final Set<PosixFilePermission> noPermissions = Set.of();
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    Files.setPosixFilePermissions(locked, noPermissions);
    try {
      final boolean readable = Files.isReadable(locked);
      Assumptions.assumeFalse(readable, "The user can read every directory!");

      final InstallationException thrown = assertThrows(InstallationException.class, () ->
        this.strategy.findLibraryDirectory(locked, LIBRARY)
      );
      final Throwable cause = thrown.getCause();
      assertInstanceOf(AccessDeniedException.class, cause);
    } finally {
      Files.setPosixFilePermissions(locked, OWNER_PERMISSIONS);
    }
  }

  @Test
  void shouldRunToolsAsChildProcesses() throws IOException {
    final Path workingDirectory = createDirectory(this.temporaryDirectory, WORK_DIRECTORY_NAME);
    final Path marker = workingDirectory.resolve(MARKER_NAME);
    final Path markerElsewhere = this.temporaryDirectory.resolve(MARKER_NAME);
    final String[] createMarker = shellCommand("echo created > marker.txt");
    this.strategy.runProcess(workingDirectory, createMarker);
    final boolean created = Files.exists(marker);
    final boolean createdElsewhere = Files.exists(markerElsewhere);
    assertTrue(created);
    assertFalse(createdElsewhere);
  }

  @Test
  void shouldFailWhenAToolExitsWithAnError() {
    final String[] failing = shellCommand("exit 3");
    final InstallationException thrown = assertThrows(InstallationException.class, () -> this.strategy.runProcess(null, failing));
    final Throwable cause = thrown.getCause();
    final String message = cause.getMessage();
    final boolean namesTheExitCode = message.contains("exited with code 3");
    assertTrue(namesTheExitCode, message);
  }

  @Test
  void shouldFailWhenAToolCannotBeStarted() {
    final String missingTool = this.temporaryDirectory.resolve(MISSING_NAME).toString();
    final InstallationException thrown = assertThrows(InstallationException.class, () -> this.strategy.runProcess(null, missingTool));
    final String message = thrown.getMessage();
    final boolean namesTheTool = message.contains(missingTool);
    assertTrue(namesTheTool, message);
  }

  private static Path nest(final Path root, final int levels) throws IOException {
    Path current = root;
    for (int level = 0; level < levels; level++) {
      current = current.resolve(LEVEL_PREFIX + level);
    }

    return Files.createDirectories(current);
  }

  private static Path createDirectory(final Path parent, final String name) throws IOException {
    final Path directory = parent.resolve(name);
    return Files.createDirectories(directory);
  }

  private static Path createFile(final Path directory, final String name) throws IOException {
    final Path file = directory.resolve(name);
    return Files.writeString(file, "structural fixture, never loaded as native code");
  }

  private static void createLinkOrAbort(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (final IOException | UnsupportedOperationException exception) {
      final String reason = exception.getMessage();
      final String message = "Creating symbolic links failed with \"%s\" here!".formatted(reason);
      Assumptions.abort(message);
    }
  }

  private static String[] shellCommand(final String command) {
    final Os operatingSystem = OsUtils.getOs();
    if (operatingSystem == Os.WINDOWS) {
      return new String[] { "cmd", "/c", command };
    }

    return new String[] { "sh", "-c", command };
  }

  private static final class TestStrategy extends ManualInstallationStrategy {

    TestStrategy(final VlcInstaller installer) {
      super(installer);
    }

    TestStrategy(final VlcInstaller installer, final ProcessRunner processRunner) {
      super(installer, processRunner);
    }

    @Override
    public Path execute(final Path archive) {
      return archive;
    }

    @Override
    public Optional<Path> getInstalledPath() {
      return Optional.empty();
    }
  }
}

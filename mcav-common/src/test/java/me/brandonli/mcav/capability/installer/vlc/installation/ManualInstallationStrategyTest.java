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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the helpers of {@link ManualInstallationStrategy}.
 */
final class ManualInstallationStrategyTest {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dll");

  @TempDir
  private Path temp;

  @Test
  void exposesTheInstallerAndItsInstallationDirectory() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final TestStrategy strategy = new TestStrategy(installer);
    final VLCInstaller strategyInstaller = strategy.getInstaller();
    final Path installDirectory = strategy.getInstallDirectory();
    final Path expectedDirectory = this.temp.resolve("vlc");
    assertSame(installer, strategyInstaller);
    assertEquals(expectedDirectory, installDirectory);
  }

  @Test
  void rejectsMissingCollaborators() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    assertThrows(NullPointerException.class, () -> new TestStrategy(null));
    assertThrows(NullPointerException.class, () -> new TestStrategy(installer, null));
    assertThrows(NullPointerException.class, () -> ManualInstallationStrategy.findLibraryDirectory(null, LIBRARY));
    assertThrows(NullPointerException.class, () -> ManualInstallationStrategy.findLibraryDirectory(this.temp, null));
  }

  @Test
  void findsTheDirectoryOfANestedLibrary() throws IOException {
    final Path relativeNested = Path.of("vlc", "vlc-3.0.23");
    final Path nested = this.temp.resolve(relativeNested);
    Files.createDirectories(nested);
    createFile(nested, "libvlc.dll");
    final Optional<Path> found = ManualInstallationStrategy.findLibraryDirectory(this.temp, LIBRARY);
    final Optional<Path> expected = Optional.of(nested);
    assertEquals(expected, found);
  }

  @Test
  void findsNothingInAMissingDirectory() throws IOException {
    final Path missing = this.temp.resolve("missing");
    final Optional<Path> found = ManualInstallationStrategy.findLibraryDirectory(missing, LIBRARY);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void ignoresDirectoriesAndPartialNameMatches() throws IOException {
    createDirectory(this.temp, "libvlc.dll");
    createFile(this.temp, "libvlc.dll.old");
    createFile(this.temp, "mylibvlc.dll");
    final Optional<Path> found = ManualInstallationStrategy.findLibraryDirectory(this.temp, LIBRARY);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void findsFilesAtMostEightLevelsBelowTheSearchedDirectory() throws IOException {
    // the file name is the eighth level below the searched directory, seven directories deep
    final Path eightDeep = nest(this.temp, 7);
    final Path other = this.temp.resolve("other");
    final Path nineDeep = nest(other, 7);
    createFile(eightDeep, "libvlc.dll");
    createFile(nineDeep, "libvlccore.dll");
    final Optional<Path> reachable = ManualInstallationStrategy.findLibraryDirectory(this.temp, LIBRARY);
    final Pattern core = Pattern.compile("libvlccore\\.dll");
    final Optional<Path> unreachable = ManualInstallationStrategy.findLibraryDirectory(this.temp, core);
    final Optional<Path> expected = Optional.of(eightDeep);
    final boolean nothingUnreachableFound = unreachable.isEmpty();
    assertEquals(expected, reachable);
    assertTrue(nothingUnreachableFound);
  }

  @Test
  void deletesWholeDirectoryTrees() throws IOException {
    final Path tree = this.temp.resolve("tree");
    final Path deep = nest(tree, 3);
    createFile(deep, "file.txt");
    createFile(tree, "top.txt");
    ManualInstallationStrategy.deleteRecursively(tree);
    final boolean exists = Files.exists(tree);
    assertFalse(exists);
  }

  @Test
  void deletesSingleFilesAndIgnoresMissingPaths() throws IOException {
    final Path file = createFile(this.temp, "file.txt");
    final Path missing = this.temp.resolve("missing");
    ManualInstallationStrategy.deleteRecursively(file);
    ManualInstallationStrategy.deleteRecursively(missing);
    final boolean fileExists = Files.exists(file);
    assertFalse(fileExists);
  }

  @Test
  void deletesLinksWithoutTouchingTheirTargets() throws IOException {
    final Path target = createDirectory(this.temp, "target");
    final Path kept = createFile(target, "kept.txt");
    final Path tree = createDirectory(this.temp, "tree");
    final Path link = tree.resolve("link");
    createLinkOrAbort(link, target);
    ManualInstallationStrategy.deleteRecursively(tree);
    final boolean treeExists = Files.exists(tree);
    final boolean keptExists = Files.exists(kept);
    assertFalse(treeExists);
    assertTrue(keptExists);
  }

  @Test
  void deletesLinksWhoseTargetIsGone() throws IOException {
    final Path gone = this.temp.resolve("gone");
    final Path link = this.temp.resolve("dangling");
    createLinkOrAbort(link, gone);
    ManualInstallationStrategy.deleteRecursively(link);
    final boolean linkExists = Files.exists(link, LinkOption.NOFOLLOW_LINKS);
    assertFalse(linkExists, "a dangling link does not exist when followed, but is deleted all the same");
    assertThrows(NullPointerException.class, () -> ManualInstallationStrategy.deleteRecursively(null));
  }

  @Test
  void deletesAFileThroughTheInstanceHelper() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final TestStrategy strategy = new TestStrategy(installer);
    final Path file = createFile(this.temp, "archive.zip");
    strategy.deleteFile(file);
    strategy.deleteFile(file);
    final boolean exists = Files.exists(file);
    assertFalse(exists);
    assertThrows(NullPointerException.class, () -> strategy.deleteFile(null));
  }

  @Test
  void runsToolsAsChildProcesses() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final TestStrategy strategy = new TestStrategy(installer);
    // the marker is written with a relative path, so it lands in the working directory the tool was started in
    final Path workingDirectory = createDirectory(this.temp, "work");
    final Path marker = workingDirectory.resolve("marker.txt");
    final Path markerElsewhere = this.temp.resolve("marker.txt");
    final String[] createMarker = shellCommand("echo created > marker.txt");
    strategy.runProcess(workingDirectory, createMarker);
    final boolean created = Files.exists(marker);
    final boolean createdElsewhere = Files.exists(markerElsewhere);
    assertTrue(created);
    assertFalse(createdElsewhere);
  }

  @Test
  void failsWhenAToolExitsWithAnError() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final TestStrategy strategy = new TestStrategy(installer);
    final String[] failing = shellCommand("exit 3");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.runProcess(null, failing));
    final String message = thrown.getMessage();
    final boolean namesTheExitCode = message.contains("exited with code 3");
    assertTrue(namesTheExitCode, message);
  }

  private static Path nest(final Path root, final int levels) throws IOException {
    Path current = root;
    for (int level = 0; level < levels; level++) {
      current = current.resolve("level" + level);
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
      Assumptions.abort("Symbolic links cannot be created here: " + reason);
    }
  }

  private static String[] shellCommand(final String command) {
    final OS operatingSystem = OSUtils.getOS();
    if (operatingSystem == OS.WINDOWS) {
      return new String[] { "cmd", "/c", command };
    }
    return new String[] { "sh", "-c", command };
  }

  /**
   * A strategy that only exposes the helpers of its base class.
   */
  private static final class TestStrategy extends ManualInstallationStrategy {

    TestStrategy(final VLCInstaller installer) {
      super(installer);
    }

    TestStrategy(final VLCInstaller installer, final ProcessRunner processRunner) {
      super(installer, processRunner);
    }

    @Override
    public Optional<Path> getInstalledPath() {
      return Optional.empty();
    }

    @Override
    public Path execute(final Path archive) {
      return archive;
    }
  }
}

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link OSXInstallationStrategy} with {@code hdiutil} and {@code cp} simulated.
 */
final class OSXInstallationStrategyTest {

  private static final Predicate<String> COPY_FAILS = commandLine -> commandLine.startsWith("cp ");
  private static final String MOUNT_DIRECTORY = "vlc-mount";

  @TempDir
  private Path temp;

  private static boolean nothingFails(final String commandLine) {
    return false;
  }

  private static boolean attachFails(final String commandLine) {
    return commandLine.startsWith("hdiutil attach");
  }

  private static boolean everyDetachFails(final String commandLine) {
    return commandLine.startsWith("hdiutil detach");
  }

  private static boolean regularDetachFails(final String commandLine) {
    final boolean detach = commandLine.startsWith("hdiutil detach");
    final boolean forced = commandLine.contains("-force");
    return detach && !forced;
  }

  @Test
  void copiesTheApplicationOutOfTheDiskImage() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, OSXInstallationStrategyTest::nothingFails);
    final Path archive = this.createDiskImage("vlc-3.0.23.dmg");
    final Path libraryDirectory = strategy.execute(archive);

    final Path installDirectory = installer.getInstallDirectory();
    final Path expected = libraryDirectoryOf(installDirectory);
    final Path mountPoint = this.temp.resolve(MOUNT_DIRECTORY);
    final List<String> expectedCommands = expectedCommands(mountPoint, archive, installDirectory);
    final boolean archiveExists = Files.exists(archive);
    final boolean mountPointExists = Files.exists(mountPoint);
    final Optional<Path> installed = strategy.getInstalledPath();
    final Optional<Path> expectedInstallation = Optional.of(expected);
    assertEquals(expected, libraryDirectory);
    assertEquals(expectedCommands, commands);
    assertFalse(archiveExists);
    assertFalse(mountPointExists);
    assertEquals(expectedInstallation, installed);
  }

  @Test
  void detachesTheDiskImageWhenCopyingFails() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, COPY_FAILS);
    final Path archive = this.createDiskImage("vlc.dmg");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));

    final String message = thrown.getMessage();
    final Throwable[] suppressed = thrown.getSuppressed();
    final String lastCommand = commands.getLast();
    final boolean archiveExists = Files.exists(archive);
    final boolean mountPointExists = this.mountPointExists();
    final boolean copyFailed = message.startsWith("failed: cp -R");
    final boolean detachedLast = lastCommand.startsWith("hdiutil detach");
    assertTrue(copyFailed, message);
    assertEquals(0, suppressed.length);
    assertTrue(detachedLast, lastCommand);
    assertTrue(archiveExists, "the disk image is kept for another attempt");
    assertFalse(mountPointExists, "the detached mount point is cleaned up");
  }

  @Test
  void keepsTheCopyFailureWhenTheDiskImageCannotBeDetachedEither() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final Predicate<String> copyAndDetachFail = COPY_FAILS.or(OSXInstallationStrategyTest::everyDetachFails);
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, copyAndDetachFail);
    final Path archive = this.createDiskImage("vlc.dmg");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));

    final String message = thrown.getMessage();
    final Path mountPoint = this.temp.resolve(MOUNT_DIRECTORY);
    final boolean mountPointExists = Files.exists(mountPoint);
    final boolean copyFailed = message.startsWith("failed: cp -R");
    final boolean forcedDetachTried = commands.contains("hdiutil detach -force " + mountPoint);
    assertTrue(copyFailed, "the copy failure is reported, not the detach failure: " + message);
    assertSuppressedDetachFailure(thrown, mountPoint);
    assertTrue(forcedDetachTried, commands::toString);
    assertTrue(mountPointExists, "a mount point that may still be mounted must not be deleted");
  }

  @Test
  void forcesTheDetachWhenARegularDetachFails() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, OSXInstallationStrategyTest::regularDetachFails);
    final Path archive = this.createDiskImage("vlc.dmg");
    final Path libraryDirectory = strategy.execute(archive);
    final Path installDirectory = installer.getInstallDirectory();
    final Path expected = libraryDirectoryOf(installDirectory);
    final Path mountPoint = this.temp.resolve(MOUNT_DIRECTORY);
    final String lastCommand = commands.getLast();
    final boolean mountPointExists = Files.exists(mountPoint);
    assertEquals(expected, libraryDirectory);
    assertEquals("hdiutil detach -force " + mountPoint, lastCommand);
    assertFalse(mountPointExists);
  }

  @Test
  void neitherCopiesNorDetachesWhenAttachingFails() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, OSXInstallationStrategyTest::attachFails);
    final Path archive = this.createDiskImage("vlc.dmg");
    assertThrows(IOException.class, () -> strategy.execute(archive));
    final int commandCount = commands.size();
    assertEquals(1, commandCount);
  }

  @Test
  void keepsTheMountPointWhenDetachingFails() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, commands, OSXInstallationStrategyTest::everyDetachFails);
    final Path archive = this.createDiskImage("vlc.dmg");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final Path mountPoint = this.temp.resolve(MOUNT_DIRECTORY);
    final String message = thrown.getMessage();
    final Throwable[] suppressed = thrown.getSuppressed();
    final boolean mountPointExists = Files.exists(mountPoint);
    assertEquals("failed: hdiutil detach " + mountPoint, message);
    assertEquals(1, suppressed.length, "the failure of the forced detach is attached");
    assertTrue(mountPointExists, "A mount point that may still be mounted must not be deleted");
  }

  @Test
  void failsWhenTheApplicationHasNoLibrary() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final ProcessRunner emptyTools = (_, _) -> {};
    final OSXInstallationStrategy strategy = new OSXInstallationStrategy(installer, emptyTools);
    final Path archive = this.createDiskImage("vlc.dmg");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheLibrary = message.contains("libvlc.dylib");
    assertTrue(namesTheLibrary, message);
  }

  @Test
  void replacesAnEarlierInstallation() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final Path installDirectory = installer.getInstallDirectory();
    Files.createDirectories(installDirectory);
    final Path stale = installDirectory.resolve("stale");
    Files.createFile(stale);
    final List<String> ignoredCommands = new ArrayList<>();
    final OSXInstallationStrategy strategy = strategyWith(installer, ignoredCommands, OSXInstallationStrategyTest::nothingFails);
    final Path archive = this.createDiskImage("vlc.dmg");
    strategy.execute(archive);
    final boolean staleExists = Files.exists(stale);
    assertFalse(staleExists);
  }

  @Test
  void usesChildProcessesByDefault() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final OSXInstallationStrategy strategy = new OSXInstallationStrategy(installer);
    final VLCInstaller strategyInstaller = strategy.getInstaller();
    assertSame(installer, strategyInstaller);
    assertThrows(NullPointerException.class, () -> strategy.execute(null));
  }

  private static OSXInstallationStrategy strategyWith(
    final VLCInstaller installer,
    final List<String> commands,
    final Predicate<String> failing
  ) {
    final ProcessRunner tools = simulatedTools(commands, failing);
    return new OSXInstallationStrategy(installer, tools);
  }

  private static List<String> expectedCommands(final Path mountPoint, final Path archive, final Path installDirectory) {
    final Path mountedApp = mountPoint.resolve("VLC.app");
    final String attach = "hdiutil attach -nobrowse -readonly -mountpoint " + mountPoint + " " + archive;
    final String copy = "cp -R " + mountedApp + " " + installDirectory;
    final String detach = "hdiutil detach " + mountPoint;
    return List.of(attach, copy, detach);
  }

  private static void assertSuppressedDetachFailure(final IOException thrown, final Path mountPoint) {
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    final Throwable detachFailure = suppressed[0];
    final String detachMessage = detachFailure.getMessage();
    final Throwable[] forcedFailures = detachFailure.getSuppressed();
    assertEquals("failed: hdiutil detach " + mountPoint, detachMessage);
    assertEquals(1, forcedFailures.length, "the forced detach was tried as well");
  }

  private boolean mountPointExists() {
    final Path mountPoint = this.temp.resolve(MOUNT_DIRECTORY);
    return Files.exists(mountPoint);
  }

  private Path createDiskImage(final String name) throws IOException {
    final Path diskImage = this.temp.resolve(name);
    return Files.createFile(diskImage);
  }

  private static Path libraryDirectoryOf(final Path installDirectory) {
    final Path relativeLibraryDirectory = Path.of("VLC.app", "Contents", "MacOS", "lib");
    return installDirectory.resolve(relativeLibraryDirectory);
  }

  /**
   * Simulates {@code hdiutil} and {@code cp}: {@code cp} creates an application bundle with the library.
   *
   * @param commands receives every command line
   * @param failing  decides which command lines fail; a failure reads {@code failed: <command line>}
   * @return the runner
   */
  private static ProcessRunner simulatedTools(final List<String> commands, final Predicate<String> failing) {
    return (_, arguments) -> {
      final String commandLine = String.join(" ", arguments);
      commands.add(commandLine);
      final boolean fails = failing.test(commandLine);
      if (fails) {
        throw new IOException("failed: " + commandLine);
      }
      final String tool = arguments[0];
      final boolean copy = tool.equals("cp");
      if (copy) {
        simulateCopy(arguments);
      }
    };
  }

  private static void simulateCopy(final String[] arguments) throws IOException {
    final Path destination = Path.of(arguments[3]);
    final Path libraryDirectory = libraryDirectoryOf(destination);
    final Path library = libraryDirectory.resolve("libvlc.dylib");
    final Path coreLibrary = libraryDirectory.resolve("libvlccore.dylib");
    Files.createDirectories(libraryDirectory);
    Files.writeString(library, "structural API fixture, never loaded");
    Files.writeString(coreLibrary, "structural core fixture, never loaded");
  }
}

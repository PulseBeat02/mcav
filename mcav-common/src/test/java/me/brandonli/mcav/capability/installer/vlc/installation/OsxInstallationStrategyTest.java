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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import me.brandonli.mcav.testing.IoActions;
import me.brandonli.mcav.utils.FileOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OsxInstallationStrategyTest {

  private static final Predicate<String> COPY_FAILS = commandLine -> commandLine.startsWith("cp ");
  private static final String MOUNT_DIRECTORY = "vlc-mount";

  private static final String DISK_IMAGE_NAME = "vlc.dmg";
  private static final String VERSIONED_DISK_IMAGE_NAME = "vlc-3.0.24.dmg";

  private static final String APPLICATION_NAME = "VLC.app";
  private static final Path RELATIVE_LIBRARY_DIRECTORY = Path.of(APPLICATION_NAME, "Contents", "MacOS", "lib");
  private static final String LIBRARY_NAME = "libvlc.dylib";
  private static final String CORE_LIBRARY_NAME = "libvlccore.dylib";

  private static final String STALE_FILE_NAME = "stale";

  private static final String DETACH_COMMAND = "hdiutil detach";
  private static final String FORCED_DETACH_COMMAND_PREFIX = "hdiutil detach -force ";

  private static final String JAVA_BINARY_DIRECTORY = "bin";
  private static final String WINDOWS_JAVA_NAME = "java.exe";
  private static final String JAVA_NAME = "java";

  private static final String MARKER_FILE_NAME = "child.txt";
  private static final String MARKER_TEXT = "child ran";

  @TempDir
  private Path temporaryDirectory;

  private static boolean nothingFails(final String commandLine) {
    return false;
  }

  private static boolean attachFails(final String commandLine) {
    return commandLine.startsWith("hdiutil attach");
  }

  private static boolean everyDetachFails(final String commandLine) {
    return commandLine.startsWith(DETACH_COMMAND);
  }

  private static boolean regularDetachFails(final String commandLine) {
    final boolean detach = commandLine.startsWith(DETACH_COMMAND);
    final boolean forced = commandLine.contains("-force");
    return detach && !forced;
  }

  @Test
  void shouldCopyTheApplicationOutOfTheDiskImage() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, OsxInstallationStrategyTest::nothingFails);
    final Path archive = this.createDiskImage(VERSIONED_DISK_IMAGE_NAME);
    final Path libraryDirectory = strategy.execute(archive);

    final Path installDirectory = installer.getInstallDirectory();
    final Path expected = libraryDirectoryOf(installDirectory);
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
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
  void shouldDetachTheDiskImageWhenCopyingFails() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, COPY_FAILS);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));

    final String message = thrown.getMessage();
    final Throwable[] suppressed = thrown.getSuppressed();
    final String lastCommand = commands.getLast();
    final boolean archiveExists = Files.exists(archive);
    final boolean mountPointExists = this.mountPointExists();
    final boolean copyFailed = message.startsWith("Command cp -R");
    final boolean detachedLast = lastCommand.startsWith(DETACH_COMMAND);
    assertTrue(copyFailed, message);
    assertEquals(0, suppressed.length);
    assertTrue(detachedLast, lastCommand);
    assertTrue(archiveExists, "the disk image is kept for another attempt");
    assertFalse(mountPointExists, "the detached mount point is cleaned up");
  }

  @Test
  void shouldKeepTheCopyFailureWhenTheDiskImageCannotBeDetachedEither() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final Predicate<String> copyAndDetachFail = COPY_FAILS.or(OsxInstallationStrategyTest::everyDetachFails);
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, copyAndDetachFail);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));

    final String message = thrown.getMessage();
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
    final boolean mountPointExists = Files.exists(mountPoint);
    final boolean copyFailed = message.startsWith("Command cp -R");
    final boolean forcedDetachTried = commands.contains(FORCED_DETACH_COMMAND_PREFIX + mountPoint);
    assertTrue(copyFailed, "the copy failure is reported, not the detach failure: " + message);
    assertSuppressedDetachFailure(thrown, mountPoint);
    assertTrue(forcedDetachTried, commands::toString);
    assertTrue(mountPointExists, "a mount point that may still be mounted must not be deleted");
  }

  @Test
  void shouldForceTheDetachWhenARegularDetachFails() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, OsxInstallationStrategyTest::regularDetachFails);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final Path libraryDirectory = strategy.execute(archive);
    final Path installDirectory = installer.getInstallDirectory();
    final Path expected = libraryDirectoryOf(installDirectory);
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
    final String lastCommand = commands.getLast();
    final boolean mountPointExists = Files.exists(mountPoint);
    assertEquals(expected, libraryDirectory);
    assertEquals(FORCED_DETACH_COMMAND_PREFIX + mountPoint, lastCommand);
    assertFalse(mountPointExists);
  }

  @Test
  void shouldNeitherCopyNorDetachWhenAttachingFails() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, OsxInstallationStrategyTest::attachFails);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    assertThrows(InstallationException.class, () -> strategy.execute(archive));

    final int commandCount = commands.size();
    assertEquals(1, commandCount);
  }

  @Test
  void shouldKeepTheMountPointWhenDetachingFails() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, OsxInstallationStrategyTest::everyDetachFails);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
    final String message = thrown.getMessage();
    final Throwable[] suppressed = thrown.getSuppressed();
    final boolean mountPointExists = Files.exists(mountPoint);
    final String expected = "Command hdiutil detach %s failed!".formatted(mountPoint);
    assertEquals(expected, message);
    assertEquals(1, suppressed.length, "the failure of the forced detach is attached");
    assertTrue(mountPointExists, "A mount point that may still be mounted must not be deleted");
  }

  @Test
  void shouldFailWhenTheApplicationHasNoLibrary() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final ProcessRunner emptyTools = (_, _) -> {};
    final OsxInstallationStrategy strategy = new OsxInstallationStrategy(installer, emptyTools);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheLibrary = message.contains(LIBRARY_NAME);
    assertTrue(namesTheLibrary, message);
  }

  @Test
  void shouldFailWhenTheMountPointCannotBeCreated() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
    Files.createFile(mountPoint);
    final List<String> commands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, commands, OsxInstallationStrategyTest::nothingFails);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    final FileOperationException thrown = assertThrows(FileOperationException.class, () -> strategy.execute(archive));
    final Throwable cause = thrown.getCause();
    assertInstanceOf(FileAlreadyExistsException.class, cause);
    assertEquals(List.of(), commands);
  }

  @Test
  void shouldReplaceAnEarlierInstallation() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path installDirectory = installer.getInstallDirectory();
    Files.createDirectories(installDirectory);
    final Path stale = installDirectory.resolve(STALE_FILE_NAME);
    Files.createFile(stale);
    final List<String> ignoredCommands = new ArrayList<>();
    final OsxInstallationStrategy strategy = strategyWith(installer, ignoredCommands, OsxInstallationStrategyTest::nothingFails);
    final Path archive = this.createDiskImage(DISK_IMAGE_NAME);
    strategy.execute(archive);
    final boolean staleExists = Files.exists(stale);
    assertFalse(staleExists);
  }

  @Test
  void shouldUseChildProcessesByDefault() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final OsxInstallationStrategy strategy = new OsxInstallationStrategy(installer);
    final VlcInstaller strategyInstaller = strategy.getInstaller();
    assertSame(installer, strategyInstaller);

    final Path javaFolder = Path.of(System.getProperty("java.home"), JAVA_BINARY_DIRECTORY);
    final Path windowsJava = javaFolder.resolve(WINDOWS_JAVA_NAME);
    final Path java = Files.isRegularFile(windowsJava) ? windowsJava : javaFolder.resolve(JAVA_NAME);
    final Path marker = this.temporaryDirectory.resolve(MARKER_FILE_NAME);
    strategy.runProcess(
      this.temporaryDirectory,
      java.toString(),
      "-cp",
      System.getProperty("java.class.path"),
      ChildProbe.class.getName(),
      marker.toString()
    );
    final boolean markerWritten = Files.isRegularFile(marker);
    assertTrue(markerWritten, "the default runner executes its child process");

    final String markerText = Files.readString(marker);
    assertEquals(MARKER_TEXT, markerText);
  }

  @Test
  void shouldRejectANullArchive() {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final OsxInstallationStrategy strategy = new OsxInstallationStrategy(installer);
    assertThrows(NullPointerException.class, () -> strategy.execute(null));
  }

  private static OsxInstallationStrategy strategyWith(
    final VlcInstaller installer,
    final List<String> commands,
    final Predicate<String> failing
  ) {
    final ProcessRunner tools = simulatedTools(commands, failing);
    return new OsxInstallationStrategy(installer, tools);
  }

  private static List<String> expectedCommands(final Path mountPoint, final Path archive, final Path installDirectory) {
    final Path mountedApp = mountPoint.resolve(APPLICATION_NAME);
    final String attach = "hdiutil attach -nobrowse -readonly -mountpoint " + mountPoint + " " + archive;
    final String copy = "cp -R " + mountedApp + " " + installDirectory;
    final String detach = "hdiutil detach " + mountPoint;
    return List.of(attach, copy, detach);
  }

  private static void assertSuppressedDetachFailure(final InstallationException thrown, final Path mountPoint) {
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);

    final Throwable detachFailure = suppressed[0];
    final String detachMessage = detachFailure.getMessage();
    final Throwable[] forcedFailures = detachFailure.getSuppressed();
    final String expectedDetachMessage = "Command hdiutil detach %s failed!".formatted(mountPoint);
    assertEquals(expectedDetachMessage, detachMessage);
    assertEquals(1, forcedFailures.length, "the forced detach was tried as well");
  }

  private boolean mountPointExists() {
    final Path mountPoint = this.temporaryDirectory.resolve(MOUNT_DIRECTORY);
    return Files.exists(mountPoint);
  }

  private Path createDiskImage(final String name) throws IOException {
    final Path diskImage = this.temporaryDirectory.resolve(name);
    return Files.createFile(diskImage);
  }

  private static Path libraryDirectoryOf(final Path installDirectory) {
    return installDirectory.resolve(RELATIVE_LIBRARY_DIRECTORY);
  }

  private static ProcessRunner simulatedTools(final List<String> commands, final Predicate<String> failing) {
    return (_, arguments) -> IoActions.runUnchecked(() -> runSimulatedTool(commands, failing, arguments));
  }

  private static void runSimulatedTool(final List<String> commands, final Predicate<String> failing, final String[] arguments)
    throws IOException {
    final String commandLine = String.join(" ", arguments);
    commands.add(commandLine);
    final boolean fails = failing.test(commandLine);
    if (fails) {
      final String message = "Command %s failed!".formatted(commandLine);
      throw new InstallationException(message);
    }

    final String tool = arguments[0];
    final boolean copy = tool.equals("cp");
    if (copy) {
      simulateCopy(arguments);
    }
  }

  private static void simulateCopy(final String[] arguments) throws IOException {
    final Path destination = Path.of(arguments[3]);
    final Path libraryDirectory = libraryDirectoryOf(destination);
    final Path library = libraryDirectory.resolve(LIBRARY_NAME);
    final Path coreLibrary = libraryDirectory.resolve(CORE_LIBRARY_NAME);
    Files.createDirectories(libraryDirectory);
    Files.writeString(library, "structural API fixture, never loaded");
    Files.writeString(coreLibrary, "structural core fixture, never loaded");
  }

  public static final class ChildProbe {

    private ChildProbe() {}

    public static void main(final String[] arguments) throws IOException {
      Files.writeString(Path.of(arguments[0]), MARKER_TEXT);
    }
  }
}

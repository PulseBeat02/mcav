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
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import me.brandonli.mcav.testing.IoActions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LinuxInstallationStrategyTest {

  private static final String EXTRACTED_DIRECTORY = "squashfs-root";
  private static final String WORKING_DIRECTORY_REQUIRED = "The AppImage is extracted in the directory of the archive!";

  private static final String APP_IMAGE_FILE_NAME = "VLC.AppImage";
  private static final String APP_RUN_FILE_NAME = "AppRun";

  private static final String PROGRAMS_DIRECTORY = "usr";
  private static final String LIBRARY_DIRECTORY = "lib";
  private static final String VLC_DIRECTORY = "vlc";
  private static final String PLUGINS_DIRECTORY = "plugins";

  private static final String LIBRARY_FILE_NAME = "libvlc.so";
  private static final String VERSIONED_LIBRARY_FILE_NAME = "libvlc.so.5";
  private static final String CORE_LIBRARY_FILE_NAME = "libvlccore.so";
  private static final String VERSIONED_CORE_LIBRARY_FILE_NAME = "libvlccore.so.9.0.1";

  private static final String STALE_FILE_NAME = "stale";
  private static final String OLD_INSTALL_FILE_NAME = "old";

  @TempDir
  private Path temporaryDirectory;

  @Test
  void shouldExtractTheAppImageAndReturnTheCoreLibraryDirectory() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final List<String> commands = new ArrayList<>();
    final List<Path> workingDirectories = new ArrayList<>();
    final ProcessRunner extractor = recordingExtractor(commands, workingDirectories);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    final Path libraryDirectory = strategy.execute(archive);

    final Path installDirectory = installer.getInstallDirectory();
    final Path relativeLibraryDirectory = Path.of(PROGRAMS_DIRECTORY, LIBRARY_DIRECTORY);
    final Path expected = installDirectory.resolve(relativeLibraryDirectory);
    final Path absoluteArchive = archive.toAbsolutePath();
    final String expectedCommand = "%s --appimage-extract".formatted(absoluteArchive);
    final List<String> expectedCommands = List.of(expectedCommand);
    final List<Path> expectedWorkingDirectories = List.of(this.temporaryDirectory);
    assertEquals(expected, libraryDirectory);
    assertEquals(expectedCommands, commands);
    assertEquals(expectedWorkingDirectories, workingDirectories);

    this.assertArchiveAndExtractionAreGone(archive);

    final Optional<Path> installed = strategy.getInstalledPath();
    final Optional<Path> expectedInstallation = Optional.of(expected);
    assertEquals(expectedInstallation, installed);
  }

  @Test
  void shouldRemoveLeftoversOfAnInterruptedExtractionFirst() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path leftover = this.temporaryDirectory.resolve(EXTRACTED_DIRECTORY);
    Files.createDirectories(leftover);
    final Path staleFile = createFile(leftover, STALE_FILE_NAME);
    final Path installDirectory = installer.getInstallDirectory();
    Files.createDirectories(installDirectory);
    final Path staleInstall = createFile(installDirectory, OLD_INSTALL_FILE_NAME);
    final ProcessRunner extractor = leftoverCheckingExtractor(staleFile);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    strategy.execute(archive);
    final boolean staleInstallExists = Files.exists(staleInstall);
    assertFalse(staleInstallExists);
  }

  @Test
  void shouldFailWhenTheExtractionProducesNothing() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, (_, _) -> {});
    final Path archive = this.createAppImage();
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheDirectory = message.contains(EXTRACTED_DIRECTORY);
    assertTrue(namesTheDirectory, message);
  }

  @Test
  void shouldFailWhenTheAppImageHasNoCoreLibrary() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final ProcessRunner extractor = (workingDirectory, _) -> IoActions.runUnchecked(() -> this.extractWithoutCoreLibrary(workingDirectory));
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheLibrary = message.contains(CORE_LIBRARY_FILE_NAME);
    assertTrue(namesTheLibrary, message);
  }

  private void extractWithoutCoreLibrary(final @Nullable Path workingDirectory) throws IOException {
    final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
    final Path extracted = directory.resolve(EXTRACTED_DIRECTORY);
    Files.createDirectories(extracted);
    createFile(extracted, APP_RUN_FILE_NAME);
  }

  @Test
  void shouldPropagateExtractionFailures() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final InstallationException failure = new InstallationException("not an AppImage!");
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, (_, _) -> this.failToExtract(failure));
    final Path archive = this.createAppImage();
    final InstallationException thrown = assertThrows(InstallationException.class, () -> strategy.execute(archive));
    assertSame(failure, thrown);
  }

  private void failToExtract(final InstallationException failure) {
    throw failure;
  }

  @Test
  void shouldFindNoInstallationBeforeTheFirstRun() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer);
    final Optional<Path> installed = strategy.getInstalledPath();
    final boolean nothingInstalled = installed.isEmpty();
    assertTrue(nothingInstalled);
  }

  @Test
  void shouldRejectANullArchive() {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer);
    assertThrows(NullPointerException.class, () -> strategy.execute(null));
  }

  private static ProcessRunner recordingExtractor(final List<String> commands, final List<Path> workingDirectories) {
    return (workingDirectory, arguments) ->
      IoActions.runUnchecked(() -> extractRecording(commands, workingDirectories, workingDirectory, arguments));
  }

  private static void extractRecording(
    final List<String> commands,
    final List<Path> workingDirectories,
    final @Nullable Path workingDirectory,
    final String[] arguments
  ) throws IOException {
    final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
    workingDirectories.add(directory);
    final String commandLine = String.join(" ", arguments);
    commands.add(commandLine);
    final Path relativeLibraryDirectory = Path.of(EXTRACTED_DIRECTORY, PROGRAMS_DIRECTORY, LIBRARY_DIRECTORY);
    final Path libraryDirectory = directory.resolve(relativeLibraryDirectory);
    final Path relativePlugins = Path.of(VLC_DIRECTORY, PLUGINS_DIRECTORY);
    final Path plugins = libraryDirectory.resolve(relativePlugins);
    Files.createDirectories(plugins);
    createFile(libraryDirectory, VERSIONED_LIBRARY_FILE_NAME);
    createFile(libraryDirectory, VERSIONED_CORE_LIBRARY_FILE_NAME);
  }

  private static ProcessRunner leftoverCheckingExtractor(final Path staleFile) {
    return (workingDirectory, _) -> IoActions.runUnchecked(() -> extractAfterCheckingTheLeftover(staleFile, workingDirectory));
  }

  private static void extractAfterCheckingTheLeftover(final Path staleFile, final @Nullable Path workingDirectory) throws IOException {
    final boolean leftoverStillThere = Files.exists(staleFile);
    assertFalse(leftoverStillThere);

    final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
    final Path extracted = directory.resolve(EXTRACTED_DIRECTORY);
    final Path libraryDirectory = extracted.resolve(LIBRARY_DIRECTORY);
    Files.createDirectories(libraryDirectory);
    createFile(libraryDirectory, CORE_LIBRARY_FILE_NAME);
    createFile(libraryDirectory, LIBRARY_FILE_NAME);
  }

  private void assertArchiveAndExtractionAreGone(final Path archive) {
    final Path extracted = this.temporaryDirectory.resolve(EXTRACTED_DIRECTORY);
    final boolean archiveExists = Files.exists(archive);
    final boolean extractedExists = Files.exists(extracted);
    assertFalse(archiveExists);
    assertFalse(extractedExists);
  }

  private Path createAppImage() throws IOException {
    return createFile(this.temporaryDirectory, APP_IMAGE_FILE_NAME);
  }

  private static Path createFile(final Path directory, final String name) throws IOException {
    final Path file = directory.resolve(name);
    return Files.writeString(file, "structural fixture, never loaded as native code");
  }
}

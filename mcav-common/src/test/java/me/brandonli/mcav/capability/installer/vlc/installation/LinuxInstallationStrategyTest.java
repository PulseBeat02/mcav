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
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link LinuxInstallationStrategy} with a fake AppImage whose extraction is simulated.
 */
final class LinuxInstallationStrategyTest {

  private static final String EXTRACTED_DIRECTORY = "squashfs-root";
  private static final String WORKING_DIRECTORY_REQUIRED = "The AppImage is extracted in the directory of the archive";

  @TempDir
  private Path temp;

  @Test
  void extractsTheAppImageAndReturnsTheCoreLibraryDirectory() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final List<String> commands = new ArrayList<>();
    final List<Path> workingDirectories = new ArrayList<>();
    final ProcessRunner extractor = recordingExtractor(commands, workingDirectories);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    final Path libraryDirectory = strategy.execute(archive);

    final Path installDirectory = installer.getInstallDirectory();
    final Path relativeLibraryDirectory = Path.of("usr", "lib");
    final Path expected = installDirectory.resolve(relativeLibraryDirectory);
    final Path absoluteArchive = archive.toAbsolutePath();
    final List<String> expectedCommands = List.of(absoluteArchive + " --appimage-extract");
    final List<Path> expectedWorkingDirectories = List.of(this.temp);
    assertEquals(expected, libraryDirectory);
    assertEquals(expectedCommands, commands);
    assertEquals(expectedWorkingDirectories, workingDirectories);
    this.assertArchiveAndExtractionAreGone(archive);

    final Optional<Path> installed = strategy.getInstalledPath();
    final Optional<Path> expectedInstallation = Optional.of(expected);
    assertEquals(expectedInstallation, installed);
  }

  @Test
  void removesLeftoversOfAnInterruptedExtractionFirst() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final Path leftover = this.temp.resolve(EXTRACTED_DIRECTORY);
    Files.createDirectories(leftover);
    final Path staleFile = createFile(leftover, "stale");
    final Path installDirectory = installer.getInstallDirectory();
    Files.createDirectories(installDirectory);
    final Path staleInstall = createFile(installDirectory, "old");
    final ProcessRunner extractor = leftoverCheckingExtractor(staleFile);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    strategy.execute(archive);
    final boolean staleInstallExists = Files.exists(staleInstall);
    assertFalse(staleInstallExists);
  }

  @Test
  void failsWhenTheExtractionProducesNothing() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, (_, _) -> {});
    final Path archive = this.createAppImage();
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheDirectory = message.contains(EXTRACTED_DIRECTORY);
    assertTrue(namesTheDirectory, message);
  }

  @Test
  void failsWhenTheAppImageHasNoCoreLibrary() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final ProcessRunner extractor = (workingDirectory, _) -> {
      final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
      final Path extracted = directory.resolve(EXTRACTED_DIRECTORY);
      Files.createDirectories(extracted);
      createFile(extracted, "AppRun");
    };
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, extractor);
    final Path archive = this.createAppImage();
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheLibrary = message.contains("libvlccore.so");
    assertTrue(namesTheLibrary, message);
  }

  @Test
  void propagatesExtractionFailures() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final IOException failure = new IOException("not an AppImage");
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer, (_, _) -> {
      throw failure;
    });
    final Path archive = this.createAppImage();
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    assertSame(failure, thrown);
  }

  @Test
  void findsNoInstallationBeforeTheFirstRun() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer);
    final Optional<Path> installed = strategy.getInstalledPath();
    final boolean nothingInstalled = installed.isEmpty();
    assertTrue(nothingInstalled);
  }

  @Test
  void rejectsANullArchive() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final LinuxInstallationStrategy strategy = new LinuxInstallationStrategy(installer);
    assertThrows(NullPointerException.class, () -> strategy.execute(null));
  }

  private static ProcessRunner recordingExtractor(final List<String> commands, final List<Path> workingDirectories) {
    return (workingDirectory, arguments) -> {
      final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
      workingDirectories.add(directory);
      final String commandLine = String.join(" ", arguments);
      commands.add(commandLine);
      final Path relativeLibraryDirectory = Path.of(EXTRACTED_DIRECTORY, "usr", "lib");
      final Path libraryDirectory = directory.resolve(relativeLibraryDirectory);
      final Path relativePlugins = Path.of("vlc", "plugins");
      final Path plugins = libraryDirectory.resolve(relativePlugins);
      Files.createDirectories(plugins);
      createFile(libraryDirectory, "libvlc.so.5");
      createFile(libraryDirectory, "libvlccore.so.9.0.1");
    };
  }

  /**
   * Creates an extractor that fails the test unless the leftover of an earlier extraction was deleted before it runs.
   */
  private static ProcessRunner leftoverCheckingExtractor(final Path staleFile) {
    return (workingDirectory, _) -> {
      final boolean leftoverStillThere = Files.exists(staleFile);
      assertFalse(leftoverStillThere);
      final Path directory = Objects.requireNonNull(workingDirectory, WORKING_DIRECTORY_REQUIRED);
      final Path extracted = directory.resolve(EXTRACTED_DIRECTORY);
      final Path libraryDirectory = extracted.resolve("lib");
      Files.createDirectories(libraryDirectory);
      createFile(libraryDirectory, "libvlccore.so");
    };
  }

  private void assertArchiveAndExtractionAreGone(final Path archive) {
    final Path extracted = this.temp.resolve(EXTRACTED_DIRECTORY);
    final boolean archiveExists = Files.exists(archive);
    final boolean extractedExists = Files.exists(extracted);
    assertFalse(archiveExists);
    assertFalse(extractedExists);
  }

  private Path createAppImage() throws IOException {
    return createFile(this.temp, "VLC.AppImage");
  }

  private static Path createFile(final Path directory, final String name) throws IOException {
    final Path file = directory.resolve(name);
    return Files.createFile(file);
  }
}

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link WinInstallationStrategy} with small zips shaped like the portable VLC zip.
 */
final class WinInstallationStrategyTest {

  private static final String TEMPORARY_DIRECTORY = "vlc-extract";

  @TempDir
  private Path temp;

  @Test
  void extractsTheInnerDirectoryIntoTheInstallationDirectory() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path archive =
      this.createZip(
          "vlc-3.0.23-win64.zip",
          "vlc-3.0.23/libvlc.dll",
          "vlc-3.0.23/libvlccore.dll",
          "vlc-3.0.23/plugins/codec/libavcodec_plugin.dll"
        );
    final Path installed = strategy.execute(archive);
    final Path installDirectory = installer.getInstallDirectory();
    final boolean hasLibrary = containsFile(installed, "libvlc.dll");
    final boolean hasCore = containsFile(installed, "libvlccore.dll");
    final boolean hasPlugin = containsFile(installed, "plugins", "codec", "libavcodec_plugin.dll");
    final boolean archiveExists = Files.exists(archive);
    final boolean temporaryExists = this.temporaryDirectoryExists();
    assertEquals(installDirectory, installed);
    assertTrue(hasLibrary);
    assertTrue(hasCore);
    assertTrue(hasPlugin);
    assertFalse(archiveExists);
    assertFalse(temporaryExists);
  }

  @Test
  void doesNotPromoteStaleFilesFromAnEarlierExtraction() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path temporary = this.temp.resolve(TEMPORARY_DIRECTORY);
    final Path previousRoot = temporary.resolve("vlc-3.0.23");
    Files.createDirectories(previousRoot);
    final Path stale = previousRoot.resolve("stale.dll");
    Files.writeString(stale, "left over from an interrupted extraction");
    final Path archive = this.createZip("vlc.zip", "vlc-3.0.23/libvlc.dll", "vlc-3.0.23/libvlccore.dll");
    final Path installed = strategy.execute(archive);
    final Path relocatedStale = installed.resolve("stale.dll");
    final boolean copied = Files.exists(relocatedStale);
    assertFalse(copied, "only files from the new archive may enter the installation");
  }

  @Test
  void reportsTheInstallationOnlyOnceItExists() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Optional<Path> before = strategy.getInstalledPath();
    final Path archive = this.createZip("vlc.zip", "vlc-3.0.23/libvlc.dll", "vlc-3.0.23/libvlccore.dll");
    final Path installed = strategy.execute(archive);
    final Optional<Path> after = strategy.getInstalledPath();
    final Optional<Path> expected = Optional.of(installed);
    final boolean nothingBefore = before.isEmpty();
    assertTrue(nothingBefore);
    assertEquals(expected, after);
  }

  @Test
  void replacesAnEarlierInstallation() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path installDirectory = installer.getInstallDirectory();
    Files.createDirectories(installDirectory);
    final Path stale = installDirectory.resolve("stale.dll");
    Files.createFile(stale);
    final Path archive = this.createZip("vlc.zip", "vlc-3.0.23/libvlc.dll", "vlc-3.0.23/libvlccore.dll");
    strategy.execute(archive);
    final boolean staleExists = Files.exists(stale);
    final boolean libraryExists = containsFile(installDirectory, "libvlc.dll");
    assertFalse(staleExists);
    assertTrue(libraryExists);
  }

  @Test
  void failsAndCleansUpWhenTheZipHasNoLibrary() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path archive = this.createZip("vlc.zip", "vlc-3.0.23/readme.txt");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final String message = thrown.getMessage();
    final boolean namesTheLibrary = message.contains("libvlc.dll");
    final boolean temporaryExists = this.temporaryDirectoryExists();
    final Path installDirectory = installer.getInstallDirectory();
    final boolean installExists = Files.exists(installDirectory);
    assertTrue(namesTheLibrary, message);
    assertFalse(temporaryExists);
    assertFalse(installExists);
  }

  @Test
  void reportsAFileThatIsNotAZipAndCleansUp() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path archive = this.temp.resolve("vlc.zip");
    Files.writeString(archive, "this is not a zip", StandardCharsets.US_ASCII);
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final Throwable cause = thrown.getCause();
    final UncheckedIOException unzipFailure = assertInstanceOf(UncheckedIOException.class, cause);
    final Throwable zipFailure = unzipFailure.getCause();
    final boolean temporaryExists = this.temporaryDirectoryExists();
    assertInstanceOf(ZipException.class, zipFailure, "the file is rejected as no zip, not read as an empty one");
    assertFalse(temporaryExists);
  }

  @Test
  void reportsUnsafeZipEntriesAsAnInstallationFailure() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    final Path archive = this.createZip("vlc.zip", "../escaped.dll");
    final IOException thrown = assertThrows(IOException.class, () -> strategy.execute(archive));
    final Throwable cause = thrown.getCause();
    final Path escaped = this.temp.resolve("escaped.dll");
    final boolean escapedExists = Files.exists(escaped);
    final boolean temporaryExists = this.temporaryDirectoryExists();
    assertInstanceOf(ZipEntryIntegrityException.class, cause);
    assertFalse(escapedExists);
    assertFalse(temporaryExists);
  }

  @Test
  void rejectsANullArchive() {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final WinInstallationStrategy strategy = new WinInstallationStrategy(installer);
    assertThrows(NullPointerException.class, () -> strategy.execute(null));
  }

  private boolean temporaryDirectoryExists() {
    final Path temporary = this.temp.resolve(TEMPORARY_DIRECTORY);
    return Files.exists(temporary);
  }

  private static boolean containsFile(final Path directory, final String first, final String... more) {
    final Path relative = Path.of(first, more);
    final Path file = directory.resolve(relative);
    return Files.isRegularFile(file);
  }

  private Path createZip(final String name, final String... entries) throws IOException {
    final Path zip = this.temp.resolve(name);
    try (final OutputStream file = Files.newOutputStream(zip); final ZipOutputStream zipOutput = new ZipOutputStream(file)) {
      for (final String entry : entries) {
        final ZipEntry zipEntry = new ZipEntry(entry);
        zipOutput.putNextEntry(zipEntry);
        final byte[] content = entry.getBytes(StandardCharsets.UTF_8);
        zipOutput.write(content);
        zipOutput.closeEntry();
      }
    }
    return zip;
  }
}

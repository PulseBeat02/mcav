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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Validates structure only: fixture libraries are nonempty markers and are never passed to a native loader. */
final class VLCInstallationValidationTest {

  @TempDir
  private Path temp;

  private enum Platform {
    WINDOWS,
    MAC,
    LINUX;

    String mainLibrary() {
      return switch (this) {
        case WINDOWS -> "libvlc.dll";
        case MAC -> "libvlc.dylib";
        case LINUX -> "libvlc.so.5.6.1";
      };
    }

    String coreLibrary() {
      return switch (this) {
        case WINDOWS -> "libvlccore.dll";
        case MAC -> "libvlccore.dylib";
        case LINUX -> "libvlccore.so.9.0.1";
      };
    }

    Path relativeDirectory() {
      return switch (this) {
        case WINDOWS -> Path.of("");
        case MAC -> Path.of("VLC.app", "Contents", "MacOS", "lib");
        case LINUX -> Path.of("usr", "lib", "i386-linux-gnu");
      };
    }

    InstallationStrategy strategy(final VLCInstaller installer, final ProcessRunner tools) {
      return switch (this) {
        case WINDOWS -> new WinInstallationStrategy(installer);
        case MAC -> new OSXInstallationStrategy(installer, tools);
        case LINUX -> new LinuxInstallationStrategy(installer, tools);
      };
    }
  }

  private enum Defect {
    MISSING_MAIN,
    MISSING_CORE,
    EMPTY_MAIN,
    EMPTY_CORE,
    DIFFERENT_DIRECTORIES,
    DIRECTORY_MAIN,
    DIRECTORY_CORE,
  }

  private static void writePair(final Path directory, final Platform platform) throws IOException {
    Files.createDirectories(directory);
    final Path main = directory.resolve(platform.mainLibrary());
    final Path core = directory.resolve(platform.coreLibrary());
    Files.writeString(main, "structural API fixture, never loaded");
    Files.writeString(core, "structural core fixture, never loaded");
  }

  private static void breakPair(final Path directory, final Platform platform, final Defect defect) throws IOException {
    final Path main = directory.resolve(platform.mainLibrary());
    final Path core = directory.resolve(platform.coreLibrary());
    switch (defect) {
      case MISSING_MAIN -> Files.delete(main);
      case MISSING_CORE -> Files.delete(core);
      case EMPTY_MAIN -> Files.writeString(main, "");
      case EMPTY_CORE -> Files.writeString(core, "");
      case DIFFERENT_DIRECTORIES -> {
        final Path other = directory.resolve("other");
        Files.createDirectories(other);
        Files.move(core, other.resolve(platform.coreLibrary()));
      }
      case DIRECTORY_MAIN -> {
        Files.delete(main);
        Files.createDirectory(main);
      }
      case DIRECTORY_CORE -> {
        Files.delete(core);
        Files.createDirectory(core);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void cachedInstallationsNeedBothNonemptyFilesInTheSameDirectory(final Platform platform) throws IOException {
    for (final Defect defect : Defect.values()) {
      final Path folder = this.temp.resolve(defect.name());
      final VLCInstaller installer = VLCInstaller.create(folder);
      final InstallationStrategy strategy = platform.strategy(installer, (_, _) -> {});
      final Path directory = installer.getInstallDirectory().resolve(platform.relativeDirectory());
      writePair(directory, platform);
      breakPair(directory, platform, defect);
      final Optional<Path> found = strategy.getInstalledPath();
      final boolean absent = found.isEmpty();
      assertTrue(absent, platform + " " + defect + " cannot be accepted as an installation");
    }
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void findsACompletePairBeyondAnIncompleteCandidate(final Platform platform) throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final Path install = installer.getInstallDirectory();
    final Path main = install.resolve(platform.mainLibrary());
    Files.createDirectories(install);
    Files.writeString(main, "orphan library marker");
    final Path complete = install.resolve("complete");
    writePair(complete, platform);
    final InstallationStrategy strategy = platform.strategy(installer, (_, _) -> {});
    final Optional<Path> found = strategy.getInstalledPath();
    final Optional<Path> expected = Optional.of(complete);
    assertEquals(expected, found);
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void extractedInstallationsRejectMissingOrEmptyCounterparts(final Platform platform) throws IOException {
    for (final Defect defect : Defect.values()) {
      final Path folder = this.temp.resolve(defect.name());
      Files.createDirectories(folder);
      final VLCInstaller installer = VLCInstaller.create(folder);
      final ProcessRunner tools = (workingDirectory, arguments) -> {
        if (platform == Platform.LINUX) {
          final Path extracted = folder.resolve("squashfs-root").resolve(platform.relativeDirectory());
          writePair(extracted, platform);
          breakPair(extracted, platform, defect);
        } else if (arguments[0].equals("cp")) {
          final Path extracted = installer.getInstallDirectory().resolve(platform.relativeDirectory());
          writePair(extracted, platform);
          breakPair(extracted, platform, defect);
        }
      };
      final InstallationStrategy strategy = platform.strategy(installer, tools);
      final Path archive = folder.resolve("fixture.archive");
      if (platform == Platform.WINDOWS) {
        writeWindowsArchive(archive, platform, defect);
      } else {
        Files.writeString(archive, "simulated archive, never executed");
      }
      assertThrows(IOException.class, () -> strategy.execute(archive), platform + " " + defect);
      final Optional<Path> cached = strategy.getInstalledPath();
      final boolean accepted = cached.isPresent();
      assertFalse(accepted, "a failed extraction must not become an accepted cached installation");
    }
  }

  private static void writeWindowsArchive(final Path archive, final Platform platform, final Defect defect) throws IOException {
    try (final OutputStream file = Files.newOutputStream(archive); final ZipOutputStream zip = new ZipOutputStream(file)) {
      if (defect != Defect.MISSING_MAIN) {
        final String suffix = defect == Defect.DIRECTORY_MAIN ? "/" : "";
        writeEntry(zip, "vlc-3.0.23/" + platform.mainLibrary() + suffix, defect == Defect.EMPTY_MAIN || defect == Defect.DIRECTORY_MAIN);
      }
      if (defect != Defect.MISSING_CORE) {
        final String prefix = defect == Defect.DIFFERENT_DIRECTORIES ? "vlc-3.0.23/other/" : "vlc-3.0.23/";
        final String suffix = defect == Defect.DIRECTORY_CORE ? "/" : "";
        writeEntry(zip, prefix + platform.coreLibrary() + suffix, defect == Defect.EMPTY_CORE || defect == Defect.DIRECTORY_CORE);
      }
    }
  }

  private static void writeEntry(final ZipOutputStream zip, final String name, final boolean empty) throws IOException {
    final ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);
    if (!empty) {
      final byte[] content = "structural library marker, never loaded".getBytes(StandardCharsets.UTF_8);
      zip.write(content);
    }
    zip.closeEntry();
  }

  @Test
  void followsLibrarySymlinksAndRejectsBrokenOrEmptyTargets() throws IOException {
    final VLCInstaller installer = VLCInstaller.create(this.temp);
    final Path directory = installer.getInstallDirectory();
    Files.createDirectories(directory);
    final Path mainTarget = directory.resolve("libvlc.5.dylib");
    final Path coreTarget = directory.resolve("libvlccore.9.dylib");
    Files.writeString(mainTarget, "API target marker");
    Files.writeString(coreTarget, "core target marker");
    try {
      Files.createSymbolicLink(directory.resolve("libvlc.dylib"), mainTarget.getFileName());
      Files.createSymbolicLink(directory.resolve("libvlccore.dylib"), coreTarget.getFileName());
    } catch (final IOException | UnsupportedOperationException failure) {
      Assumptions.abort("Symbolic links cannot be created here: " + failure);
    }
    final InstallationStrategy strategy = new OSXInstallationStrategy(installer, (_, _) -> {});
    final Optional<Path> valid = strategy.getInstalledPath();
    assertEquals(Optional.of(directory), valid);
    Files.writeString(coreTarget, "");
    final Optional<Path> empty = strategy.getInstalledPath();
    assertTrue(empty.isEmpty());
    Files.delete(coreTarget);
    final Optional<Path> broken = strategy.getInstalledPath();
    assertTrue(broken.isEmpty());
  }
}

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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy.ProcessRunner;
import me.brandonli.mcav.testing.IoActions;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// Validates structure only: fixture libraries are nonempty markers and are never passed to a native loader.
final class VlcInstallationValidationTest {

  private static final String MAC_MAIN_LIBRARY_NAME = "libvlc.dylib";
  private static final String MAC_CORE_LIBRARY_NAME = "libvlccore.dylib";

  private static final String MAC_MAIN_TARGET_NAME = "libvlc.5.dylib";
  private static final String MAC_CORE_TARGET_NAME = "libvlccore.9.dylib";

  private static final String COMPLETE_DIRECTORY_NAME = "complete";
  private static final String OTHER_DIRECTORY_NAME = "other";
  private static final String EXTRACTED_DIRECTORY_NAME = "squashfs-root";

  private static final String ARCHIVE_NAME = "fixture.archive";
  private static final String ARCHIVE_ROOT = "vlc-3.0.24/";
  private static final String OTHER_ARCHIVE_DIRECTORY = "vlc-3.0.24/other/";
  private static final String DIRECTORY_ENTRY_SUFFIX = "/";

  private static final String LOCKED_DIRECTORY_NAME = "locked";
  private static final Set<PosixFilePermission> OWNER_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> READ_ONLY_PERMISSIONS = PosixFilePermissions.fromString("r-x------");

  @TempDir
  private Path temporaryDirectory;

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
        final Path other = directory.resolve(OTHER_DIRECTORY_NAME);
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
  void shouldNeedBothNonemptyFilesInTheSameDirectoryForCachedInstallations(final Platform platform) throws IOException {
    for (final Defect defect : Defect.values()) {
      final Path folder = this.temporaryDirectory.resolve(defect.name());
      final VlcInstaller installer = VlcInstaller.create(folder);
      final InstallationStrategy strategy = platform.strategy(installer, (_, _) -> {});
      final Path directory = installer.getInstallDirectory().resolve(platform.relativeDirectory());
      writePair(directory, platform);
      breakPair(directory, platform, defect);
      final Optional<Path> found = strategy.getInstalledPath();
      final boolean absent = found.isEmpty();
      assertTrue(absent, "%s %s cannot be accepted as an installation".formatted(platform, defect));
    }
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void shouldFindACompletePairBeyondAnIncompleteCandidate(final Platform platform) throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path install = installer.getInstallDirectory();
    final Path main = install.resolve(platform.mainLibrary());
    Files.createDirectories(install);
    Files.writeString(main, "orphan library marker");
    final Path complete = install.resolve(COMPLETE_DIRECTORY_NAME);
    writePair(complete, platform);
    final InstallationStrategy strategy = platform.strategy(installer, (_, _) -> {});
    final Optional<Path> found = strategy.getInstalledPath();
    final Optional<Path> expected = Optional.of(complete);
    assertEquals(expected, found);
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void shouldRejectMissingOrEmptyCounterpartsOfExtractedInstallations(final Platform platform) throws IOException {
    for (final Defect defect : Defect.values()) {
      final Path folder = this.temporaryDirectory.resolve(defect.name());
      Files.createDirectories(folder);
      final VlcInstaller installer = VlcInstaller.create(folder);
      final ProcessRunner tools = (_, arguments) ->
        IoActions.runUnchecked(() -> this.extractABrokenPair(platform, defect, folder, installer, arguments));
      final InstallationStrategy strategy = platform.strategy(installer, tools);
      final Path archive = folder.resolve(ARCHIVE_NAME);
      if (platform == Platform.WINDOWS) {
        writeWindowsArchive(archive, platform, defect);
      } else {
        Files.writeString(archive, "simulated archive, never executed");
      }
      assertThrows(InstallationException.class, () -> strategy.execute(archive), "%s %s".formatted(platform, defect));
      final Optional<Path> cached = strategy.getInstalledPath();
      final boolean accepted = cached.isPresent();
      assertFalse(accepted, "a failed extraction must not become an accepted cached installation");
    }
  }

  private void extractABrokenPair(
    final Platform platform,
    final Defect defect,
    final Path folder,
    final VlcInstaller installer,
    final String[] arguments
  ) throws IOException {
    if (platform == Platform.LINUX) {
      final Path extracted = folder.resolve(EXTRACTED_DIRECTORY_NAME).resolve(platform.relativeDirectory());
      writePair(extracted, platform);
      breakPair(extracted, platform, defect);
    } else if (arguments[0].equals("cp")) {
      final Path extracted = installer.getInstallDirectory().resolve(platform.relativeDirectory());
      writePair(extracted, platform);
      breakPair(extracted, platform, defect);
    }
  }

  private static void writeWindowsArchive(final Path archive, final Platform platform, final Defect defect) throws IOException {
    try (final OutputStream file = Files.newOutputStream(archive); final ZipOutputStream zip = new ZipOutputStream(file)) {
      if (defect != Defect.MISSING_MAIN) {
        final String suffix = defect == Defect.DIRECTORY_MAIN ? DIRECTORY_ENTRY_SUFFIX : "";
        final EntryContent content = selectContent(defect, Defect.EMPTY_MAIN, Defect.DIRECTORY_MAIN);
        writeEntry(zip, ARCHIVE_ROOT + platform.mainLibrary() + suffix, content);
      }
      if (defect != Defect.MISSING_CORE) {
        final String prefix = defect == Defect.DIFFERENT_DIRECTORIES ? OTHER_ARCHIVE_DIRECTORY : ARCHIVE_ROOT;
        final String suffix = defect == Defect.DIRECTORY_CORE ? DIRECTORY_ENTRY_SUFFIX : "";
        final EntryContent content = selectContent(defect, Defect.EMPTY_CORE, Defect.DIRECTORY_CORE);
        writeEntry(zip, prefix + platform.coreLibrary() + suffix, content);
      }
    }
  }

  private static EntryContent selectContent(final Defect defect, final Defect emptyDefect, final Defect directoryDefect) {
    final boolean empty = defect == emptyDefect || defect == directoryDefect;
    return empty ? EntryContent.EMPTY : EntryContent.MARKER;
  }

  private static void writeEntry(final ZipOutputStream zip, final String name, final EntryContent content) throws IOException {
    final ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);

    if (content == EntryContent.MARKER) {
      final byte[] marker = "structural library marker, never loaded".getBytes(StandardCharsets.UTF_8);
      zip.write(marker);
    }

    zip.closeEntry();
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void shouldReportAnEarlierInstallationThatCannotBeDeleted(final Platform platform) throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    final boolean posix = operatingSystem != Os.WINDOWS;
    Assumptions.assumeTrue(posix, "Directory permissions need a POSIX file system!");

    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path installDirectory = installer.getInstallDirectory();
    final Path locked = installDirectory.resolve(LOCKED_DIRECTORY_NAME);
    Files.createDirectories(locked);
    final Path file = locked.resolve(platform.mainLibrary());
    Files.writeString(file, "earlier installation marker");
    final Path archive = this.temporaryDirectory.resolve(ARCHIVE_NAME);
    Files.writeString(archive, "archive kept after a failed delete");
    final InstallationStrategy strategy = platform.strategy(installer, (_, _) -> {});
    Files.setPosixFilePermissions(locked, READ_ONLY_PERMISSIONS);
    try {
      final boolean writable = Files.isWritable(locked);
      Assumptions.assumeFalse(writable, "The user can write every directory!");

      assertThrows(FileOperationException.class, () -> strategy.execute(archive));

      final boolean archiveExists = Files.exists(archive);
      assertTrue(archiveExists);
    } finally {
      Files.setPosixFilePermissions(locked, OWNER_PERMISSIONS);
    }
  }

  @Test
  void shouldFollowLibrarySymlinksAndRejectBrokenOrEmptyTargets() throws IOException {
    final VlcInstaller installer = VlcInstaller.create(this.temporaryDirectory);
    final Path directory = installer.getInstallDirectory();
    Files.createDirectories(directory);
    final Path mainTarget = directory.resolve(MAC_MAIN_TARGET_NAME);
    final Path coreTarget = directory.resolve(MAC_CORE_TARGET_NAME);
    Files.writeString(mainTarget, "API target marker");
    Files.writeString(coreTarget, "core target marker");
    try {
      Files.createSymbolicLink(directory.resolve(MAC_MAIN_LIBRARY_NAME), mainTarget.getFileName());
      Files.createSymbolicLink(directory.resolve(MAC_CORE_LIBRARY_NAME), coreTarget.getFileName());
    } catch (final IOException | UnsupportedOperationException exception) {
      final String message = "Creating symbolic links failed with \"%s\" here!".formatted(exception);
      Assumptions.abort(message);
    }

    final InstallationStrategy strategy = new OsxInstallationStrategy(installer, (_, _) -> {});
    final Optional<Path> valid = strategy.getInstalledPath();
    final Optional<Path> expected = Optional.of(directory);
    assertEquals(expected, valid);

    Files.writeString(coreTarget, "");
    final Optional<Path> empty = strategy.getInstalledPath();
    final boolean emptyMissing = empty.isEmpty();
    assertTrue(emptyMissing);

    Files.delete(coreTarget);
    final Optional<Path> broken = strategy.getInstalledPath();
    final boolean brokenMissing = broken.isEmpty();
    assertTrue(brokenMissing);
  }

  private enum Platform {
    WINDOWS,
    MAC,
    LINUX;

    private static final String WINDOWS_MAIN_LIBRARY_NAME = "libvlc.dll";
    private static final String WINDOWS_CORE_LIBRARY_NAME = "libvlccore.dll";

    private static final String LINUX_MAIN_LIBRARY_NAME = "libvlc.so.5.6.1";
    private static final String LINUX_CORE_LIBRARY_NAME = "libvlccore.so.9.0.1";

    private static final Path MAC_LIBRARY_DIRECTORY = Path.of("VLC.app", "Contents", "MacOS", "lib");
    private static final Path LINUX_LIBRARY_DIRECTORY = Path.of("usr", "lib", "i386-linux-gnu");

    private String mainLibrary() {
      return switch (this) {
        case WINDOWS -> WINDOWS_MAIN_LIBRARY_NAME;
        case MAC -> MAC_MAIN_LIBRARY_NAME;
        case LINUX -> LINUX_MAIN_LIBRARY_NAME;
      };
    }

    private String coreLibrary() {
      return switch (this) {
        case WINDOWS -> WINDOWS_CORE_LIBRARY_NAME;
        case MAC -> MAC_CORE_LIBRARY_NAME;
        case LINUX -> LINUX_CORE_LIBRARY_NAME;
      };
    }

    private Path relativeDirectory() {
      return switch (this) {
        case WINDOWS -> Path.of("");
        case MAC -> MAC_LIBRARY_DIRECTORY;
        case LINUX -> LINUX_LIBRARY_DIRECTORY;
      };
    }

    InstallationStrategy strategy(final VlcInstaller installer, final ProcessRunner tools) {
      return switch (this) {
        case WINDOWS -> new WindowsInstallationStrategy(installer);
        case MAC -> new OsxInstallationStrategy(installer, tools);
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

  private enum EntryContent {
    EMPTY,
    MARKER,
  }
}

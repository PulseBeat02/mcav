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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class DiskImagesFuzzTest {

  private static final String DATA_FOLDER_PREFIX = "mcav-disk-images-fuzz";

  private static final String IMAGE_FILE_NAME = "alpine.iso";
  private static final String OUTSIDE_IMAGE_FILE_NAME = "outside.iso";
  private static final String DIRECTORY_IMAGE_FILE_NAME = "directory.iso";
  private static final String LINUX_FOLDER_NAME = "linux";
  private static final String LIBRARY_FOLDER_NAME = "iso-library";

  private static final Path DATA_FOLDER = createDataFolder();
  private static final Path IMAGE_FOLDER = DiskImages.folderOf(DATA_FOLDER);

  @FuzzTest(maxDuration = "30s")
  void shouldResolveOnlyAFileInsideTheImageFolder(final FuzzedDataProvider data) throws IOException {
    final String image = data.consumeRemainingAsString();
    final Path resolved;
    try {
      resolved = DiskImages.require(IMAGE_FOLDER, image);
    } catch (final IllegalArgumentException exception) {
      // refusing is always allowed; what was accepted is checked below
      return;
    }

    final Path realImage = resolved.toRealPath();
    final Path realFolder = IMAGE_FOLDER.toRealPath();
    final boolean inside = realImage.startsWith(realFolder) && !realImage.equals(realFolder);
    assertTrue(inside, image);

    final boolean file = Files.isRegularFile(realImage);
    assertTrue(file, image);
  }

  private static Path createDataFolder() {
    try {
      final Path dataFolder = Files.createTempDirectory(DATA_FOLDER_PREFIX);
      final Path imageFolder = DiskImages.folderOf(dataFolder);
      final Path linuxFolder = imageFolder.resolve(LINUX_FOLDER_NAME);
      final Path directoryImage = imageFolder.resolve(DIRECTORY_IMAGE_FILE_NAME);
      final Path libraryFolder = dataFolder.resolve(LIBRARY_FOLDER_NAME);
      final List<Path> folders = List.of(dataFolder, imageFolder, linuxFolder, directoryImage, libraryFolder);
      final List<Path> files = List.of(
        imageFolder.resolve(IMAGE_FILE_NAME),
        linuxFolder.resolve(IMAGE_FILE_NAME),
        dataFolder.resolve(OUTSIDE_IMAGE_FILE_NAME),
        libraryFolder.resolve(OUTSIDE_IMAGE_FILE_NAME)
      );
      createFoldersAndFiles(folders, files);
      return dataFolder;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  // deleteOnExit deletes in reverse order, so each folder is registered before what it holds
  private static void createFoldersAndFiles(final List<Path> folders, final List<Path> files) throws IOException {
    for (final Path folder : folders) {
      Files.createDirectories(folder);
      final File created = folder.toFile();
      created.deleteOnExit();
    }

    for (final Path file : files) {
      Files.createFile(file);
      final File created = file.toFile();
      created.deleteOnExit();
    }
  }
}

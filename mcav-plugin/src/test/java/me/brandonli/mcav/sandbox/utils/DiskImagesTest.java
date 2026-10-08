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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link DiskImages}, which decides what a command may boot.
 */
final class DiskImagesTest {

  @TempDir
  private Path dataFolder;

  private Path folder;

  @BeforeEach
  void createFolder() throws IOException {
    this.folder = DiskImages.folderOf(this.dataFolder);
    Files.createDirectories(this.folder);
  }

  private Path writeImage(final String name) throws IOException {
    final Path image = this.folder.resolve(name);
    Files.writeString(image, "disk image");
    return image;
  }

  @Test
  void namesTheImageFolderInsideTheDataFolder() {
    final Path expected = this.dataFolder.resolve("iso");
    assertEquals(expected, this.folder);
    assertEquals("iso", DiskImages.FOLDER_NAME);
  }

  @Test
  void resolvesAnImageOfTheFolder() throws IOException {
    final Path image = this.writeImage("alpine.iso");
    final Path resolved = DiskImages.require(this.folder, "alpine.iso");
    final Path expected = image.toAbsolutePath().normalize();
    assertEquals(expected, resolved);
  }

  @Test
  void resolvesAnImageOfASubFolder() throws IOException {
    final Path nested = this.folder.resolve("linux");
    Files.createDirectories(nested);
    final Path image = nested.resolve("alpine.iso");
    Files.writeString(image, "disk image");
    final Path resolved = DiskImages.require(this.folder, "linux/alpine.iso");
    assertEquals(image.toAbsolutePath().normalize(), resolved);
  }

  @Test
  void resolvesAnAbsolutePathInsideTheFolder() throws IOException {
    final Path image = this.writeImage("alpine.iso");
    final String absolute = image.toAbsolutePath().toString();
    final Path resolved = DiskImages.require(this.folder, absolute);
    assertEquals(image.toAbsolutePath().normalize(), resolved);
  }

  @Test
  void followsALinkOfTheFolderThatTheOwnerPutThere() throws IOException {
    final Path elsewhere = this.dataFolder.resolve("library.iso");
    Files.writeString(elsewhere, "disk image");
    final Path link = this.folder.resolve("linked.iso");
    Files.createSymbolicLink(link, elsewhere);

    final Path resolved = DiskImages.require(this.folder, "linked.iso");

    assertEquals(link.toAbsolutePath().normalize(), resolved, "the link is named, so the machine is given the link");
  }

  @ParameterizedTest
  @ValueSource(strings = { "../outside.iso", "../../outside.iso", "/etc/passwd", "linux/../../outside.iso" })
  void refusesAnImageOutsideTheFolder(final String image) {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, image));
    final String message = failure.getMessage();
    final boolean explained = message.contains("lies outside the iso folder");
    assertTrue(explained, message);
  }

  @Test
  void refusesTheFolderItself() {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, "."));
    final String message = failure.getMessage();
    final boolean explained = message.contains("lies outside the iso folder");
    assertTrue(explained, message);
  }

  @Test
  void refusesAnImageThatIsNotAFile() throws IOException {
    final Path directory = this.folder.resolve("directory.iso");
    Files.createDirectories(directory);
    final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
      DiskImages.require(this.folder, "absent.iso")
    );
    final IllegalArgumentException notAFile = assertThrows(IllegalArgumentException.class, () ->
      DiskImages.require(this.folder, "directory.iso")
    );
    assertEquals("There is no disk image absent.iso in the iso folder of the plugin", missing.getMessage());
    assertEquals("There is no disk image directory.iso in the iso folder of the plugin", notAFile.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void refusesAnImageWithoutAName(final String image) {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, image));
    assertEquals("A disk image must be named", failure.getMessage());
  }

  @Test
  void refusesANameTheFileSystemCannotRepresent() {
    final String invalid = "alpine\u0000.iso";
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, invalid));
    final String message = failure.getMessage();
    final boolean explained = message.contains("is not a valid file name");
    assertTrue(explained, message);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> DiskImages.folderOf(null));
    assertThrows(NullPointerException.class, () -> DiskImages.require(null, "alpine.iso"));
    assertThrows(NullPointerException.class, () -> DiskImages.require(this.folder, null));
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(DiskImages.class);
  }
}

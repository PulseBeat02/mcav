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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DiskImagesTest {

  private static final String IMAGE_FILE_NAME = "alpine.iso";
  private static final String ISO_FOLDER_NAME = "iso";
  private static final String LINUX_FOLDER_NAME = "linux";
  private static final String NESTED_IMAGE_PATH = "linux/alpine.iso";
  private static final String LIBRARY_IMAGE_FILE_NAME = "library.iso";
  private static final String LINKED_IMAGE_FILE_NAME = "linked.iso";
  private static final String DIRECTORY_IMAGE_FILE_NAME = "directory.iso";
  private static final String ABSENT_IMAGE_FILE_NAME = "absent.iso";
  private static final String INVALID_IMAGE_FILE_NAME = "alpine\u0000.iso";

  private static final String IMAGE_CONTENT = "disk image";

  private static final String OUTSIDE_FOLDER_EXPLANATION = "lies outside the iso folder";

  @TempDir
  private Path dataFolder;

  private Path folder;

  @BeforeEach
  void createFolder() throws IOException {
    this.folder = DiskImages.folderOf(this.dataFolder);
    Files.createDirectories(this.folder);
  }

  @Test
  void shouldNameTheImageFolderInsideTheDataFolder() {
    final Path expected = this.dataFolder.resolve(ISO_FOLDER_NAME);
    assertEquals(expected, this.folder);
    assertEquals(ISO_FOLDER_NAME, DiskImages.FOLDER_NAME);
  }

  @Test
  void shouldResolveAnImageOfTheFolder() throws IOException {
    final Path image = this.writeImage(IMAGE_FILE_NAME);
    final Path resolved = DiskImages.require(this.folder, IMAGE_FILE_NAME);
    final Path expected = image.toAbsolutePath().normalize();
    assertEquals(expected, resolved);
  }

  private Path writeImage(final String name) throws IOException {
    final Path image = this.folder.resolve(name);
    Files.writeString(image, IMAGE_CONTENT);
    return image;
  }

  @Test
  void shouldResolveAnImageOfASubFolder() throws IOException {
    final Path nested = this.folder.resolve(LINUX_FOLDER_NAME);
    Files.createDirectories(nested);
    final Path image = nested.resolve(IMAGE_FILE_NAME);
    Files.writeString(image, IMAGE_CONTENT);
    final Path resolved = DiskImages.require(this.folder, NESTED_IMAGE_PATH);

    final Path expected = image.toAbsolutePath().normalize();
    assertEquals(expected, resolved);
  }

  @Test
  void shouldResolveAnAbsolutePathInsideTheFolder() throws IOException {
    final Path image = this.writeImage(IMAGE_FILE_NAME);
    final String absolute = image.toAbsolutePath().toString();
    final Path resolved = DiskImages.require(this.folder, absolute);

    final Path expected = image.toAbsolutePath().normalize();
    assertEquals(expected, resolved);
  }

  @Test
  void shouldFollowALinkOfTheFolderThatTheOwnerPutThere() throws IOException {
    final Path elsewhere = this.dataFolder.resolve(LIBRARY_IMAGE_FILE_NAME);
    Files.writeString(elsewhere, IMAGE_CONTENT);
    final Path link = this.folder.resolve(LINKED_IMAGE_FILE_NAME);
    Files.createSymbolicLink(link, elsewhere);

    final Path resolved = DiskImages.require(this.folder, LINKED_IMAGE_FILE_NAME);

    final Path expected = link.toAbsolutePath().normalize();
    assertEquals(expected, resolved, "the link is named, so the machine is given the link");
  }

  @ParameterizedTest
  @ValueSource(strings = { "../outside.iso", "../../outside.iso", "/etc/passwd", "linux/../../outside.iso", "../iso-library/outside.iso" })
  void shouldRefuseAnImageOutsideTheFolder(final String image) {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, image));

    final String message = failure.getMessage();
    final boolean explained = message.contains(OUTSIDE_FOLDER_EXPLANATION);
    assertTrue(explained, message);
  }

  @Test
  void shouldRefuseTheFolderItself() {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, "."));

    final String message = failure.getMessage();
    final boolean explained = message.contains(OUTSIDE_FOLDER_EXPLANATION);
    assertTrue(explained, message);
  }

  @Test
  void shouldRefuseAnImageThatIsNotAFile() throws IOException {
    final Path directory = this.folder.resolve(DIRECTORY_IMAGE_FILE_NAME);
    Files.createDirectories(directory);
    final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
      DiskImages.require(this.folder, ABSENT_IMAGE_FILE_NAME)
    );
    final IllegalArgumentException notAFile = assertThrows(IllegalArgumentException.class, () ->
      DiskImages.require(this.folder, DIRECTORY_IMAGE_FILE_NAME)
    );

    final String message = missing.getMessage();
    assertEquals("There is no disk image absent.iso in the iso folder of the plugin!", message);

    final String notAFileMessage = notAFile.getMessage();
    assertEquals("There is no disk image directory.iso in the iso folder of the plugin!", notAFileMessage);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void shouldRefuseAnImageWithoutAName(final String image) {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, image));

    final String message = failure.getMessage();
    assertEquals("A disk image must be named!", message);
  }

  @Test
  void shouldRefuseANameTheFileSystemCannotRepresent() {
    final String invalid = INVALID_IMAGE_FILE_NAME;
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DiskImages.require(this.folder, invalid));

    final String message = failure.getMessage();
    final boolean explained = message.contains("is not a valid file name");
    assertTrue(explained, message);
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> DiskImages.folderOf(null));
    assertThrows(NullPointerException.class, () -> DiskImages.require(null, IMAGE_FILE_NAME));
    assertThrows(NullPointerException.class, () -> DiskImages.require(this.folder, null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(DiskImages.class);
  }
}

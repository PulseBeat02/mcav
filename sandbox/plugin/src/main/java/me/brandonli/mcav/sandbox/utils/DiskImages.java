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

import com.google.common.base.Preconditions;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The disk images a virtual machine of the plugin may boot. They all live in the {@value #FOLDER_NAME} folder of the
 * plugin, so a command can only name an image the server owner put there, and never another file of the server.
 *
 * <p>A symbolic link inside the folder is followed, which lets the owner keep a library of images elsewhere and link
 * it in. Creating such a link needs access to the files of the server, so it is the owner's decision, not a decision
 * a command can make.
 */
public final class DiskImages {

  /**
   * The name of the folder that holds the disk images, inside the data folder of the plugin.
   */
  public static final String FOLDER_NAME = "iso";

  private DiskImages() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the folder that holds the disk images. The folder is not created here.
   *
   * @param dataFolder the data folder of the plugin
   * @return the folder of the disk images
   */
  public static Path folderOf(final Path dataFolder) {
    Preconditions.checkNotNull(dataFolder, "Data folder must not be null");
    return dataFolder.resolve(FOLDER_NAME);
  }

  /**
   * Resolves the disk image a command names, which must be a file of the image folder.
   *
   * @param folder the folder of the disk images
   * @param image  the image as the sender wrote it, relative to the folder or as an absolute path inside it
   * @return the path of the image
   * @throws NullPointerException     if the folder or the image is {@code null}
   * @throws IllegalArgumentException if the image is empty, lies outside the folder, or is not a file there
   */
  public static Path require(final Path folder, final String image) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    Preconditions.checkNotNull(image, "Image must not be null");
    final boolean named = !image.isBlank();
    Preconditions.checkArgument(named, "A disk image must be named");

    final Path resolved = resolve(folder, image);
    final Path normalizedFolder = folder.toAbsolutePath().normalize();
    final Path normalizedImage = resolved.toAbsolutePath().normalize();
    final boolean inside = normalizedImage.startsWith(normalizedFolder) && !normalizedImage.equals(normalizedFolder);
    Preconditions.checkArgument(
      inside,
      "The disk image %s lies outside the %s folder of the plugin; put the image there and name it",
      image,
      FOLDER_NAME
    );
    final boolean file = Files.isRegularFile(normalizedImage);
    Preconditions.checkArgument(file, "There is no disk image %s in the %s folder of the plugin", image, FOLDER_NAME);
    return normalizedImage;
  }

  private static Path resolve(final Path folder, final String image) {
    try {
      return folder.resolve(image);
    } catch (final RuntimeException invalidPath) {
      // an image name the file system cannot represent at all, such as a NUL byte on Linux
      final String message = "The disk image %s is not a valid file name".formatted(image);
      throw new IllegalArgumentException(message, invalidPath);
    }
  }
}

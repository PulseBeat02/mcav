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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

// a change that keeps the modification time, the size and the file key goes unnoticed
final class ResourcePackFile {

  private static final byte[] EMPTY = new byte[0];

  private final Path path;

  private byte[] cachedBytes;
  private @Nullable FileTime cachedModifiedTime;
  private @Nullable Object cachedFileKey;
  private long cachedSize;

  ResourcePackFile(final Path path) {
    this.path = path;
    this.cachedBytes = EMPTY;
    this.cachedSize = -1;
  }

  // the array is shared: callers must not modify it
  synchronized byte[] read() throws IOException {
    final BasicFileAttributes attributes = Files.readAttributes(this.path, BasicFileAttributes.class);
    final FileTime modifiedTime = attributes.lastModifiedTime();
    final Object fileKey = attributes.fileKey();
    final long size = attributes.size();
    final boolean unchanged =
      modifiedTime.equals(this.cachedModifiedTime) && this.cachedSize == size && Objects.equals(fileKey, this.cachedFileKey);
    if (unchanged) {
      return this.cachedBytes;
    }

    final byte[] bytes = Files.readAllBytes(this.path);
    this.cachedBytes = bytes;
    this.cachedModifiedTime = modifiedTime;
    this.cachedFileKey = fileKey;
    this.cachedSize = size;

    return bytes;
  }
}

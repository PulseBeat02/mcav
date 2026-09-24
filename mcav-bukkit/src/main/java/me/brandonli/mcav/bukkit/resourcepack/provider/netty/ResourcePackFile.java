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

/**
 * Caches the contents of a resource pack in memory, so repeated downloads do not read the file from disk every
 * time. The cache uses the full modification time, size, and file identity when available. Changes that preserve
 * all of that metadata cannot be detected; rebuilders should update the modification time. This class is thread-safe.
 *
 * <p>The whole pack is kept on the heap for as long as the hosting exists, so it costs as much memory as the pack
 * is large. For very large packs, prefer a dedicated server that streams the file from disk, such as
 * {@link me.brandonli.mcav.bukkit.resourcepack.provider.http.ServerPackHosting}.
 */
final class ResourcePackFile {

  private static final byte[] EMPTY = new byte[0];

  private final Path path;

  private byte[] cachedBytes;
  private @Nullable FileTime cachedModified;
  private @Nullable Object cachedFileKey;
  private long cachedSize;

  /**
   * Constructs a new cache for the specified file. The file is not read until the first call to {@link #read()}.
   *
   * @param path the path to the resource pack
   */
  ResourcePackFile(final Path path) {
    this.path = path;
    this.cachedBytes = EMPTY;
    this.cachedSize = -1;
  }

  /**
   * Gets the contents of the resource pack, reading the file again only if it changed since the last call.
   * The returned array is shared between callers and must not be modified.
   *
   * @return the bytes of the resource pack
   * @throws IOException if the file cannot be read
   */
  synchronized byte[] read() throws IOException {
    final BasicFileAttributes attributes = Files.readAttributes(this.path, BasicFileAttributes.class);
    final FileTime modifiedTime = attributes.lastModifiedTime();
    final Object fileKey = attributes.fileKey();
    final long size = attributes.size();
    final boolean unchanged =
      modifiedTime.equals(this.cachedModified) && this.cachedSize == size && Objects.equals(fileKey, this.cachedFileKey);
    if (unchanged) {
      return this.cachedBytes;
    }
    final byte[] bytes = Files.readAllBytes(this.path);
    this.cachedBytes = bytes;
    this.cachedModified = modifiedTime;
    this.cachedFileKey = fileKey;
    this.cachedSize = size;
    return bytes;
  }
}

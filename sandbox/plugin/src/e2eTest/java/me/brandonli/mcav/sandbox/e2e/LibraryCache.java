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
package me.brandonli.mcav.sandbox.e2e;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Keeps the libraries the plugin downloads between test runs, so only the first run downloads them. The cached
 * libraries are moved into the library folder of the server before it starts, and moved back once it has stopped,
 * with whatever the server downloaded or removed since. The plugin still checks the SHA-256 hash of every cached
 * library against its {@code dependencies.txt}, and downloads a library again when the hash does not match.
 */
final class LibraryCache implements AutoCloseable {

  private final Path cacheDirectory;
  private final Path serverLibraries;

  private LibraryCache(final Path cacheDirectory, final Path serverLibraries) {
    this.cacheDirectory = cacheDirectory;
    this.serverLibraries = serverLibraries;
  }

  /**
   * Moves the cached libraries into the library folder of a server that has not started yet.
   *
   * @param cacheDirectory  the folder that keeps the libraries between runs
   * @param serverLibraries the library folder of the server, which must not exist yet
   * @return the cache, which takes the libraries back when it is closed
   * @throws IOException if the libraries cannot be moved
   */
  static LibraryCache lend(final Path cacheDirectory, final Path serverLibraries) throws IOException {
    if (Files.isDirectory(cacheDirectory)) {
      transfer(cacheDirectory, serverLibraries);
    }
    return new LibraryCache(cacheDirectory, serverLibraries);
  }

  /**
   * Moves the library folder of the stopped server back into the cache, replacing what the cache still holds.
   *
   * @throws IOException if the libraries cannot be moved
   */
  @Override
  public void close() throws IOException {
    if (!Files.isDirectory(this.serverLibraries)) {
      return;
    }
    deleteRecursively(this.cacheDirectory);
    transfer(this.serverLibraries, this.cacheDirectory);
  }

  /**
   * Moves a folder, which is a single rename on the same file system and a copy followed by a delete otherwise.
   */
  private static void transfer(final Path source, final Path target) throws IOException {
    final Path targetParent = target.getParent();
    Files.createDirectories(targetParent);
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException exception) {
      copyRecursively(source, target);
      deleteRecursively(source);
    }
  }

  private static void copyRecursively(final Path source, final Path target) throws IOException {
    final List<Path> entries;
    try (final Stream<Path> walk = Files.walk(source)) {
      entries = walk.toList();
    }
    for (final Path entry : entries) {
      final Path relative = source.relativize(entry);
      final Path copy = target.resolve(relative);
      if (Files.isDirectory(entry)) {
        Files.createDirectories(copy);
      } else {
        Files.copy(entry, copy, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static void deleteRecursively(final Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    final List<Path> entries;
    try (final Stream<Path> walk = Files.walk(directory)) {
      final Comparator<Path> deepestFirst = Comparator.reverseOrder();
      final Stream<Path> sorted = walk.sorted(deepestFirst);
      entries = sorted.toList();
    }
    for (final Path entry : entries) {
      Files.delete(entry);
    }
  }
}

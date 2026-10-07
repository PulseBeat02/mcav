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
package me.brandonli.mcav.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.aether.repository.RemoteRepository;

/** Checks installation writes in a filesystem whose entire root is observable. */
final class InstallationTreeAssertions implements AutoCloseable {

  private final FileSystem filesystem;
  private final InstallationManager manager;
  private final Path root;
  private final Path installed;

  InstallationTreeAssertions(final Path archive, final List<RemoteRepository> repositories, final Path localRepository) throws IOException {
    this.filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"));
    this.root = this.filesystem.getPath("/");
    this.installed = Files.createDirectories(this.root.resolve("workspace/installed"));
    Files.write(this.root.resolve("boundary-sentinel"), new byte[] { 11, 22, 33, 44 });
    Files.write(this.installed.getParent().resolve("existing.txt"), new byte[] { 55, 66 });
    this.manager = new InstallationManager(this.installed, repositories, localRepository);
  }

  void assertArtifactFolder(final String artifactId) throws IOException {
    final Map<Path, StoredEntry> before = this.snapshot();
    boolean refused = false;
    try {
      this.manager.downloadDependencies("me.test", artifactId, "1.0");
    } catch (final InstallationException failure) {
      refused = failure.getMessage().startsWith("Artifact id must contain only");
    }
    final Map<Path, StoredEntry> after = this.snapshot();
    for (final Map.Entry<Path, StoredEntry> entry : before.entrySet()) {
      final Path path = entry.getKey();
      assertTrue(after.containsKey(path), () -> "the existing path survives: " + path);
      assertEquals(entry.getValue().directory(), after.get(path).directory(), () -> "the path retains its type: " + path);
      assertArrayEquals(entry.getValue().contents(), after.get(path).contents(), () -> "the existing bytes survive: " + path);
    }
    if (refused) {
      assertEquals(before.keySet(), after.keySet(), "refused coordinates write nowhere in the filesystem");
      return;
    }
    final Path ownFolder = this.installed.resolve(artifactId);
    assertEquals(this.installed, ownFolder.getParent());
    assertTrue(Files.isDirectory(ownFolder));
    for (final Path path : after.keySet()) {
      assertTrue(before.containsKey(path) || path.equals(ownFolder), () -> "the only new path is the artifact folder: " + path);
    }
  }

  private Map<Path, StoredEntry> snapshot() throws IOException {
    final Map<Path, StoredEntry> entries = new HashMap<>();
    try (final Stream<Path> paths = Files.walk(this.root)) {
      for (final Path path : paths.toList()) {
        final boolean directory = Files.isDirectory(path);
        entries.put(path, new StoredEntry(directory, directory ? new byte[0] : Files.readAllBytes(path)));
      }
    }
    return entries;
  }

  @Override
  public void close() throws IOException {
    try {
      this.manager.close();
    } finally {
      this.filesystem.close();
    }
  }

  private static final class StoredEntry {

    private final boolean directory;
    private final byte[] contents;

    private StoredEntry(final boolean directory, final byte[] contents) {
      this.directory = directory;
      this.contents = contents;
    }

    private boolean directory() {
      return this.directory;
    }

    private byte[] contents() {
      return this.contents;
    }
  }
}

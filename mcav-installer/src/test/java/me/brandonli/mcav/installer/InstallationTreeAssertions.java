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
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.aether.repository.RemoteRepository;

final class InstallationTreeAssertions implements AutoCloseable {

  private static final String ROOT = "/";
  private static final String INSTALLED_FOLDER = "workspace/installed";
  private static final String SENTINEL_FILE = "boundary-sentinel";
  private static final String EXISTING_FILE = "existing.txt";

  private final FileSystem filesystem;
  private final InstallationManager manager;
  private final Path root;
  private final Path installed;

  InstallationTreeAssertions(final Path archive, final List<RemoteRepository> repositories, final Path localRepository) throws IOException {
    this.filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"));
    this.root = this.filesystem.getPath(ROOT);
    final Path installedFolder = this.root.resolve(INSTALLED_FOLDER);
    this.installed = Files.createDirectories(installedFolder);

    final Path sentinel = this.root.resolve(SENTINEL_FILE);
    Files.write(sentinel, new byte[] { 11, 22, 33, 44 });

    final Path workspace = this.installed.getParent();
    final Path existing = workspace.resolve(EXISTING_FILE);
    Files.write(existing, new byte[] { 55, 66 });

    this.manager = new InstallationManager(this.installed, repositories, localRepository);
  }

  void assertArtifactFolder(final String artifactId) throws IOException {
    final Map<Path, StoredEntry> before = this.snapshot();
    boolean refused = false;
    try {
      this.manager.downloadDependencies("me.test", artifactId, "1.0");
    } catch (final InstallationException exception) {
      final String message = exception.getMessage();
      refused = message.endsWith(" must contain only letters, digits, dots, underscores or hyphens!");
    }

    final Map<Path, StoredEntry> after = this.snapshot();
    this.assertExistingEntriesSurvive(before, after);

    if (refused) {
      final Set<Path> pathsBefore = before.keySet();
      final Set<Path> pathsAfter = after.keySet();
      assertEquals(pathsBefore, pathsAfter, "refused coordinates write nowhere in the filesystem");
      return;
    }

    final Path ownFolder = this.installed.resolve(artifactId);
    final Path ownFolderParent = ownFolder.getParent();
    assertEquals(this.installed, ownFolderParent);

    final boolean ownFolderIsDirectory = Files.isDirectory(ownFolder);
    assertTrue(ownFolderIsDirectory);

    this.assertOnlyOwnFolderIsNew(before, after, ownFolder);
  }

  private Map<Path, StoredEntry> snapshot() throws IOException {
    final Map<Path, StoredEntry> entries = new HashMap<>();
    try (final Stream<Path> paths = Files.walk(this.root)) {
      for (final Path path : paths.toList()) {
        final StoredEntry entry = this.storedEntryOf(path);
        entries.put(path, entry);
      }
    }

    return entries;
  }

  private StoredEntry storedEntryOf(final Path path) throws IOException {
    final boolean directory = Files.isDirectory(path);
    if (directory) {
      return new StoredEntry(directory, new byte[0]);
    }

    final byte[] contents = Files.readAllBytes(path);
    return new StoredEntry(directory, contents);
  }

  private void assertExistingEntriesSurvive(final Map<Path, StoredEntry> before, final Map<Path, StoredEntry> after) {
    for (final Map.Entry<Path, StoredEntry> entry : before.entrySet()) {
      final Path path = entry.getKey();
      final boolean survives = after.containsKey(path);
      assertTrue(survives, () -> "the existing path survives: %s".formatted(path));

      final StoredEntry stored = entry.getValue();
      final StoredEntry current = after.get(path);
      final boolean storedDirectory = stored.directory();
      final boolean currentDirectory = current.directory();
      assertEquals(storedDirectory, currentDirectory, () -> "the path retains its type: %s".formatted(path));

      final byte[] storedContents = stored.contents();
      final byte[] currentContents = current.contents();
      assertArrayEquals(storedContents, currentContents, () -> "the existing bytes survive: %s".formatted(path));
    }
  }

  private void assertOnlyOwnFolderIsNew(final Map<Path, StoredEntry> before, final Map<Path, StoredEntry> after, final Path ownFolder) {
    for (final Path path : after.keySet()) {
      final boolean knownOrOwnFolder = before.containsKey(path) || path.equals(ownFolder);
      assertTrue(knownOrOwnFolder, () -> "the only new path is the artifact folder: %s".formatted(path));
    }
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

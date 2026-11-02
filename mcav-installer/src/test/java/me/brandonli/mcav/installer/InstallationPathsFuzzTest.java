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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class InstallationPathsFuzzTest {

  private static final String TEMPORARY_PREFIX = "mcav-installer-fuzz";

  private static final String INSTALLED_FOLDER = "installed";
  private static final String REMOTE_FOLDER = "remote";
  private static final String LOCAL_FOLDER = "local";
  private static final String ARTIFACT_FOLDER = "artifact";

  private static final String TREE_ARCHIVE = "complete-tree.zip";
  private static final String TREE_LOCAL_FOLDER = "complete-local";

  private static final Workspace WORKSPACE = new Workspace();

  @FuzzTest(maxDuration = "30s")
  void shouldWriteOnlyWhereTheCoordinatesAllow(final FuzzedDataProvider data) throws IOException {
    final String artifactId = data.consumeString(40);
    final String groupId = data.consumeString(30);
    final String artifact = data.consumeString(30);
    final String fileName = data.consumeRemainingAsString();
    this.assertArtifactFolder(artifactId);
    this.assertJarDestination(groupId, artifact, fileName);
  }

  private void assertArtifactFolder(final String artifactId) throws IOException {
    WORKSPACE.completeTree.assertArtifactFolder(artifactId);
    final List<Path> before = WORKSPACE.list();
    boolean refused = false;
    try {
      WORKSPACE.manager.downloadDependencies("me.fuzz", artifactId, "1.0");
    } catch (final InstallationException exception) {
      final String message = exception.getMessage();
      refused = message.endsWith(" must contain only letters, digits, dots, underscores or hyphens!");
    }

    final List<Path> created = new ArrayList<>(WORKSPACE.list());
    created.removeAll(before);

    if (refused) {
      assertEquals(List.of(), created, () -> "the refused id '%s' created %s".formatted(artifactId, created));
      return;
    }

    final Path ownFolder = WORKSPACE.folder.resolve(artifactId);
    final Path parent = ownFolder.getParent();
    final boolean directChild = WORKSPACE.folder.equals(parent) && Files.isDirectory(ownFolder);
    assertTrue(directChild, () -> "the id '%s' has no folder of its own right below the installation folder".formatted(artifactId));

    for (final Path path : created) {
      final boolean allowed = path.startsWith(WORKSPACE.localRepository) || path.equals(ownFolder);
      assertTrue(allowed, () -> "the id '%s' created %s".formatted(artifactId, path));
    }
  }

  private void assertJarDestination(final String groupId, final String artifact, final String fileName) {
    final Path target = WORKSPACE.folder.resolve(ARTIFACT_FOLDER);
    final Path destination;
    try {
      destination = InstallationManager.destinationOf(target, groupId, artifact, fileName);
    } catch (final IOException | InvalidPathException exception) {
      return;
    }

    final Path normalizedTarget = target.normalize();
    final Path normalizedDestination = destination.normalize();
    final boolean strictlyInside = normalizedDestination.startsWith(normalizedTarget) && !normalizedDestination.equals(normalizedTarget);
    final Path name = normalizedDestination.getFileName();
    final boolean keepsItsName = name != null && name.toString().equals(fileName);
    final boolean insideUnderItsName = strictlyInside && keepsItsName;
    assertTrue(insideUnderItsName, () -> "%s:%s (%s) is copied to %s".formatted(groupId, artifact, fileName, normalizedDestination));
  }

  private static final class Workspace {

    private final Path root;
    private final Path folder;
    private final Path localRepository;
    private final InstallationManager manager;
    private final InstallationTreeAssertions completeTree;

    Workspace() {
      try {
        this.root = Files.createTempDirectory(TEMPORARY_PREFIX);
        this.folder = this.root.resolve(INSTALLED_FOLDER);
        this.localRepository = this.root.resolve(LOCAL_FOLDER);
        final Path remote = this.root.resolve(REMOTE_FOLDER);
        Files.createDirectories(this.folder);
        Files.createDirectories(remote);
        final URI remoteUri = remote.toUri();
        final String remoteUrl = remoteUri.toString();
        final RemoteRepository repository = InstallationManager.createRepository("empty", remoteUrl);
        final List<RemoteRepository> repositories = List.of(repository);
        this.manager = new InstallationManager(this.folder, repositories, this.localRepository);
        this.completeTree = new InstallationTreeAssertions(
          this.root.resolve(TREE_ARCHIVE),
          repositories,
          this.root.resolve(TREE_LOCAL_FOLDER)
        );
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    List<Path> list() throws IOException {
      try (final Stream<Path> paths = Files.walk(this.root)) {
        return paths.toList();
      }
    }
  }
}

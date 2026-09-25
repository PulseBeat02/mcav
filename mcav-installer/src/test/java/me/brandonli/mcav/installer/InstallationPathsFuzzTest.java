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

/**
 * Fuzzes the paths the installer derives from coordinates, the root artifact id a plugin passes in and the group,
 * artifact and file names a POM may hold. The installer has no archive extraction; it resolves jars and copies them, so
 * the question is only where it writes. An artifact id is refused before anything is created, or gets a folder of
 * exactly its name right below the installation folder and nothing else outside the local repository; a jar goes
 * strictly below the folder of its artifact under its own file name, or the coordinates are refused. The artifact ids
 * run against an empty repository on the file system, so nothing is downloaded. The property tests of this module
 * cover the same ground with generated coordinates; this reaches the ones a generator would not think of.
 */
@Tag("fuzz")
final class InstallationPathsFuzzTest {

  private static final Workspace WORKSPACE = new Workspace();

  @FuzzTest(maxDuration = "30s")
  void writesOnlyWhereTheCoordinatesAllow(final FuzzedDataProvider data) throws IOException {
    final String artifactId = data.consumeString(40);
    final String groupId = data.consumeString(30);
    final String artifact = data.consumeString(30);
    final String fileName = data.consumeRemainingAsString();
    this.assertArtifactFolder(artifactId);
    assertJarDestination(groupId, artifact, fileName);
  }

  private void assertArtifactFolder(final String artifactId) throws IOException {
    final List<Path> before = WORKSPACE.list();
    boolean refused = false;
    try {
      WORKSPACE.manager.downloadDependencies("me.fuzz", artifactId, "1.0");
    } catch (final InstallationException failure) {
      final String message = failure.getMessage();
      refused = message.startsWith("Artifact id must contain only");
    }
    final List<Path> created = new ArrayList<>(WORKSPACE.list());
    created.removeAll(before);
    if (refused) {
      assertEquals(List.of(), created, () -> "the refused id '" + artifactId + "' created " + created);
      return;
    }
    final Path ownFolder = WORKSPACE.folder.resolve(artifactId);
    final Path parent = ownFolder.getParent();
    final boolean directChild = WORKSPACE.folder.equals(parent) && Files.isDirectory(ownFolder);
    assertTrue(directChild, () -> "the id '" + artifactId + "' has no folder of its own right below the installation folder");
    for (final Path path : created) {
      final boolean allowed = path.startsWith(WORKSPACE.localRepository) || path.equals(ownFolder);
      assertTrue(allowed, () -> "the id '" + artifactId + "' created " + path);
    }
  }

  private static void assertJarDestination(final String groupId, final String artifact, final String fileName) {
    final Path target = WORKSPACE.folder.resolve("artifact");
    final Path destination;
    try {
      destination = InstallationManager.destinationOf(target, groupId, artifact, fileName);
    } catch (final IOException | InvalidPathException refused) {
      return;
    }
    final Path normalizedTarget = target.normalize();
    final Path normalizedDestination = destination.normalize();
    final boolean strictlyInside = normalizedDestination.startsWith(normalizedTarget) && !normalizedDestination.equals(normalizedTarget);
    final Path name = normalizedDestination.getFileName();
    final boolean keepsItsName = name != null && name.toString().equals(fileName);
    assertTrue(
      strictlyInside && keepsItsName,
      () -> groupId + ":" + artifact + " (" + fileName + ") is copied to " + normalizedDestination
    );
  }

  /**
   * One installation folder, local repository and empty remote repository for the whole run of the fuzzer.
   */
  private static final class Workspace {

    private final Path root;
    private final Path folder;
    private final Path localRepository;
    private final InstallationManager manager;

    Workspace() {
      try {
        this.root = Files.createTempDirectory("mcav-installer-fuzz");
        this.folder = this.root.resolve("installed");
        this.localRepository = this.root.resolve("local");
        final Path remote = this.root.resolve("remote");
        Files.createDirectories(this.folder);
        Files.createDirectories(remote);
        final URI remoteUri = remote.toUri();
        final String remoteUrl = remoteUri.toString();
        final RemoteRepository repository = InstallationManager.createRepository("empty", remoteUrl);
        final List<RemoteRepository> repositories = List.of(repository);
        this.manager = new InstallationManager(this.folder, repositories, this.localRepository);
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

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

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.StringArbitrary;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.eclipse.aether.repository.RemoteRepository;

final class InstallationPathsPropertyTest {

  private static final String TEMPORARY_PREFIX = "mcav-installer-paths";

  private static final String INSTALLED_FOLDER = "installed";
  private static final String REMOTE_FOLDER = "remote";
  private static final String LOCAL_FOLDER = "local";
  private static final String ARTIFACT_FOLDER = "artifact";

  private static final String TREE_ARCHIVE = "complete-tree.zip";
  private static final String TREE_LOCAL_FOLDER = "complete-local";

  private static final String SEED = "20260925";
  private static final String REFUSED = " must contain only letters, digits, dots, underscores or hyphens!";

  private Path root;
  private Path folder;
  private Path localRepository;
  private InstallationManager manager;
  private InstallationTreeAssertions completeTree;

  @BeforeProperty
  void createManager() throws IOException {
    this.root = Files.createTempDirectory(TEMPORARY_PREFIX);
    this.folder = this.root.resolve(INSTALLED_FOLDER);
    final Path emptyRepository = this.root.resolve(REMOTE_FOLDER);
    this.localRepository = this.root.resolve(LOCAL_FOLDER);
    Files.createDirectories(this.folder);
    Files.createDirectories(emptyRepository);
    final URI remoteUri = emptyRepository.toUri();
    final String remoteUrl = remoteUri.toString();
    final RemoteRepository remote = InstallationManager.createRepository("empty", remoteUrl);
    final List<RemoteRepository> repositories = List.of(remote);
    this.manager = new InstallationManager(this.folder, repositories, this.localRepository);
    this.completeTree = new InstallationTreeAssertions(this.root.resolve(TREE_ARCHIVE), repositories, this.root.resolve(TREE_LOCAL_FOLDER));
  }

  @AfterProperty
  void deleteFolders() throws IOException {
    this.manager.close();
    this.completeTree.close();
    this.deleteTree(this.root);
  }

  @Provide
  Arbitrary<String> hostileIds() {
    final StringArbitrary pathStrings = Arbitraries.strings();
    final StringArbitrary pathCharacters = pathStrings.withChars("./\\:~ %2eaZ_-\u0000\u2024\uFF0E");
    final StringArbitrary shortPaths = pathCharacters.ofMaxLength(12);
    final StringArbitrary anyStrings = Arbitraries.strings();
    final StringArbitrary anyText = anyStrings.ofMaxLength(20);
    final Arbitrary<String> classics = Arbitraries.of(
      ".",
      "..",
      "...",
      "../escape",
      "..\\escape",
      "/absolute",
      "C:\\escape",
      "C:",
      "%2e%2e",
      "a/../..",
      "valid-id",
      "valid.id_2",
      "\u0000"
    );
    return Arbitraries.oneOf(shortPaths, anyText, classics);
  }

  @Property(seed = SEED, tries = 80)
  void shouldCreateNothingOutsideTheInstallationFolderForAnyArtifactId(@ForAll("hostileIds") final String artifactId) throws IOException {
    this.completeTree.assertArtifactFolder(artifactId);
    final Path installed = this.folder.toAbsolutePath();
    final Path cache = this.localRepository.toAbsolutePath();
    final List<Path> before = this.listTree(this.root);
    boolean refused = false;
    try {
      this.manager.downloadDependencies("me.test", artifactId, "1.0");
    } catch (final InstallationException exception) {
      final String message = exception.getMessage();
      refused = message.endsWith(REFUSED);
    }

    final List<Path> after = this.listTree(this.root);
    final List<Path> created = new ArrayList<>(after);
    created.removeAll(before);

    if (refused) {
      assertEquals(List.of(), created, () -> "a refused id created %s".formatted(created));
      return;
    }

    final List<String> children = this.listNames(this.folder);
    final boolean ownFolder = children.contains(artifactId);
    assertTrue(ownFolder, () -> "the id '%s' has no folder of its own in %s: %s".formatted(artifactId, installed, children));

    for (final Path path : created) {
      final boolean inLocalRepository = path.startsWith(cache);
      if (inLocalRepository) {
        continue;
      }
      final Path parent = path.getParent();
      final boolean directChild = installed.equals(parent);
      assertTrue(directChild, () -> "the id '%s' created %s, which is not directly in %s".formatted(artifactId, path, installed));
    }
  }

  @Provide
  Arbitrary<String> coordinates() {
    final StringArbitrary coordinateStrings = Arbitraries.strings();
    final StringArbitrary coordinateCharacters = coordinateStrings.withChars("./\\ ajz-_");
    final StringArbitrary shortCoordinates = coordinateCharacters.ofMaxLength(10);
    final Arbitrary<String> classics = Arbitraries.of(".", "..", "", "/", "/etc", "a/..", "../..", "normal");
    return Arbitraries.oneOf(shortCoordinates, classics);
  }

  @Property(seed = SEED)
  void shouldPlaceEveryJarDestinationStrictlyInsideTheArtifactFolder(
    @ForAll("coordinates") final String groupId,
    @ForAll("coordinates") final String artifactId,
    @ForAll("coordinates") final String fileName
  ) {
    final Path target = this.folder.resolve(ARTIFACT_FOLDER);
    final Path destination;
    try {
      destination = InstallationManager.destinationOf(target, groupId, artifactId, fileName);
    } catch (final IOException | InvalidPathException exception) {
      return;
    }

    final Path normalizedTarget = target.normalize();
    final Path normalizedDestination = destination.normalize();
    final boolean inside = normalizedDestination.startsWith(normalizedTarget);
    final boolean strictlyInside = inside && !normalizedDestination.equals(normalizedTarget);
    final Path destinationName = normalizedDestination.getFileName();
    final String name = String.valueOf(destinationName);
    final boolean keepsItsName = name.equals(fileName);
    final boolean insideUnderItsName = strictlyInside && keepsItsName;
    assertTrue(insideUnderItsName, () -> "%s:%s (%s) is copied to %s".formatted(groupId, artifactId, fileName, normalizedDestination));
  }

  private List<String> listNames(final Path directory) throws IOException {
    try (final Stream<Path> children = Files.list(directory)) {
      final Stream<Path> names = children.map(Path::getFileName);
      final Stream<String> strings = names.map(Path::toString);
      return strings.toList();
    }
  }

  private List<Path> listTree(final Path directory) throws IOException {
    try (final Stream<Path> paths = Files.walk(directory)) {
      final Stream<Path> absolute = paths.map(Path::toAbsolutePath);
      return absolute.toList();
    }
  }

  private void deleteTree(final Path directory) throws IOException {
    final TreeDeleter deleter = new TreeDeleter();
    Files.walkFileTree(directory, deleter);
  }

  private static final class TreeDeleter extends SimpleFileVisitor<Path> {

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
      Files.delete(file);

      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path visited, final IOException failure) throws IOException {
      if (failure != null) {
        throw failure;
      }

      Files.delete(visited);

      return FileVisitResult.CONTINUE;
    }
  }
}

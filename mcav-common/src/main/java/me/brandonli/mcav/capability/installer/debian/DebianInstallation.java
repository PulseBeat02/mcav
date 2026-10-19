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
package me.brandonli.mcav.capability.installer.debian;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

// Installs pinned Debian packages into one folder: each package is downloaded, checked against its hash and
// extracted, then the folder is moved into place with the load order of its libraries, which marks it complete.
final class DebianInstallation {

  private static final long MAXIMUM_PACKAGE_SIZE = 64L * 1024L * 1024L;

  private static final String LIBRARY_TREE = "usr/lib";
  private static final String LEGACY_LIBRARY_TREE = "lib";
  private static final String X86_64_TRIPLET = "x86_64-linux-gnu";
  private static final String AARCH64_TRIPLET = "aarch64-linux-gnu";
  private static final String LIBRARY_PREFIX = "lib";
  private static final String LIBRARY_SUFFIX = ".so";

  private static final String LOAD_ORDER_FILE = "load-order.txt";
  private static final String EXTRACTED_FOLDER = "root";
  private static final String PACKAGE_FILE = "package.deb";

  private final Path folder;
  private final String name;
  private final Download[] downloads;
  private final Platform platform;
  private final List<String> trees;

  DebianInstallation(
    final Path folder,
    final String name,
    final Download[] downloads,
    final Platform platform,
    final List<String> extraTrees
  ) {
    final Stream<String> libraryTrees = Stream.of(LIBRARY_TREE, LEGACY_LIBRARY_TREE);
    final Stream<String> otherTrees = extraTrees.stream();
    this.folder = folder;
    this.name = name;
    this.downloads = downloads.clone();
    this.platform = platform;
    this.trees = Stream.concat(libraryTrees, otherTrees).toList();
  }

  void install() {
    final boolean installed = this.isInstalled();
    if (installed) {
      return;
    }

    final List<Download> packages = this.findPackages();
    if (packages.isEmpty()) {
      final String message = "Platform %s has no %s packages!".formatted(this.platform, this.name);
      throw new InstallationException(message);
    }

    this.deleteBrokenInstallation();
    try {
      this.installPackages(packages);
    } catch (final IOException exception) {
      final String message = "Debian packages of %s could not be installed to %s!".formatted(this.name, this.folder);
      throw new InstallationException(message, exception);
    }
  }

  boolean isInstalled() {
    final Path loadOrder = this.folder.resolve(LOAD_ORDER_FILE);
    return Files.isRegularFile(loadOrder);
  }

  private List<Download> findPackages() {
    final List<Download> packages = new ArrayList<>();
    for (final Download download : this.downloads) {
      final Platform downloadPlatform = download.getPlatform();
      if (downloadPlatform.equals(this.platform)) {
        packages.add(download);
      }
    }

    return packages;
  }

  // an installation folder without a load order was left by an older or interrupted installation
  private void deleteBrokenInstallation() {
    final boolean exists = Files.exists(this.folder);
    if (exists) {
      IoUtils.deleteRecursively(this.folder);
    }
  }

  private void installPackages(final List<Download> packages) throws IOException {
    final Path parent = this.getParent();
    Files.createDirectories(parent);
    final Path staging = Files.createTempDirectory(parent, this.name);
    try {
      final Path extracted = staging.resolve(EXTRACTED_FOLDER);
      final List<String> loadOrder = new ArrayList<>();
      for (final Download download : packages) {
        this.installPackage(download, staging, extracted, loadOrder);
      }

      final Path loadOrderFile = extracted.resolve(LOAD_ORDER_FILE);
      Files.write(loadOrderFile, loadOrder, StandardCharsets.UTF_8);
      Files.move(extracted, this.folder, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      IoUtils.deleteRecursively(staging);
    }
  }

  private void installPackage(final Download download, final Path staging, final Path extracted, final List<String> loadOrder)
    throws IOException {
    final String url = download.getUrl();
    final Optional<String> hash = download.getHash();
    final @Nullable String sha256 = hash.orElse(null);
    if (sha256 == null) {
      final String message = "Debian package %s has no pinned hash!".formatted(url);
      throw new InstallationException(message);
    }

    final URI uri = URI.create(url);
    final Path archive = staging.resolve(PACKAGE_FILE);
    HttpDownloader.download(uri, archive, sha256, MAXIMUM_PACKAGE_SIZE);
    DebianPackageExtractor.extract(archive, extracted, this.trees);

    final Set<String> libraries = this.listLibraries(extracted);
    libraries.removeAll(loadOrder);
    loadOrder.addAll(libraries);
  }

  // Lists the libraries directly in the library folders, sorted by name, as paths relative to the installation.
  private Set<String> listLibraries(final Path root) throws IOException {
    final Set<String> libraries = new LinkedHashSet<>();
    for (final Path directory : this.getTripletFolders(root)) {
      final List<Path> files = this.listDirectory(directory);
      final List<String> names = files.stream().filter(this::isLibrary).map(root::relativize).map(Path::toString).sorted().toList();
      libraries.addAll(names);
    }

    return libraries;
  }

  private List<Path> getTripletFolders(final Path root) {
    final String triplet = this.getTriplet();
    final Path libraryDirectory = root.resolve(LIBRARY_TREE).resolve(triplet);
    final Path legacyDirectory = root.resolve(LEGACY_LIBRARY_TREE).resolve(triplet);
    return List.of(libraryDirectory, legacyDirectory);
  }

  private List<Path> listDirectory(final Path directory) throws IOException {
    final boolean exists = Files.isDirectory(directory);
    if (!exists) {
      return List.of();
    }

    try (final Stream<Path> files = Files.list(directory)) {
      return files.toList();
    }
  }

  // the real files only: a symbolic link names a library that its target already loads
  private boolean isLibrary(final Path file) {
    final Path fileName = file.getFileName();
    final String name = String.valueOf(fileName);
    final boolean regular = Files.isRegularFile(file) && !Files.isSymbolicLink(file);
    return regular && name.startsWith(LIBRARY_PREFIX) && name.contains(LIBRARY_SUFFIX);
  }

  boolean isSupported() {
    final List<Download> packages = this.findPackages();
    return !packages.isEmpty();
  }

  List<Path> readLibraries() {
    final List<String> loadOrder = this.readLoadOrder();
    return loadOrder.stream().map(this::resolveLibrary).toList();
  }

  private List<String> readLoadOrder() {
    final Path file = this.folder.resolve(LOAD_ORDER_FILE);
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      final String message = "Installation of %s at %s could not be read!".formatted(this.name, this.folder);
      throw new InstallationException(message, exception);
    }
  }

  private Path resolveLibrary(final String library) {
    final Path resolved = this.folder.resolve(library).normalize();
    final boolean inside = resolved.startsWith(this.folder) && !resolved.equals(this.folder);
    if (!inside) {
      final String message = "Library %s of %s is outside its installation!".formatted(library, this.name);
      throw new InstallationException(message);
    }

    return resolved;
  }

  List<Path> listLibraryFolders() {
    final List<Path> libraries = this.readLibraries();
    return libraries.stream().map(this::getContainingFolder).distinct().toList();
  }

  private Path getContainingFolder(final Path library) {
    final @Nullable Path parent = library.getParent();
    return Objects.requireNonNull(parent, "A library inside the installation always has a parent!");
  }

  Path getLibraryFolder() {
    final List<Path> folders = this.getTripletFolders(this.folder);
    return folders.getFirst();
  }

  Path getFolder() {
    return this.folder;
  }

  private String getTriplet() {
    final Arch arch = this.platform.getArch();
    if (arch == Arch.ARM) {
      return AARCH64_TRIPLET;
    }

    return X86_64_TRIPLET;
  }

  private Path getParent() {
    final Path absolute = this.folder.toAbsolutePath();
    final @Nullable Path parent = absolute.getParent();
    return Objects.requireNonNull(parent, "An installation folder inside the cache always has a parent!");
  }
}

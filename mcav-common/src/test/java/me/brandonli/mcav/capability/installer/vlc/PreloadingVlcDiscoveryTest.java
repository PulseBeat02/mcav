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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PreloadingVlcDiscoveryTest {

  private static final Path LIBRARY_DIRECTORY = Path.of("installation", "usr", "lib", "aarch64-linux-gnu");

  private static final Path TAGLIB_PLUGIN_FOLDER = Path.of("vlc", "plugins", "meta_engine");
  private static final String TAGLIB_PLUGIN_FILE = "libtaglib_plugin.so";

  private static final String PRELOAD = "preload";
  private static final String PLUGIN = "plugin";
  private static final String SYSTEM = "system";
  private static final String LOAD = "load";

  @TempDir
  private Path directory;

  private List<String> calls;
  private @Nullable String loadedPlugin;
  private FakeDiscovery delegate;
  private PreloadingVlcDiscovery discovery;

  @BeforeEach
  void setUp() {
    this.calls = new ArrayList<>();
    this.delegate = new FakeDiscovery(this.calls);
    this.discovery = new PreloadingVlcDiscovery(this::preload, this::loadPlugin, this.delegate);
  }

  @Test
  void shouldPreloadBeforeLoadingTheInstalledLibraries() {
    this.delegate.found = true;

    final boolean loaded = this.discovery.loadInstalledLibraries(LIBRARY_DIRECTORY);
    final List<String> expected = List.of(PRELOAD, LOAD);
    assertTrue(loaded);
    assertEquals(expected, this.calls);
    assertEquals(LIBRARY_DIRECTORY, this.delegate.loadedDirectory);
  }

  @Test
  void shouldLoadTheTaglibPluginBetweenThePreloadAndTheInstalledLibraries() throws IOException {
    final Path plugin = this.createTaglibPlugin();

    this.discovery.loadInstalledLibraries(this.directory);
    final List<String> expected = List.of(PRELOAD, PLUGIN, LOAD);
    final String expectedPlugin = plugin.toString();
    assertEquals(expected, this.calls);
    assertEquals(expectedPlugin, this.loadedPlugin);
  }

  @Test
  void shouldLoadTheInstalledLibrariesWhenTheTaglibPluginDoesNotLoad() throws IOException {
    this.createTaglibPlugin();
    this.delegate.found = true;
    final PreloadingVlcDiscovery failingDiscovery = new PreloadingVlcDiscovery(this::preload, this::failToLoadPlugin, this.delegate);

    final boolean loaded = failingDiscovery.loadInstalledLibraries(this.directory);
    final List<String> expected = List.of(PRELOAD, LOAD);
    assertTrue(loaded);
    assertEquals(expected, this.calls);
  }

  @Test
  void shouldReportInstalledLibrariesThatDoNotLoad() {
    final boolean loaded = this.discovery.loadInstalledLibraries(LIBRARY_DIRECTORY);
    assertFalse(loaded);
  }

  @Test
  void shouldDiscoverTheSystemInstallationWithoutPreloading() {
    this.delegate.found = true;

    final boolean found = this.discovery.discoverSystemInstallation();
    final List<String> expected = List.of(SYSTEM);
    assertTrue(found);
    assertEquals(expected, this.calls);
  }

  @Test
  void shouldReportAMissingSystemInstallation() {
    final boolean found = this.discovery.discoverSystemInstallation();
    assertFalse(found);
  }

  @Test
  void shouldReportTheDiscoveredPathOfTheDelegate() {
    this.delegate.discoveredPath = LIBRARY_DIRECTORY;

    final @Nullable Path discoveredPath = this.discovery.getDiscoveredPath();
    assertEquals(LIBRARY_DIRECTORY, discoveredPath);
  }

  @Test
  void shouldReportNoPathWhenTheDelegateHasNone() {
    final @Nullable Path discoveredPath = this.discovery.getDiscoveredPath();
    assertNull(discoveredPath);
  }

  private Path createTaglibPlugin() throws IOException {
    final Path folder = this.directory.resolve(TAGLIB_PLUGIN_FOLDER);
    Files.createDirectories(folder);

    final Path plugin = folder.resolve(TAGLIB_PLUGIN_FILE);
    return Files.createFile(plugin);
  }

  private void preload() {
    this.calls.add(PRELOAD);
  }

  private void loadPlugin(final String path) {
    this.calls.add(PLUGIN);
    this.loadedPlugin = path;
  }

  private void failToLoadPlugin(final String path) {
    final String message = "Plugin %s could not be loaded!".formatted(path);
    throw new UnsatisfiedLinkError(message);
  }

  private static final class FakeDiscovery implements VlcDiscovery {

    private final List<String> calls;

    private boolean found;
    private @Nullable Path discoveredPath;
    private @Nullable Path loadedDirectory;

    FakeDiscovery(final List<String> calls) {
      this.calls = calls;
    }

    @Override
    public boolean discoverSystemInstallation() {
      this.calls.add(SYSTEM);
      return this.found;
    }

    @Override
    public @Nullable Path getDiscoveredPath() {
      return this.discoveredPath;
    }

    @Override
    public boolean loadInstalledLibraries(final Path libraryDirectory) {
      this.calls.add(LOAD);
      this.loadedDirectory = libraryDirectory;
      return this.found;
    }
  }
}

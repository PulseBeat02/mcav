/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.sandbox.dependency;

import com.alessiodp.libby.BukkitLibraryManager;
import com.alessiodp.libby.Library;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.utils.IOUtils;

public final class DependencyManager {

  private final MCAV plugin;

  public DependencyManager(final MCAV plugin) {
    this.plugin = plugin;
  }

  public void loadDependencies() {
    final BukkitLibraryManager manager = new BukkitLibraryManager(this.plugin);
    final List<String> repos = this.getRepositories();
    for (final String repo : repos) {
      manager.addRepository(repo);
    }
    final List<String[]> dependencies = this.getDependencies();
    final List<Library> libraries = new ArrayList<>();
    for (final String[] dependency : dependencies) {
      final String groupId = dependency[0];
      final String artifactId = dependency[1].replace("{}", ".");
      final String version = dependency[2].replace("{}", ".");
      final Library library = Library.builder().groupId(groupId).artifactId(artifactId).version(version).build();
      libraries.add(library);
    }
    final Library[] libs = libraries.toArray(new Library[0]);
    manager.loadLibraries(libs);
  }

  public List<String[]> getDependencies() {
    try (
      final Reader inputStream = IOUtils.getResourceAsStreamReader("/dependencies.txt");
      final BufferedReader reader = new BufferedReader(inputStream)
    ) {
      return reader.lines().map(line -> line.replace(".", "{}").split(":")).collect(Collectors.toList());
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  public List<String> getRepositories() {
    try (
      final Reader inputStream = IOUtils.getResourceAsStreamReader("/repositories.txt");
      final BufferedReader reader = new BufferedReader(inputStream)
    ) {
      return reader.lines().collect(Collectors.toList());
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}

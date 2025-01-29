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
package me.brandonli.mcav.installer;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides functionality to manage and load dependencies dynamically
 * into an application at runtime. It allows downloading and loading of dependencies
 * from a specified location and provides methods for initialization with configurable
 * parameters.
 */
public class MCAVInstaller {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAVInstaller.class);

  private final Path folder;
  private final ClassLoader classLoader;

  MCAVInstaller(final Path folder, final ClassLoader classLoader) {
    this.folder = folder;
    this.classLoader = classLoader;
  }

  /**
   * Creates an instance of MCAVInstaller with the specified folder and class loader.
   *
   * @param folder      the folder path where dependencies or resources will be managed
   * @param classLoader the class loader used to load dependencies at runtime
   * @return an instance of MCAVInstaller initialized with the provided parameters
   */
  public static MCAVInstaller injector(final Path folder, final ClassLoader classLoader) {
    requireNonNull(folder);
    requireNonNull(classLoader);
    return new MCAVInstaller(folder, classLoader);
  }

  /**
   * Creates a new {@link MCAVInstaller} instance by extracting the class loader
   * from the provided object's class and using it along with the specified folder path.
   *
   * @param folder the folder path where dependencies should be managed or loaded.
   * @param object the object whose class loader will be utilized for loading resources.
   * @return a new instance of {@link MCAVInstaller} initialized with the specified folder and object's class loader.
   */
  public static MCAVInstaller injector(final Path folder, final Object object) {
    final Class<?> clazz = object.getClass();
    final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
    return injector(folder, classLoader);
  }

  /**
   * Downloads and loads the required dependencies for the given artifact. Progress updates are
   * provided through the specified progress logger during the download and loading process.
   *
   * @param artifact the artifact whose dependencies need to be downloaded and loaded
   * @param loader   the JarLoader implementation used for dynamically loading the dependencies
   */
  public void loadMCAVDependencies(final Artifact artifact, final JarLoader loader) {
    try (final InstallationManager manager = new InstallationManager(this.folder)) {
      LOGGER.info("Downloading dependencies...");
      final Collection<Path> jars = manager.downloadDependencies(artifact);
      LOGGER.info("Loading dependencies...");
      loader.loadJars(jars, this.classLoader);
      LOGGER.info("Successfully loaded dependencies!");
    }
  }

  /**
   * Downloads and loads the required dependencies for the given artifact. This method provides
   * progress updates via the specified progress logger during the download and loading processes.
   *
   * @param artifact the artifact for which dependencies need to be downloaded and loaded
   */
  public void loadMCAVDependencies(final Artifact artifact) {
    this.loadMCAVDependencies(artifact, JarLoader.DEFAULT_URL_LOADER);
  }
}

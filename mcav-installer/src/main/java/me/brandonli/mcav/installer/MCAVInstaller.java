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

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * This class provides functionality to manage and load dependencies dynamically
 * into an application at runtime. It allows downloading and loading of dependencies
 * from a specified location and provides methods for initialization with configurable
 * parameters.
 */
public class MCAVInstaller {

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
    final ClassLoader classLoader = clazz.getClassLoader();
    return injector(folder, classLoader);
  }

  /**
   * Downloads and loads the required dependencies for the application. The method
   * provides feedback through the specified progress logger as the dependencies
   * are downloaded and loaded into the system.
   *
   * @param progressLogger a Consumer function to handle log messages indicating
   *                       the progress of the operation
   * @return the Path object representing the location of the downloaded dependencies
   * @throws IOException if an error occurs during the download or loading of dependencies
   */
  public Path loadMCAVDependencies(final Consumer<String> progressLogger) throws IOException {
    final Path jar = HttpUtils.downloadDependencies(progressLogger, this.folder);
    progressLogger.accept(String.format("Loading dependencies from %s", jar));
    LoaderUtils.loadJarPath(jar, this.classLoader);
    progressLogger.accept("Successfully loaded dependencies!");
    return jar;
  }
}

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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows you to install and manage mcav dependencies for many artifacts. Downloads all transitive dependencies as
 * well. In general, you should use this class to construct your own ClassLoader that contains references to the
 * downloaded jars.
 *
 * <pre><code>
 *   final Path downloaded = Path.of("dependencies");
 *   final Class&lt;?&gt; clazz = this.getClass();
 *   final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
 *   final MCAVInstaller installer = MCAVInstaller.injector(downloaded, classLoader);
 *   installer.loadMCAVDependencies(Artifact.COMMON);
 * </code></pre>
 *
 * @deprecated You shouldn't load dependencies at runtime unless you really have to. In that case, you should allow
 * other libraries to handle the large dependencies for you.
 */
@Deprecated
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
   * @return an instance of MCAVInstaller
   */
  public static MCAVInstaller injector(final Path folder, final ClassLoader classLoader) {
    requireNonNull(folder);
    requireNonNull(classLoader);
    return new MCAVInstaller(folder, classLoader);
  }

  /**
   * Creates an instance of MCAVInstaller with the specified folder and the class loader of the provided object.
   *
   * @param folder the folder path where dependencies or resources will be managed
   * @param object an object whose class loader will be used to load dependencies
   * @return an instance of MCAVInstaller
   */
  public static MCAVInstaller injector(final Path folder, final Object object) {
    final Class<?> clazz = object.getClass();
    final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
    return injector(folder, classLoader);
  }

  /**
   * Downloads and loads the required dependencies for the given artifact.
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
   * Downloads and loads the required dependencies for the given artifact using the default JarLoader.
   *
   * @param artifact the artifact for which dependencies need to be downloaded and loaded
   */
  public void loadMCAVDependencies(final Artifact artifact) {
    this.loadMCAVDependencies(artifact, JarLoader.DEFAULT_URL_LOADER);
  }
}

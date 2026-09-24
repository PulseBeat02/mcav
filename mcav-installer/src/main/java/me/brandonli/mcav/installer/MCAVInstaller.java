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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads modules of the library, or any other Maven artifact, at runtime and adds them to a class loader.
 *
 * <p>This keeps plugin jars small: the plugin ships only the installer and downloads the library on its first
 * start. Shading the library into the plugin remains the simpler choice where jar size does not matter.
 *
 * <pre><code>
 *   final Path dataPath = dataFolder.toPath();
 *   final Path folder = dataPath.resolve("libs");
 *   final MCAVInstaller installer = MCAVInstaller.injector(folder, this);
 *   installer.loadMCAVDependencies(Artifact.COMMON);
 *   installer.loadMCAVDependencies(Artifact.BUKKIT);
 * </code></pre>
 */
public final class MCAVInstaller {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAVInstaller.class);

  private final Logger logger;
  private final Path folder;
  private final ClassLoader classLoader;
  private final Function<Path, InstallationManager> managerFactory;

  MCAVInstaller(final Path folder, final ClassLoader classLoader) {
    this(folder, classLoader, InstallationManager::new);
  }

  /**
   * Creates an installer with another way to create the {@link InstallationManager}, for example one that
   * downloads from a repository on the file system.
   *
   * @param folder         the folder the jars are copied into
   * @param classLoader    the class loader the jars are added to
   * @param managerFactory creates a manager for the folder
   */
  MCAVInstaller(final Path folder, final ClassLoader classLoader, final Function<Path, InstallationManager> managerFactory) {
    this(folder, classLoader, managerFactory, LOGGER);
  }

  /** Creates an installer with a diagnostic sink, allowing hosts and tests to capture installation timing. */
  MCAVInstaller(
    final Path folder,
    final ClassLoader classLoader,
    final Function<Path, InstallationManager> managerFactory,
    final Logger logger
  ) {
    this.folder = folder;
    this.classLoader = classLoader;
    this.managerFactory = managerFactory;
    this.logger = logger;
  }

  /**
   * Gets the folder the jars are copied into.
   *
   * @return the folder
   */
  Path getFolder() {
    return this.folder;
  }

  /**
   * Gets the class loader the jars are added to.
   *
   * @return the class loader
   */
  ClassLoader getClassLoader() {
    return this.classLoader;
  }

  /**
   * Creates an installer that loads into a class loader.
   *
   * @param folder      the folder the jars are copied into
   * @param classLoader the class loader the jars are added to
   * @return the installer
   * @throws NullPointerException if the folder or the class loader is null
   */
  public static MCAVInstaller injector(final Path folder, final ClassLoader classLoader) {
    Objects.requireNonNull(folder, "Folder must not be null");
    Objects.requireNonNull(classLoader, "Class loader must not be null");
    return new MCAVInstaller(folder, classLoader);
  }

  /**
   * Creates an installer that loads into the class loader of an object, typically the plugin instance.
   *
   * @param folder the folder the jars are copied into
   * @param owner  the object whose class loader receives the jars
   * @return the installer
   * @throws NullPointerException if the folder or the owner is null
   * @throws JarInjectorException if the class of the owner was loaded by the bootstrap class loader
   */
  public static MCAVInstaller injector(final Path folder, final Object owner) {
    Objects.requireNonNull(folder, "Folder must not be null");
    Objects.requireNonNull(owner, "Owner must not be null");
    final Class<?> type = owner.getClass();
    final ClassLoader classLoader = type.getClassLoader();
    if (classLoader == null) {
      throw new JarInjectorException("Classes of the bootstrap class loader cannot receive jars");
    }
    return injector(folder, classLoader);
  }

  /**
   * Downloads a module of the library in the default version and adds it to the class loader.
   *
   * @param artifact the module
   * @throws NullPointerException  if the artifact is null
   * @throws InstallationException if the module cannot be downloaded
   * @throws JarInjectorException  if the jars cannot be added to the class loader
   */
  public void loadMCAVDependencies(final Artifact artifact) {
    Objects.requireNonNull(artifact, "Artifact must not be null");
    this.loadMCAVDependencies(artifact, JarLoader.DEFAULT_URL_LOADER);
  }

  /**
   * Downloads a module of the library in the default version and adds it with a custom loader.
   *
   * @param artifact the module
   * @param loader   how the jars are added
   * @throws NullPointerException  if the artifact or the loader is null
   * @throws InstallationException if the module cannot be downloaded
   * @throws JarInjectorException  if the loader cannot add the jars
   */
  public void loadMCAVDependencies(final Artifact artifact, final JarLoader loader) {
    Objects.requireNonNull(artifact, "Artifact must not be null");
    Objects.requireNonNull(loader, "Loader must not be null");
    final String artifactId = artifact.getArtifactId();
    this.loadDependencies(Artifact.GROUP_ID, artifactId, Artifact.DEFAULT_VERSION, loader);
  }

  /**
   * Downloads any Maven artifact with its runtime dependencies and adds it to the class loader.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @param loader     how the jars are added
   * @return the jars that were added
   * @throws NullPointerException  if any argument is null
   * @throws InstallationException if the artifact or one of its dependencies cannot be downloaded
   * @throws JarInjectorException  if the loader cannot add the jars
   */
  public List<Path> loadDependencies(final String groupId, final String artifactId, final String version, final JarLoader loader) {
    Objects.requireNonNull(groupId, "Group id must not be null");
    Objects.requireNonNull(artifactId, "Artifact id must not be null");
    Objects.requireNonNull(version, "Version must not be null");
    Objects.requireNonNull(loader, "Loader must not be null");

    final long start = System.currentTimeMillis();
    this.logger.info("Downloading {}:{}:{} and its dependencies...", groupId, artifactId, version);
    final List<Path> jars;
    try (final InstallationManager manager = this.managerFactory.apply(this.folder)) {
      jars = manager.downloadDependencies(groupId, artifactId, version);
    }
    loader.loadJars(jars, this.classLoader);

    final long end = System.currentTimeMillis();
    final long elapsed = end - start;
    final int count = jars.size();
    this.logger.info("Loaded {} jars for {} in {} ms", count, artifactId, elapsed);
    return jars;
  }
}

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
package me.brandonli.mcav.plugin;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jpenilla.gremlin.runtime.DependencyCache;
import xyz.jpenilla.gremlin.runtime.DependencyResolver;
import xyz.jpenilla.gremlin.runtime.DependencySet;
import xyz.jpenilla.gremlin.runtime.ResolvedDependencySet;
import xyz.jpenilla.gremlin.runtime.logging.GremlinLogger;
import xyz.jpenilla.gremlin.runtime.logging.Slf4jGremlinLogger;
import xyz.jpenilla.gremlin.runtime.platformsupport.PaperClasspathAppender;

/**
 * Downloads the libraries of the plugin before it is loaded and puts them on its classpath. The libraries are
 * listed in the {@code mcav/plugin/dependencies.txt} that Gremlin generates at build time, and are cached in
 * {@code libraries/mcav}.
 *
 * <p>The modules of mcav the server downloads are snapshots, published apart from the plugin: modules older than the
 * plugin's code lack classes it uses. The build lists those classes in {@value #REQUIRED_CLASSES_RESOURCE}, and the loader
 * refuses to start the plugin with a message saying so when a downloaded module lacks one, where the server would
 * otherwise fail on the first missing class.
 *
 * <p>The loader runs before the downloaded modules are on the classpath, so it reports failures with JDK exceptions
 * instead of {@link PluginException}, whose superclass {@code MCAVException} comes from one of those modules.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class MCAVLoader implements PluginLoader {

  private static final Logger GREMLIN_LOGGER = LoggerFactory.getLogger("Gremlin");

  private static final String REQUIRED_CLASSES_RESOURCE = "META-INF/mcav/required-classes.txt";

  private static final String DEPENDENCIES_RESOURCE = "mcav/plugin/dependencies.txt";

  private static final String CLASS_SUFFIX = ".class";

  private static final int MAXIMUM_NAMED_CLASSES = 5;

  private static final String LIBRARIES_DIRECTORY = "libraries/mcav";

  private static final char PATH_SEPARATOR = '/';
  private static final char PACKAGE_SEPARATOR = '.';

  private static final String NAME_SEPARATOR = ", ";

  private final Path libraries;

  private final List<String> requiredClasses;

  /**
   * Creates the loader. Paper creates it for you.
   *
   * @since 1.0.0
   */
  public MCAVLoader() {
    this(Path.of(LIBRARIES_DIRECTORY), readRequiredClasses(requireNonNull(MCAVLoader.class.getClassLoader()))); // checker framework
  }

  @VisibleForTesting
  MCAVLoader(final Path libraries, final Collection<String> requiredClasses) {
    this.libraries = libraries;
    this.requiredClasses = List.copyOf(requiredClasses);
  }

  @VisibleForTesting
  static List<String> readRequiredClasses(final ClassLoader classLoader) {
    final InputStream input = classLoader.getResourceAsStream(REQUIRED_CLASSES_RESOURCE);
    if (input == null) {
      return List.of();
    }

    try (input) {
      final byte[] bytes = input.readAllBytes();
      final String text = new String(bytes, StandardCharsets.UTF_8);
      return text
        .lines()
        .filter(line -> !line.isBlank())
        .toList();
    } catch (final IOException exception) {
      final String message = "Cannot read the classes the plugin needs from its modules!";
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Resolves the libraries listed in {@code mcav/plugin/dependencies.txt}, downloading those missing from the cache folder,
   * checks that the downloaded modules are not older than the plugin, adds them to the classpath of the plugin, and
   * removes cached files that are no longer listed. Paper calls this once, before the plugin class is loaded.
   *
   * @param classpathBuilder the classpath of the plugin, which receives the library jars
   * @throws NullPointerException  if the classpath builder is {@code null}
   * @throws IllegalStateException if a downloaded module lacks a class the plugin uses
   * @since 1.0.0
   */
  @Override
  public void classloader(final @NonNull PluginClasspathBuilder classpathBuilder) {
    Preconditions.checkNotNull(classpathBuilder, "Classpath builder must not be null!");

    final Class<?> loaderClass = this.getClass();
    final ClassLoader nullableClassLoader = loaderClass.getClassLoader();
    final ClassLoader classLoader = requireNonNull(nullableClassLoader); // checker framework
    final DependencySet dependencies = DependencySet.readFromClasspathResource(classLoader, DEPENDENCIES_RESOURCE);
    final DependencyCache cache = new DependencyCache(this.libraries);

    final GremlinLogger gremlinLogger = new Slf4jGremlinLogger(GREMLIN_LOGGER);
    try (final DependencyResolver downloader = new DependencyResolver(gremlinLogger)) {
      final ResolvedDependencySet resolvedDependencies = downloader.resolve(dependencies, cache);
      final Set<Path> jars = resolvedDependencies.jarFiles();
      this.checkModules(jars);
      final PaperClasspathAppender appender = new PaperClasspathAppender(classpathBuilder);
      appender.append(jars);
    }
    cache.cleanup();
  }

  @VisibleForTesting
  void checkModules(final Collection<Path> jars) {
    final Set<String> entries = this.listEntries(jars);
    final List<String> missing = this.findMissing(entries);
    if (missing.isEmpty()) {
      return;
    }

    final int count = missing.size();
    final List<String> named = missing.subList(0, Math.min(MAXIMUM_NAMED_CLASSES, count));
    final String names = String.join(NAME_SEPARATOR, named);
    final String message =
      "The MCAV modules the server downloaded are older than this plugin, which uses classes they lack (%s), such as %s. Publish the modules of the plugin's own version, or set -Pmcav.e2e=true when you build the plugin!".formatted(
        count,
        names
      );
    throw new IllegalStateException(message);
  }

  private Set<String> listEntries(final Collection<Path> jars) {
    final Set<String> entries = new HashSet<>();
    for (final Path jar : jars) {
      try (final ZipFile file = new ZipFile(jar.toFile())) {
        final List<String> names = file.stream().map(ZipEntry::getName).toList();
        entries.addAll(names);
      } catch (final IOException exception) {
        final String message = "Downloaded library %s could not be read!".formatted(jar);
        throw new UncheckedIOException(message, exception);
      }
    }
    return entries;
  }

  private List<String> findMissing(final Set<String> entries) {
    return this.requiredClasses
      .stream()
      .filter(required -> !entries.contains("%s%s".formatted(required, CLASS_SUFFIX)))
      .map(required -> required.replace(PATH_SEPARATOR, PACKAGE_SEPARATOR))
      .toList();
  }

  @VisibleForTesting
  Path getLibraries() {
    return this.libraries;
  }
}

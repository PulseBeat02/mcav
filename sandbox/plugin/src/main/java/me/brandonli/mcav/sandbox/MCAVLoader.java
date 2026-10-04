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
package me.brandonli.mcav.sandbox;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * listed in the {@code dependencies.txt} that Gremlin generates at build time, and are cached in
 * {@code libraries/mcav}.
 *
 * <p>The modules of mcav the server downloads are snapshots, published apart from the plugin: modules older than the
 * plugin's code lack classes it uses. The build lists those classes in {@value #REQUIRED_CLASSES}, and the loader
 * refuses to start the plugin with a message saying so when a downloaded module lacks one, where the server would
 * otherwise fail on the first missing class.
 */
public final class MCAVLoader implements PluginLoader {

  /** The classes of the downloaded modules that the plugin's code uses, one internal name per line, from the build. */
  static final String REQUIRED_CLASSES = "META-INF/mcav/required-classes.txt";

  private static final String CLASS_SUFFIX = ".class";

  private static final int NAMED_MISSING = 5;

  private final Path libraries;

  private final List<String> requiredClasses;

  /**
   * Constructs the loader. Paper creates it for you.
   */
  public MCAVLoader() {
    this(Path.of("libraries/mcav"), readRequiredClasses(requireNonNull(MCAVLoader.class.getClassLoader())));
  }

  /**
   * Constructs a loader that caches the libraries in another folder and checks the downloaded modules for other classes.
   *
   * @param libraries       the cache folder
   * @param requiredClasses the classes, by internal name, the downloaded modules must have
   */
  @VisibleForTesting
  MCAVLoader(final Path libraries, final Collection<String> requiredClasses) {
    this.libraries = libraries;
    this.requiredClasses = List.copyOf(requiredClasses);
  }

  /**
   * Reads the classes the plugin's code uses from the downloaded modules, as the build listed them.
   *
   * @param classLoader the class loader of the plugin
   * @return the classes by internal name; none when the plugin was built without the list
   * @throws UncheckedIOException if the list cannot be read
   */
  @VisibleForTesting
  static List<String> readRequiredClasses(final ClassLoader classLoader) {
    final InputStream list = classLoader.getResourceAsStream(REQUIRED_CLASSES);
    if (list == null) {
      return List.of();
    }
    try (list) {
      return new String(list.readAllBytes(), StandardCharsets.UTF_8)
        .lines()
        .filter(line -> !line.isBlank())
        .toList();
    } catch (final IOException exception) {
      throw new UncheckedIOException("Cannot read the classes the plugin needs from its modules", exception);
    }
  }

  /**
   * Checks that downloaded jars hold every class the plugin needs from them.
   *
   * @param requiredClasses the classes, by internal name
   * @param jars            the downloaded jars
   * @throws IllegalStateException if a class is in none of the jars: the modules are older than the plugin
   * @throws UncheckedIOException  if a jar cannot be read
   */
  @VisibleForTesting
  static void checkModules(final Collection<String> requiredClasses, final Collection<Path> jars) {
    final Set<String> entries = new HashSet<>();
    for (final Path jar : jars) {
      try (final ZipFile file = new ZipFile(jar.toFile())) {
        file.stream().map(ZipEntry::getName).forEach(entries::add);
      } catch (final IOException exception) {
        throw new UncheckedIOException("Cannot read the downloaded library " + jar, exception);
      }
    }
    final List<String> missing = new ArrayList<>();
    for (final String required : requiredClasses) {
      if (!entries.contains(required + CLASS_SUFFIX)) {
        missing.add(required.replace('/', '.'));
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
        String.format(
          Locale.getDefault(Locale.Category.FORMAT),
          "The mcav modules the server downloaded are older than this plugin, which uses classes they lack (%d), such as %s. " +
            "Publish the modules of the plugin's own version, or build the plugin with -Pmcav.e2e=true",
          missing.size(),
          String.join(", ", missing.subList(0, Math.min(NAMED_MISSING, missing.size())))
        )
      );
    }
  }

  /**
   * Gets the folder the libraries are cached in.
   *
   * @return the cache folder
   */
  @VisibleForTesting
  Path getLibraries() {
    return this.libraries;
  }

  /**
   * Resolves the libraries listed in {@code dependencies.txt}, downloading those missing from the cache folder,
   * checks that the downloaded modules are not older than the plugin, adds them to the classpath of the plugin, and
   * removes cached files that are no longer listed. Paper calls this once, before the plugin class is loaded.
   *
   * @param classpathBuilder the classpath of the plugin, which receives the library jars
   * @throws NullPointerException  if the classpath builder is {@code null}
   * @throws IllegalStateException if a downloaded module lacks a class the plugin uses
   */
  @Override
  public void classloader(final @NonNull PluginClasspathBuilder classpathBuilder) {
    Preconditions.checkNotNull(classpathBuilder, "Classpath builder must not be null");
    final Class<?> loaderClass = this.getClass();
    final ClassLoader nullableClassLoader = loaderClass.getClassLoader();
    final ClassLoader classLoader = requireNonNull(nullableClassLoader);
    final DependencySet dependencies = DependencySet.readDefault(classLoader);
    final DependencyCache cache = new DependencyCache(this.libraries);

    final Logger logger = LoggerFactory.getLogger("Gremlin");
    final GremlinLogger gremlinLogger = new Slf4jGremlinLogger(logger);
    try (final DependencyResolver downloader = new DependencyResolver(gremlinLogger)) {
      final ResolvedDependencySet resolvedDependencies = downloader.resolve(dependencies, cache);
      final Set<Path> jars = resolvedDependencies.jarFiles();
      checkModules(this.requiredClasses, jars);
      final PaperClasspathAppender appender = new PaperClasspathAppender(classpathBuilder);
      appender.append(jars);
    }
    cache.cleanup();
  }
}

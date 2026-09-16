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
import java.nio.file.Path;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;
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
 */
public final class MCAVLoader implements PluginLoader {

  private final Path libraries;

  /**
   * Constructs the loader. Paper creates it for you.
   */
  public MCAVLoader() {
    this(Path.of("libraries/mcav"));
  }

  /**
   * Constructs a loader that caches the libraries in another folder.
   *
   * @param libraries the cache folder
   */
  @VisibleForTesting
  MCAVLoader(final Path libraries) {
    this.libraries = libraries;
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
   * adds them to the classpath of the plugin, and removes cached files that are no longer listed. Paper calls this
   * once, before the plugin class is loaded.
   *
   * @param classpathBuilder the classpath of the plugin, which receives the library jars
   * @throws NullPointerException if the classpath builder is {@code null}
   */
  @Override
  public void classloader(final @NonNull PluginClasspathBuilder classpathBuilder) {
    Preconditions.checkNotNull(classpathBuilder, "Classpath builder must not be null");
    final Class<?> loaderClass = this.getClass();
    final ClassLoader nullableClassLoader = loaderClass.getClassLoader();
    final ClassLoader classLoader = requireNonNull(nullableClassLoader);
    final DependencySet dependencies = DependencySet.readDefault(classLoader);
    final DependencyCache cache = new DependencyCache(this.libraries);

    final org.slf4j.Logger logger = LoggerFactory.getLogger("Gremlin");
    final GremlinLogger gremlinLogger = new Slf4jGremlinLogger(logger);
    try (final DependencyResolver downloader = new DependencyResolver(gremlinLogger)) {
      final ResolvedDependencySet resolvedDependencies = downloader.resolve(dependencies, cache);
      final Set<Path> jars = resolvedDependencies.jarFiles();
      final PaperClasspathAppender appender = new PaperClasspathAppender(classpathBuilder);
      appender.append(jars);
    }
    cache.cleanup();
  }
}

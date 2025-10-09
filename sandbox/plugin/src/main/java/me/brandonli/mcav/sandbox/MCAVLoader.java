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

public final class MCAVLoader implements PluginLoader {

  @Override
  @SuppressWarnings("UnstableApiUsage")
  public void classloader(final @NonNull PluginClasspathBuilder classpathBuilder) {
    final Path libs = Path.of("libraries/mcav");
    final Class<?> clazz = this.getClass();
    final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
    final DependencySet deps = DependencySet.readDefault(classLoader);
    final DependencyCache cache = new DependencyCache(libs);
    final org.slf4j.Logger logger = LoggerFactory.getLogger("Gremlin");
    final GremlinLogger gremlinLogger = new Slf4jGremlinLogger(logger);
    try (final DependencyResolver downloader = new DependencyResolver(gremlinLogger)) {
      final ResolvedDependencySet resolvedDeps = downloader.resolve(deps, cache);
      final Set<Path> jars = resolvedDeps.jarFiles();
      final PaperClasspathAppender appender = new PaperClasspathAppender(classpathBuilder);
      appender.append(jars);
    }
    cache.cleanup();
  }
}

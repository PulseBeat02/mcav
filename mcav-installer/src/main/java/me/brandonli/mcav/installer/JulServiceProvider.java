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

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Hands the messages of the installer, and of the resolver it bundles, to {@code java.util.logging}, which a server
 * shows in its console. The installer bundles SLF4J under its own package names, so that it never meets the SLF4J of a
 * plugin or a server, and that bundled SLF4J finds no provider of theirs: without this one it would drop every message
 * and warn on the standard error that it found none.
 *
 * <p>SLF4J finds this class through the service file of the installer's jar; it is not meant to be used otherwise.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class JulServiceProvider implements SLF4JServiceProvider {

  static final String API_VERSION = "2.0.99";

  private final ILoggerFactory loggers;
  private final IMarkerFactory markers;
  private final MDCAdapter context;

  /**
   * Creates the provider; SLF4J calls this constructor through the service file of the jar.
   *
   * @since 1.0.0
   */
  public JulServiceProvider() {
    this.loggers = JulLogger::new;
    this.markers = new BasicMarkerFactory();
    this.context = new NOPMDCAdapter();
  }

  /**
   * Prepares nothing: the factories are ready once the provider is constructed.
   *
   * @since 1.0.0
   */
  @Override
  public void initialize() {}

  /**
   * Returns the factory of loggers, each of which writes to the {@code java.util.logging} logger of its name.
   *
   * @return the factory
   * @since 1.0.0
   */
  @Override
  public ILoggerFactory getLoggerFactory() {
    return this.loggers;
  }

  /**
   * Returns SLF4J's basic factory of markers; {@code java.util.logging} has no markers, so they are dropped.
   *
   * @return the factory
   * @since 1.0.0
   */
  @Override
  public IMarkerFactory getMarkerFactory() {
    return this.markers;
  }

  /**
   * Returns an adapter that keeps no context, as {@code java.util.logging} has none.
   *
   * @return the adapter
   * @since 1.0.0
   */
  @Override
  public MDCAdapter getMDCAdapter() {
    return this.context;
  }

  /**
   * Returns the version of the SLF4J API this provider is written against.
   *
   * @return the API version {@code 2.0.99}
   * @since 1.0.0
   */
  @Override
  public String getRequestedApiVersion() {
    return API_VERSION;
  }
}

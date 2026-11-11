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
package me.brandonli.mcav.module;

/**
 * An optional part of the library that is started and stopped together with it, such as the Bukkit, Discord, or
 * HTTP integrations.
 *
 * <p>Modules are created by {@link ModuleLoader} through a no-argument constructor, which may be package-private,
 * and are passed to {@link me.brandonli.mcav.MCAVApi#install(Class[])} by class.
 *
 * <p>The module loader starts and stops modules one at a time, so implementations need not synchronize their lifecycle.
 *
 * @since 1.0.0
 */
public interface MCAVModule {
  /**
   * Starts the module. Called once while the library is installed.
   *
   * @throws ModuleException if the module cannot start
   * @since 1.0.0
   */
  void start();

  /**
   * Stops the module and releases every resource it holds. Called once while the library is released, or right after a
   * failed {@link #start()}, so the module can release what it acquired before it failed.
   *
   * @since 1.0.0
   */
  void stop();

  /**
   * Returns a short, unique name of the module for log messages, such as {@code bukkit}.
   *
   * @return the name of the module
   * @since 1.0.0
   */
  String getModuleName();
}

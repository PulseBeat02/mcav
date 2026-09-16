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
 */
public interface MCAVModule {
  /**
   * Starts the module. Called once while the library is installed.
   *
   * @throws ModuleException if the module cannot start
   */
  void start();

  /**
   * Stops the module and releases every resource it holds. Called once while the library is released.
   */
  void stop();

  /**
   * Gets a short, unique name of the module for log messages, such as {@code bukkit}.
   *
   * @return the name of the module
   */
  String getModuleName();
}

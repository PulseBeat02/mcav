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
package me.brandonli.mcav;

import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.module.MCAVModule;

/**
 * The main API interface for the mcav library, providing methods to check capabilities,
 * install dependencies, and manage resources.
 */
public interface MCAVApi {
  /**
   * Checks whether the specified capability is supported and enabled.
   *
   * @param capability the capability to check
   * @return true if the capability is supported and enabled, false otherwise
   */
  boolean hasCapability(final Capability capability);

  /**
   * Installs all dependencies and resources required for the library's functionality.
   *
   * @param plugins an array of plugin classes to be installed
   */
  void install(final Class<?>... plugins);

  /**
   * Releases resources and performs cleanup operations as required by the library.
   */
  void release();

  /**
   * Retrieves a module of the specified class type.
   *
   * @param moduleClass the class of the module to retrieve
   * @param <T> the type of the module
   * @return an instance of the specified module class, or null if not found
   */
  <T extends MCAVModule> T getModule(final Class<T> moduleClass);
}

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
 * An interface representing a module in the MCAV library.
 */
public interface MCAVModule {
  /**
   * Initializes the module.
   *
   * @throws ModuleException if the module fails to initialize.
   */
  void start();

  /**
   * Stops the module and releases any resources it holds.
   */
  void stop();

  /**
   * Returns the name of the module.
   *
   * @return the name of the module
   */
  String getModuleName();
}

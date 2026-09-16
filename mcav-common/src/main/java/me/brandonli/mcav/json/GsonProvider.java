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
package me.brandonli.mcav.json;

import com.google.gson.Gson;

/**
 * Shares one thread-safe {@link Gson} instance across the library so that every module parses and writes JSON
 * the same way.
 */
public final class GsonProvider {

  private static final Gson GSON = new Gson();

  private GsonProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the shared instance, configured with the Gson defaults.
   *
   * @return the shared instance
   */
  public static Gson getSimple() {
    return GSON;
  }
}

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
 * A provider for Gson instances.
 * <p>
 * This class provides a static method to get a simple Gson instance.
 * </p>
 */
public final class GsonProvider {

  private static final Gson GSON = new Gson();

  /**
   * Get a simple Gson instance.
   *
   * @return a simple Gson instance
   */
  public static Gson getSimple() {
    return GSON;
  }
}

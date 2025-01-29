/*
 * This file is part of mcav, a media playback library for Minecraft
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

import org.bukkit.plugin.Plugin;

/**
 * The {@code MCAVBukkit} class is a utility class designed to manage
 * and provide access to a shared {@link Plugin} instance within a Minecraft Bukkit
 * environment. It cannot be instantiated and provides static methods for
 * manipulation and retrieval of a single plugin instance.
 */
public final class MCAVBukkit {

  private static Plugin PLUGIN;

  private MCAVBukkit() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Injects a Plugin instance into the class for future use.
   *
   * @param plugin the Plugin instance to be injected
   */
  public static void inject(final Plugin plugin) {
    PLUGIN = plugin;
  }

  /**
   * Retrieves the plugin instance associated with the application.
   *
   * @return the current Plugin instance registered with the application.
   */
  public static Plugin getPlugin() {
    return PLUGIN;
  }
}

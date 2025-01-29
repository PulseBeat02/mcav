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
package me.brandonli.mcav.bukkit;

import me.brandonli.mcav.MCAVModule;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import org.bukkit.plugin.Plugin;

/**
 * The main entry point for Bukkit integration. Requires injection of a plugin instance to load
 * listeners and schedulers.
 */
public final class BukkitModule implements MCAVModule {

  private static Plugin PLUGIN;

  public BukkitModule() {
    // no-op
  }

  /**
   * Injects a Plugin instance
   *
   * @param plugin the Plugin instance
   */
  public void inject(final Plugin plugin) {
    PLUGIN = plugin;
    BlockPaletteLookup.init();
    PacketUtils.init();
  }

  /**
   * Retrieves the plugin instance.
   *
   * @return the current Plugin instance
   */
  public static Plugin getPlugin() {
    return PLUGIN;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    // no-op
  }

  @Override
  public String getModuleName() {
    return "bukkit";
  }
}

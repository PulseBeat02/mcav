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
package me.brandonli.mcav.bukkit;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import me.brandonli.mcav.module.MCAVModule;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The entry point of the Bukkit integration.
 *
 * <p>The module only supports the Minecraft version named by {@link ServerEnvironment#SUPPORTED_MINECRAFT_VERSION}.
 * After installing the module, a plugin instance must be injected with {@link #inject(Plugin)} before any Bukkit
 * feature is used, because listeners and tasks have to be registered on behalf of a plugin.
 *
 * <pre><code>
 *   final MCAVApi api = MCAV.api();
 *   api.install(BukkitModule.class);
 *   final BukkitModule module = api.getModule(BukkitModule.class);
 *   module.inject(plugin);
 * </code></pre>
 */
public final class BukkitModule implements MCAVModule {

  private static final String MODULE_NAME = "bukkit";

  private static volatile @Nullable Plugin PLUGIN;

  BukkitModule() {
    // created through MCAVApi#install
  }

  /**
   * Injects the plugin used for registering listeners and scheduling tasks, and builds all lookup tables. Call
   * this method once from {@code onEnable}.
   *
   * @param plugin the plugin instance
   * @throws UnsupportedServerVersionException if the server runs an unsupported Minecraft version
   */
  public void inject(final Plugin plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    ServerEnvironment.checkSupported();

    PLUGIN = plugin;
    BlockPaletteLookup.init();
    PacketUtils.init();
  }

  /**
   * Gets the injected plugin.
   *
   * @return the plugin instance
   * @throws IllegalStateException if no plugin has been injected yet
   */
  public static Plugin getPlugin() {
    final Plugin plugin = PLUGIN;
    if (plugin == null) {
      throw new IllegalStateException("No plugin has been injected, call BukkitModule#inject(Plugin) first");
    }
    return plugin;
  }

  /**
   * Registers the listeners of the module again if a plugin was injected before, so the module works again after
   * it was stopped. Does nothing before the first injection, because {@link #inject(Plugin)} starts the module
   * once the plugin is known, and does nothing while the injected plugin is disabled, because a disabled plugin
   * cannot register listeners.
   */
  @Override
  public void start() {
    final Plugin plugin = PLUGIN;
    if (plugin == null || !plugin.isEnabled()) {
      return;
    }
    PacketUtils.init();
  }

  /**
   * Unregisters the listeners of the module. The injected plugin is kept, so the module can be started again.
   */
  @Override
  public void stop() {
    if (PLUGIN == null) {
      return;
    }
    PacketUtils.shutdown();
  }

  /**
   * Gets the name of the module, which is {@code bukkit}.
   *
   * @return the name of the module
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }
}

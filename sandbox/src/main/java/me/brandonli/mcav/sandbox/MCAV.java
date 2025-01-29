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
package me.brandonli.mcav.sandbox;

import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.installer.MCAVInstaller;
import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MCAV extends JavaPlugin {

  /*

  Things to implement into plugin
  - Example showing VNC player
  - Example showing video player (map, entities, chat, scoreboard)

  - Add resource pack audio support
  - Add Discord Audio Support
  - Add resource pack hosting + Netty injection

   */

  private AudienceProvider audienceProvider;

  private MCAVApi mcav;
  private PluginDataConfigurationMapper configurationMapper;

  @Override
  public void onLoad() {
    this.loadDependencies();
    this.loadMCAV();
  }

  @Override
  public void onEnable() {
    this.loadPluginData();
    this.loadAudience();
    this.initLookupTables();
    this.loadCommands();
  }

  private void unloadMCAV() {
    if (this.mcav != null) {
      this.mcav.release();
    }
  }

  private void loadDependencies() {
    final ClassLoader loader = this.getClassLoader();
    final Path folder = IOUtils.getPluginDataFolderPath();
    final MCAVInstaller installer = MCAVInstaller.injector(folder, loader);
    final Logger temporary = Logger.getLogger("MCAV Installer");
    try {
      installer.loadMCAVDependencies(line -> temporary.log(Level.INFO, line));
    } catch (final IOException e) {
      final Server server = Bukkit.getServer();
      final PluginManager pluginManager = server.getPluginManager();
      pluginManager.disablePlugin(this);
      throw new AssertionError(e);
    }
  }

  private void loadMCAV() {
    this.mcav = me.brandonli.mcav.MCAV.api();
    this.mcav.install();
  }

  private void shutdownAudience() {
    if (this.audienceProvider != null) {
      this.audienceProvider.shutdown();
    }
  }

  private void loadAudience() {
    this.audienceProvider = new AudienceProvider(this);
  }

  private void loadPluginData() {
    this.configurationMapper = new PluginDataConfigurationMapper(this);
    this.configurationMapper.deserialize();
  }

  @Override
  public void onDisable() {
    this.savePluginData();
    this.shutdownLookupTables();
    this.unloadMCAV();
    this.shutdownAudience();
  }

  private void loadCommands() {
    final AnnotationParserHandler annotationParserHandler = new AnnotationParserHandler(this);
    annotationParserHandler.registerCommands();
  }

  private void shutdownLookupTables() {
    // no-op
  }

  private void initLookupTables() {
    // no-op
  }

  private void savePluginData() {
    if (this.configurationMapper != null) {
      this.configurationMapper.serialize();
    }
  }

  public PluginDataConfigurationMapper getConfiguration() {
    return this.configurationMapper;
  }

  public AudienceProvider getAudience() {
    return this.audienceProvider;
  }
}

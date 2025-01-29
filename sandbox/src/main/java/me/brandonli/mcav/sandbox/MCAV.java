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

import dev.triumphteam.gui.TriumphGui;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
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
  private Logger logger;

  private MCAVApi mcav;
  private PluginDataConfigurationMapper configurationMapper;

  @Override
  public void onLoad() {
    this.logger = this.getLogger();
    this.loadDependencies();
    this.loadMCAV();
  }

  private void unloadMCAV() {
    if (this.mcav != null) {
      this.mcav.release();
    }
  }

  @Override
  public void onEnable() {
    this.loadAudience();
    this.loadPluginData();
    this.loadCommands();
    this.initLookupTables();
  }

  private void loadMCAV() {
    this.logger.info("Loading MCAV Library");
    final long startTime = System.currentTimeMillis();
    this.mcav = me.brandonli.mcav.MCAV.api();
    this.mcav.install();
    final long endTime = System.currentTimeMillis();
    this.logger.info("MCAV Library loaded in " + (endTime - startTime) + "ms");
  }

  private void loadDependencies() {
    try {
      this.logger.info("Loading MCAV Dependencies");
      final long startTime = System.currentTimeMillis();
      final ClassLoader loader = this.getClassLoader();
      final Path folder = IOUtils.getPluginDataFolderPath();
      final Logger loggerFactory = Logger.getLogger("MCAV Installer");
      final MCAVInstaller installer = MCAVInstaller.injector(folder, loader);
      installer.loadMCAVDependencies(line -> loggerFactory.log(Level.INFO, line));
      final long endTime = System.currentTimeMillis();
      this.logger.info("MCAV Dependencies loaded in " + (endTime - startTime) + "ms");
    } catch (final IOException e) {
      final Server server = Bukkit.getServer();
      final PluginManager pluginManager = server.getPluginManager();
      pluginManager.disablePlugin(this);
      throw new AssertionError(e);
    }
  }

  private void shutdownAudience() {
    if (this.audienceProvider != null) {
      this.audienceProvider.shutdown();
    }
  }

  private void loadAudience() {
    this.logger.info("Loading Audience Provider");
    final long startTime = System.currentTimeMillis();
    this.audienceProvider = new AudienceProvider(this);
    final long endTime = System.currentTimeMillis();
    this.logger.info("Audience Provider loaded in " + (endTime - startTime) + "ms");
  }

  private void loadPluginData() {
    this.logger.info("Loading Plugin Data");
    final long startTime = System.currentTimeMillis();
    this.configurationMapper = new PluginDataConfigurationMapper(this);
    this.configurationMapper.deserialize();
    final long endTime = System.currentTimeMillis();
    this.logger.info("Plugin Data loaded in " + (endTime - startTime) + "ms");
  }

  @Override
  public void onDisable() {
    this.savePluginData();
    this.shutdownLookupTables();
    this.unloadMCAV();
    this.shutdownAudience();
  }

  private void loadCommands() {
    this.logger.info("Loading Commands");
    final long startTime = System.currentTimeMillis();
    final AnnotationParserHandler annotationParserHandler = new AnnotationParserHandler(this);
    annotationParserHandler.registerCommands();
    final long endTime = System.currentTimeMillis();
    this.logger.info("Commands loaded in " + (endTime - startTime) + "ms");
  }

  private void shutdownLookupTables() {
    // no-op
  }

  private void initLookupTables() {
    this.logger.info("Initializing Lookup Tables");
    final long startTime = System.currentTimeMillis();
    TriumphGui.init(this);
    final long endTime = System.currentTimeMillis();
    this.logger.info("Lookup Tables initialized in " + (endTime - startTime) + "ms");
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

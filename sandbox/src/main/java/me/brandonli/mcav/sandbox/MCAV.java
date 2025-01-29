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
import org.bukkit.scheduler.BukkitScheduler;

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
  }

  private void scheduleLoadTask() {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTaskLater(this, this::loadMCAV, 1L);
  }

  @Override
  public void onEnable() {
    this.scheduleLoadTask();
  }

  private void unloadMCAV() {
    if (this.mcav != null) {
      this.mcav.release();
    }
  }

  // fix console hang
  @SuppressWarnings("all")
  private void loadDependencies() {
    try {
      final ClassLoader loader = this.getClassLoader();
      final Path folder = IOUtils.getPluginDataFolderPath();
      final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      final Logger loggerFactory = Logger.getLogger("MCAV Installer");
      final MCAVInstaller installer = MCAVInstaller.injector(folder, loader);
      installer.loadMCAVDependencies(line -> loggerFactory.log(Level.INFO, line));
    } catch (final IOException e) {
      final Server server = Bukkit.getServer();
      final PluginManager pluginManager = server.getPluginManager();
      pluginManager.disablePlugin(this);
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("all")
  private void loadMCAV() {
    System.setProperty("org.bytedeco.javacpp.logger.debug", "true");
    this.logger.info("Loading MCAV!");
    this.mcav = me.brandonli.mcav.MCAV.api();
    this.mcav.install();
    this.loadAudience();
    this.loadPluginData();
    this.loadCommands();
    this.initLookupTables();
    this.logger.info("MCAV loaded!");
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
    TriumphGui.init(this);
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

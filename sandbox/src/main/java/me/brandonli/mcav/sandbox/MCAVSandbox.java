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
import java.util.logging.Logger;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.bukkit.MCAVBukkit;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.command.video.VideoPlayerManager;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.listener.JukeBoxListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class MCAVSandbox extends JavaPlugin {

  private Logger logger;

  private MCAVApi mcav;
  private JukeBoxListener listener;
  private AudioProvider audioProvider;
  private VideoPlayerManager videoPlayerManager;
  private AnnotationParserHandler annotationParserHandler;
  private PluginDataConfigurationMapper configurationMapper;

  @Override
  public void onLoad() {
    this.logger = this.getLogger();
    this.loadMCAV();
  }

  private void unloadMCAV() {
    if (this.mcav != null) {
      this.mcav.release();
    }
  }

  @Override
  public void onEnable() {
    this.loadPluginData();
    this.loadManager();
    this.loadCommands();
    this.loadListeners();
    this.initLookupTables();
  }

  private void loadManager() {
    this.audioProvider = new AudioProvider(this);
    this.audioProvider.initialize();
    this.videoPlayerManager = new VideoPlayerManager(this);
  }

  private void loadMCAV() {
    this.logger.info("Loading MCAV Library");
    final long startTime = System.currentTimeMillis();
    this.mcav = MCAV.api();
    this.mcav.install();
    MCAVBukkit.inject(this);
    final long endTime = System.currentTimeMillis();
    this.logger.info("MCAV Library loaded in " + (endTime - startTime) + "ms");
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
    this.shutdownLookupTables();
    this.unloadListeners();
    this.shutdownCommands();
    this.saveData();
    this.unloadMCAV();
  }

  private void unloadListeners() {
    this.listener.shutdown();
  }

  private void loadListeners() {
    this.listener = new JukeBoxListener(this);
    this.listener.start();
  }

  private void shutdownCommands() {
    if (this.annotationParserHandler != null) {
      this.annotationParserHandler.shutdownCommands();
    }
  }

  private void saveData() {
    if (this.configurationMapper != null) {
      this.configurationMapper.shutdown();
    }
    if (this.audioProvider != null) {
      this.audioProvider.shutdown();
    }
  }

  private void loadCommands() {
    this.logger.info("Loading Commands");
    final long startTime = System.currentTimeMillis();
    this.annotationParserHandler = new AnnotationParserHandler(this);
    this.annotationParserHandler.registerCommands();
    final long endTime = System.currentTimeMillis();
    this.logger.info("Commands loaded in " + (endTime - startTime) + "ms");
  }

  private void shutdownLookupTables() {
    if (this.videoPlayerManager != null) {
      this.videoPlayerManager.shutdown();
    }
  }

  private void initLookupTables() {
    this.logger.info("Initializing Lookup Tables");
    final long startTime = System.currentTimeMillis();
    TriumphGui.init(this);
    final long endTime = System.currentTimeMillis();
    this.logger.info("Lookup Tables initialized in " + (endTime - startTime) + "ms");
  }

  public MCAVApi getMCAV() {
    return this.mcav;
  }

  public PluginDataConfigurationMapper getConfiguration() {
    return this.configurationMapper;
  }

  public VideoPlayerManager getVideoPlayerManager() {
    return this.videoPlayerManager;
  }

  public AudioProvider getAudioProvider() {
    return this.audioProvider;
  }
}

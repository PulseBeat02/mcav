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
package me.brandonli.mcav.sandbox;

import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.BrowserModule;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.command.image.ImageManager;
import me.brandonli.mcav.sandbox.command.video.VideoPlayerManager;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.listener.JukeBoxListener;
import me.brandonli.mcav.vm.VMModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.plugin.java.JavaPlugin;

public final class MCAVSandbox extends JavaPlugin {

  private ComponentLogger logger;

  private MCAVApi mcav;
  private boolean isQemuInstalled;

  private JukeBoxListener listener;
  private AudioProvider audioProvider;
  private ImageManager imageManager;
  private VideoPlayerManager videoPlayerManager;
  private AnnotationParserHandler annotationParserHandler;
  private PluginDataConfigurationMapper configurationMapper;

  private void unloadMCAV() {
    if (this.mcav != null) {
      this.mcav.release();
    }
  }

  @Override
  public void onEnable() {
    this.logger = this.getComponentLogger();
    this.loadMCAV();
    this.loadPluginData();
    this.loadManager();
    this.loadCommands();
    this.loadListeners();
  }

  private void loadManager() {
    this.audioProvider = new AudioProvider(this);
    this.audioProvider.initialize();
    this.videoPlayerManager = new VideoPlayerManager(this);
    this.imageManager = new ImageManager();
  }

  private void loadMCAV() {
    this.logger.info("Loading MCAV Library");
    final long startTime = System.currentTimeMillis();

    this.mcav = MCAV.api();
    this.mcav.install(BukkitModule.class, BrowserModule.class, VMModule.class);

    final BukkitModule module = this.mcav.getModule(BukkitModule.class);
    module.inject(this);

    final VMModule vmModule = this.mcav.getModule(VMModule.class);
    this.isQemuInstalled = vmModule.isQemuInstalled();
    if (!this.isQemuInstalled) {
      this.logger.warn("QEMU is not installed. VM playback will not be available.");
    }

    final long endTime = System.currentTimeMillis();
    this.logger.info("MCAV Library loaded in {}ms", endTime - startTime);
  }

  private void loadPluginData() {
    this.logger.info("Loading Plugin Data");
    final long startTime = System.currentTimeMillis();
    this.configurationMapper = new PluginDataConfigurationMapper(this);
    this.configurationMapper.deserialize();
    final long endTime = System.currentTimeMillis();
    this.logger.info("Plugin Data loaded in {}ms", endTime - startTime);
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
    if (this.listener != null) {
      this.listener.shutdown();
    }
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
    this.logger.info("Commands loaded in {}ms", endTime - startTime);
  }

  private void shutdownLookupTables() {
    if (this.videoPlayerManager != null) {
      this.videoPlayerManager.shutdown();
    }
    if (this.imageManager != null) {
      this.imageManager.shutdown();
    }
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

  public ImageManager getImageManager() {
    return this.imageManager;
  }

  public boolean isQemuInstalled() {
    return this.isQemuInstalled;
  }
}

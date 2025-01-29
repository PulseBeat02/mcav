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

import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.dependency.DependencyManager;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.sandbox.utils.ClassGraphUtils;
import org.bukkit.plugin.java.JavaPlugin;

public final class MCAV extends JavaPlugin {

  private AudienceProvider audienceProvider;

  private PluginDataConfigurationMapper configurationMapper;

  @Override
  public void onLoad() {
    final DependencyManager manager = new DependencyManager(this);
    manager.loadDependencies();
  }

  @Override
  public void onEnable() {
    this.loadAudience();
    this.loadPluginData();
    this.initLookupTables();
    this.loadCommands();
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
    this.shutdownAudience();
  }

  private void loadCommands() {
    final AnnotationParserHandler annotationParserHandler = new AnnotationParserHandler(this);
    annotationParserHandler.registerCommands();
  }

  private void shutdownLookupTables() {
    ClassGraphUtils.close();
  }

  private void initLookupTables() {
    ClassGraphUtils.init();
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

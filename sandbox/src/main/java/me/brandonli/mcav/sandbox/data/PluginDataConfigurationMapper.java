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
package me.brandonli.mcav.sandbox.data;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.configuration.file.FileConfiguration;

public final class PluginDataConfigurationMapper {

  private static final String PLUGIN_LANGUAGE = "language";

  private static final String DISCORD_BOT_TOKEN_FIELD = "discord-bot.token";
  private static final String DISCORD_BOT_CHANNEL_FIELD = "discord-bot.channel-id";
  private static final String DISCORD_BOT_GUILD_ID_FIELD = "discord-bot.guild-id";
  private static final String DISCORD_BOT_ENABLED = "discord-bot.enabled";

  private static final String HTTP_HOST_FIELD = "http-server.host-name";
  private static final String HTTP_PORT_FIELD = "http-server.port";
  private static final String HTTP_ENABLED = "http-server.enabled";

  private final ExecutorService service;
  private final MCAVSandbox plugin;
  private final Lock readLock;

  private Locale locale;

  private boolean discordBotEnabled;
  private String discordBotToken;
  private String discordBotChannelId;
  private String discordBotGuildId;

  private boolean httpEnabled;
  private String httpHostName;
  private int httpPort;

  public PluginDataConfigurationMapper(final MCAVSandbox plugin) {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    this.plugin = plugin;
    this.readLock = lock.readLock();
    this.service = Executors.newVirtualThreadPerTaskExecutor();
    this.plugin.saveDefaultConfig();
  }

  public synchronized void shutdown() {
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public synchronized MCAVSandbox getPlugin() {
    return this.plugin;
  }

  public synchronized void deserialize() {
    this.readLock.lock();
    final FileConfiguration config = this.plugin.getConfig();
    this.plugin.saveConfig();
    this.locale = this.getLocale(config);
    this.discordBotToken = this.getDiscordBotToken(config);
    this.discordBotChannelId = this.getDiscordBotChannelId(config);
    this.discordBotGuildId = this.getDiscordBotGuildId(config);
    this.httpHostName = this.getHttpHostName(config);
    this.httpPort = this.getHttpPort(config);
    this.discordBotEnabled = this.isDiscordBotEnabled(config);
    this.httpEnabled = this.isHttpEnabled(config);
    this.readLock.unlock();
  }

  private boolean isDiscordBotEnabled(final FileConfiguration config) {
    return config.getBoolean(DISCORD_BOT_ENABLED, false);
  }

  private boolean isHttpEnabled(final FileConfiguration config) {
    return config.getBoolean(HTTP_ENABLED, false);
  }

  private int getHttpPort(final FileConfiguration config) {
    return config.getInt(HTTP_PORT_FIELD);
  }

  private String getHttpHostName(final FileConfiguration config) {
    return requireNonNull(config.getString(HTTP_HOST_FIELD, "localhost"));
  }

  private String getDiscordBotGuildId(final FileConfiguration config) {
    return requireNonNull(config.getString(DISCORD_BOT_GUILD_ID_FIELD), "");
  }

  private String getDiscordBotChannelId(final FileConfiguration config) {
    return requireNonNull(config.getString(DISCORD_BOT_CHANNEL_FIELD), "");
  }

  private String getDiscordBotToken(final FileConfiguration config) {
    return requireNonNull(config.getString(DISCORD_BOT_TOKEN_FIELD), "");
  }

  private Locale getLocale(final FileConfiguration config) {
    return Locale.fromString(requireNonNull(config.getString(PLUGIN_LANGUAGE, "EN_US")));
  }

  public synchronized Locale getLocale() {
    return this.locale;
  }

  public synchronized String getDiscordBotToken() {
    return this.discordBotToken;
  }

  public synchronized String getDiscordBotChannelId() {
    return this.discordBotChannelId;
  }

  public synchronized String getDiscordBotGuildId() {
    return this.discordBotGuildId;
  }

  public synchronized String getHttpHostName() {
    return this.httpHostName;
  }

  public synchronized int getHttpPort() {
    return this.httpPort;
  }

  public synchronized boolean isDiscordBotEnabled() {
    return this.discordBotEnabled;
  }

  public synchronized boolean isHttpEnabled() {
    return this.httpEnabled;
  }
}

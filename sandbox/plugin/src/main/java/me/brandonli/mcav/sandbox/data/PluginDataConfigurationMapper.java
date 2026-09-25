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
package me.brandonli.mcav.sandbox.data;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code config.yml} into typed settings. Missing values fall back to defaults instead of failing, but a
 * file that cannot be read or is not valid YAML fails, so a broken file never silently turns into the defaults.
 */
public final class PluginDataConfigurationMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginDataConfigurationMapper.class);
  private static final String CONFIG_FILE = "config.yml";
  private static final String PLUGIN_LANGUAGE = "language";
  private static final String DISCORD_BOT_TOKEN_FIELD = "discord-bot.token";
  private static final String DISCORD_BOT_CHANNEL_FIELD = "discord-bot.channel-id";
  private static final String DISCORD_BOT_GUILD_ID_FIELD = "discord-bot.guild-id";
  private static final String DISCORD_BOT_ENABLED = "discord-bot.enabled";
  private static final String HTTP_HOST_FIELD = "http-server.host-name";
  private static final String HTTP_PORT_FIELD = "http-server.port";
  private static final String HTTP_ENABLED = "http-server.enabled";
  private static final String SIMPLE_VOICE_CHAT_ENABLED = "simple-voice-chat.enabled";
  private static final String BROWSER_PRIVATE_NETWORKS = "browser.allow-private-networks";
  private static final String BROWSER_JAVASCRIPT_JIT = "browser.javascript-jit";
  private static final String BROWSER_AUTOPLAY_SOUND = "browser.autoplay-sound";
  private static final int DEFAULT_HTTP_PORT = 3000;

  private final MCAVSandbox plugin;

  private Locale locale;
  private boolean discordBotEnabled;
  private String discordBotToken;
  private String discordBotChannelId;
  private String discordBotGuildId;
  private boolean httpEnabled;
  private String httpHostName;
  private int httpPort;
  private boolean simpleVoiceChatEnabled;
  private boolean browserPrivateNetworks;
  private boolean browserJavaScriptJit;
  private boolean browserAutoplaySound;

  /**
   * Constructs the mapper with default settings. Call {@link #deserialize()} to read the file.
   *
   * @param plugin the plugin
   */
  public PluginDataConfigurationMapper(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.locale = Locale.EN_US;
    this.discordBotToken = "";
    this.discordBotChannelId = "";
    this.discordBotGuildId = "";
    this.httpHostName = "localhost";
    this.httpPort = DEFAULT_HTTP_PORT;
  }

  /**
   * Reads the configuration file, creating it from the bundled default first if it does not exist.
   *
   * @throws IllegalStateException if the file cannot be read or is not valid YAML
   */
  public synchronized void deserialize() {
    final FileConfiguration config = this.loadConfiguration();
    final String language = getString(config, PLUGIN_LANGUAGE, "EN_US");
    this.locale = Locale.fromString(language);
    this.discordBotEnabled = config.getBoolean(DISCORD_BOT_ENABLED, false);
    this.discordBotToken = getString(config, DISCORD_BOT_TOKEN_FIELD, "");
    this.discordBotChannelId = getString(config, DISCORD_BOT_CHANNEL_FIELD, "");
    this.discordBotGuildId = getString(config, DISCORD_BOT_GUILD_ID_FIELD, "");
    this.httpEnabled = config.getBoolean(HTTP_ENABLED, false);
    this.httpHostName = getString(config, HTTP_HOST_FIELD, "localhost");
    this.httpPort = readPort(config);
    this.simpleVoiceChatEnabled = config.getBoolean(SIMPLE_VOICE_CHAT_ENABLED, false);
    this.browserPrivateNetworks = config.getBoolean(BROWSER_PRIVATE_NETWORKS, false);
    this.browserJavaScriptJit = config.getBoolean(BROWSER_JAVASCRIPT_JIT, false);
    this.browserAutoplaySound = config.getBoolean(BROWSER_AUTOPLAY_SOUND, false);
  }

  private FileConfiguration loadConfiguration() {
    final Path folder = IOUtils.getPluginDataFolderPath();
    final Path file = folder.resolve(CONFIG_FILE);
    final boolean exists = Files.exists(file);
    if (!exists) {
      this.plugin.saveResource(CONFIG_FILE, false);
    }
    try (final Reader reader = Files.newBufferedReader(file)) {
      final YamlConfiguration configuration = new YamlConfiguration();
      configuration.load(reader);
      return configuration;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new IllegalStateException("Failed to read " + file + ": " + message, exception);
    } catch (final InvalidConfigurationException exception) {
      final String message = exception.getMessage();
      throw new IllegalStateException(file + " is not valid YAML: " + message, exception);
    }
  }

  private static String getString(final FileConfiguration config, final String key, final String fallback) {
    // never null because the fallback is not null
    final String value = config.getString(key, fallback);
    return Objects.requireNonNullElse(value, fallback);
  }

  private static int readPort(final FileConfiguration config) {
    final int port = config.getInt(HTTP_PORT_FIELD, DEFAULT_HTTP_PORT);
    if (port < 1 || port > 65535) {
      LOGGER.warn("Invalid {} {}, using {}", HTTP_PORT_FIELD, port, DEFAULT_HTTP_PORT);
      return DEFAULT_HTTP_PORT;
    }
    return port;
  }

  /**
   * Checks whether pages of the browser may reach loopback, private and link-local addresses.
   *
   * @return true if {@code browser.allow-private-networks} is on
   */
  public synchronized boolean isBrowserPrivateNetworks() {
    return this.browserPrivateNetworks;
  }

  /**
   * Checks whether the browser compiles JavaScript to machine code.
   *
   * @return true if {@code browser.javascript-jit} is on
   */
  public synchronized boolean isBrowserJavaScriptJit() {
    return this.browserJavaScriptJit;
  }

  /**
   * Checks whether pages of the browser play sound before a player clicked or typed into them.
   *
   * @return true if {@code browser.autoplay-sound} is on
   */
  public synchronized boolean isBrowserAutoplaySound() {
    return this.browserAutoplaySound;
  }

  /**
   * Gets the plugin.
   *
   * @return the plugin
   */
  public MCAVSandbox getPlugin() {
    return this.plugin;
  }

  /**
   * Gets the language of the messages.
   *
   * @return the locale
   */
  public synchronized Locale getLocale() {
    return this.locale;
  }

  /**
   * Gets the Discord bot token.
   *
   * @return the token, empty if not set
   */
  public synchronized String getDiscordBotToken() {
    return this.discordBotToken;
  }

  /**
   * Gets the id of the Discord voice channel.
   *
   * @return the channel id, empty if not set
   */
  public synchronized String getDiscordBotChannelId() {
    return this.discordBotChannelId;
  }

  /**
   * Gets the id of the Discord server.
   *
   * @return the guild id, empty if not set
   */
  public synchronized String getDiscordBotGuildId() {
    return this.discordBotGuildId;
  }

  /**
   * Gets the host name listeners use to reach the audio web page.
   *
   * @return the host name
   */
  public synchronized String getHttpHostName() {
    return this.httpHostName;
  }

  /**
   * Gets the port of the audio web page.
   *
   * @return the port
   */
  public synchronized int getHttpPort() {
    return this.httpPort;
  }

  /**
   * Checks whether the Discord bot is enabled.
   *
   * @return true if enabled
   */
  public synchronized boolean isDiscordBotEnabled() {
    return this.discordBotEnabled;
  }

  /**
   * Checks whether the audio web page is enabled.
   *
   * @return true if enabled
   */
  public synchronized boolean isHttpEnabled() {
    return this.httpEnabled;
  }

  /**
   * Checks whether Simple Voice Chat audio is enabled.
   *
   * @return true if enabled
   */
  public synchronized boolean isSimpleVoiceChatEnabled() {
    return this.simpleVoiceChatEnabled;
  }
}

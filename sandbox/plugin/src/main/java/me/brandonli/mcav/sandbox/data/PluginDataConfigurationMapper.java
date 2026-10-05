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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.bukkit.media.mcv2.encode.Mcv2Natives;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.interaction.VncAllowList;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import me.brandonli.mcav.sandbox.utils.MapCodec;
import me.brandonli.mcav.sandbox.utils.Mcv2Hosting;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.checkerframework.checker.nullness.qual.Nullable;
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

  private static final String MCV2_ENCODER_THREADS = "mcv2.encoder-threads";

  private static final String MCV2_NATIVE = "mcv2.native";

  private static final String MCV2_DEFAULT_CODEC = "mcv2.default-codec";

  private static final String MCV2_PACK_HOSTING = "mcv2.pack.hosting";

  private static final String MCV2_PACK_HTTP_HOST = "mcv2.pack.http-host";

  private static final String MCV2_PACK_HTTP_PORT = "mcv2.pack.http-port";

  private static final int DEFAULT_PACK_HTTP_PORT = 25580;

  private static final String BROWSER_PRIVATE_NETWORKS = "browser.allow-private-networks";

  private static final String BROWSER_JAVASCRIPT_JIT = "browser.javascript-jit";

  private static final String BROWSER_AUTOPLAY_SOUND = "browser.autoplay-sound";

  private static final String BROWSER_CONFINE_CHROMIUM = "browser.confine-chromium";

  private static final String VM_ALLOW_NETWORK = "vm.allow-network";

  private static final String VNC_ALLOWED_HOSTS = "vnc.allowed-hosts";

  private static final int DEFAULT_HTTP_PORT = 3000;

  private static final int MAX_PORT = 65535;

  private static final String INVALID_PORT = "Invalid {} {}, using {}";

  private static final String INVALID_THREADS = "Invalid {} {}, using half the processors";

  private static final String INVALID_NATIVE = "Invalid {} {}, using " + Mcv2Natives.AUTO;

  private static final String INVALID_CHOICE = "Invalid {} {}, using {}";

  // names the host and the port of the entry, never its password
  private static final String INVALID_VNC_HOST = "Ignoring an entry of {} that is not a host and a port from 1 to 65535: {}:{}";

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

  private boolean browserConfineChromium = true;

  private boolean vmAllowNetwork;

  private int mcv2EncoderThreads;

  private String mcv2Native = Mcv2Natives.AUTO;

  private MapCodec mcv2DefaultCodec = MapCodec.DITHER;

  private Mcv2Hosting mcv2PackHosting = Mcv2Hosting.INJECTOR;

  private String mcv2PackHttpHost = "";

  private int mcv2PackHttpPort = DEFAULT_PACK_HTTP_PORT;

  private VncAllowList vncAllowList = VncAllowList.NONE;

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
    this.httpPort = readPort(config, HTTP_PORT_FIELD, DEFAULT_HTTP_PORT);
    this.simpleVoiceChatEnabled = config.getBoolean(SIMPLE_VOICE_CHAT_ENABLED, false);
    this.mcv2EncoderThreads = readEncoderThreads(config);
    this.mcv2Native = readNative(config);
    this.mcv2DefaultCodec = readChoice(config, MCV2_DEFAULT_CODEC, MapCodec.class, MapCodec.DITHER);
    this.mcv2PackHosting = readChoice(config, MCV2_PACK_HOSTING, Mcv2Hosting.class, Mcv2Hosting.INJECTOR);
    this.mcv2PackHttpHost = getString(config, MCV2_PACK_HTTP_HOST, "").strip();
    this.mcv2PackHttpPort = readPort(config, MCV2_PACK_HTTP_PORT, DEFAULT_PACK_HTTP_PORT);
    this.browserPrivateNetworks = config.getBoolean(BROWSER_PRIVATE_NETWORKS, false);
    this.browserJavaScriptJit = config.getBoolean(BROWSER_JAVASCRIPT_JIT, false);
    this.browserAutoplaySound = config.getBoolean(BROWSER_AUTOPLAY_SOUND, false);
    this.browserConfineChromium = config.getBoolean(BROWSER_CONFINE_CHROMIUM, true);
    this.vmAllowNetwork = config.getBoolean(VM_ALLOW_NETWORK, false);
    this.vncAllowList = readVncAllowList(config);
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

  private static int readPort(final FileConfiguration config, final String key, final int fallback) {
    // only a YAML int is taken: getInt cuts a longer number down to its low 32 bits (4294967376 reads as port 80) and
    // a fraction down to a whole number
    final Object value = config.get(key, fallback);
    if (!(value instanceof final Integer port) || port < 1 || port > MAX_PORT) {
      LOGGER.warn(INVALID_PORT, key, value, fallback);
      return fallback;
    }
    return port;
  }

  /** Reads a setting that names a constant of an enum, in any case. */
  private static <E extends Enum<E>> E readChoice(final FileConfiguration config, final String key, final Class<E> type, final E fallback) {
    final String value = getString(config, key, fallback.name());
    for (final E constant : EnumSet.allOf(type)) {
      if (constant.name().equalsIgnoreCase(value.strip())) {
        return constant;
      }
    }
    LOGGER.warn(INVALID_CHOICE, key, value, fallback);
    return fallback;
  }

  private static int readEncoderThreads(final FileConfiguration config) {
    // only a YAML int is taken, as for a port
    final Object value = config.get(MCV2_ENCODER_THREADS, 0);
    if (!(value instanceof final Integer threads) || threads < 0 || threads > EncoderPool.MAX_THREADS) {
      LOGGER.warn(INVALID_THREADS, MCV2_ENCODER_THREADS, value);
      return 0;
    }
    return threads;
  }

  private static String readNative(final FileConfiguration config) {
    // YAML reads an unquoted off as false, and on as true
    if (config.isBoolean(MCV2_NATIVE)) {
      return config.getBoolean(MCV2_NATIVE) ? Mcv2Natives.AUTO : Mcv2Natives.OFF;
    }
    final String mode = getString(config, MCV2_NATIVE, Mcv2Natives.AUTO);
    if (!mode.equals(Mcv2Natives.AUTO) && !mode.equals(Mcv2Natives.OFF)) {
      LOGGER.warn(INVALID_NATIVE, MCV2_NATIVE, mode);
      return Mcv2Natives.AUTO;
    }
    return mode;
  }

  private static VncAllowList readVncAllowList(final FileConfiguration config) {
    final List<VncAllowList.Entry> entries = new ArrayList<>();
    for (final Map<?, ?> map : config.getMapList(VNC_ALLOWED_HOSTS)) {
      final VncAllowList.Entry entry = readVncHost(map);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return new VncAllowList(entries);
  }

  private static VncAllowList.@Nullable Entry readVncHost(final Map<?, ?> map) {
    final Object hostValue = map.get("host");
    final Object portValue = map.get("port");
    if (
      !(hostValue instanceof final String host) ||
      host.isBlank() ||
      !(portValue instanceof final Integer port) ||
      port < 1 ||
      port > MAX_PORT
    ) {
      LOGGER.warn(INVALID_VNC_HOST, VNC_ALLOWED_HOSTS, hostValue, portValue);
      return null;
    }
    final Object password = map.get("password");
    final String secret = password == null ? "" : password.toString();
    return new VncAllowList.Entry(host, port, secret.isEmpty() ? null : secret);
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
   * Checks whether the browser confines Chromium where the system can.
   *
   * @return true if {@code browser.confine-chromium} is on
   */
  public synchronized boolean isBrowserConfineChromium() {
    return this.browserConfineChromium;
  }

  /**
   * Checks whether virtual machines get QEMU's own user-mode network, which reaches the internet and every service of
   * the server's loopback address, instead of one that reaches nothing.
   *
   * @return true if {@code vm.allow-network} is on
   */
  public synchronized boolean isVmAllowNetwork() {
    return this.vmAllowNetwork;
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

  /**
   * Gets how many threads the MCV2 encoders of the server share.
   *
   * @return the thread count, or 0 for half the processors
   */
  public synchronized int getMcv2EncoderThreads() {
    return this.mcv2EncoderThreads;
  }

  /**
   * Gets the VNC servers {@code /mcav vnc create} may connect to.
   *
   * @return the entries of {@code vnc.allowed-hosts}, which may be none
   */
  public synchronized VncAllowList getVncAllowList() {
    return this.vncAllowList;
  }

  /**
   * Gets the codec of a map screen whose command does not say.
   *
   * @return {@code mcv2.default-codec}, dither unless set
   */
  public synchronized MapCodec getMcv2DefaultCodec() {
    return this.mcv2DefaultCodec;
  }

  /**
   * Gets where the players download the MCV2 resource pack from.
   *
   * @return {@code mcv2.pack.hosting}, the injector unless set
   */
  public synchronized Mcv2Hosting getMcv2PackHosting() {
    return this.mcv2PackHosting;
  }

  /**
   * Gets the host name or address players reach the pack's HTTP server by.
   *
   * @return {@code mcv2.pack.http-host}, empty for the address the server finds for itself
   */
  public synchronized String getMcv2PackHttpHost() {
    return this.mcv2PackHttpHost;
  }

  /**
   * Gets the port of the pack's HTTP server.
   *
   * @return {@code mcv2.pack.http-port}, from 1 to 65535
   */
  public synchronized int getMcv2PackHttpPort() {
    return this.mcv2PackHttpPort;
  }

  /**
   * Gets whether the live encoders use the native kernels where they load.
   *
   * @return {@link Mcv2Natives#AUTO} or {@link Mcv2Natives#OFF}
   */
  public synchronized String getMcv2Native() {
    return this.mcv2Native;
  }
}

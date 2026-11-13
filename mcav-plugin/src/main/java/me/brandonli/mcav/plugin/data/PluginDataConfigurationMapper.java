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
package me.brandonli.mcav.plugin.data;

import static java.util.Objects.requireNonNull;

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
import java.util.Optional;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.command.interaction.VncAllowList;
import me.brandonli.mcav.plugin.locale.Locale;
import me.brandonli.mcav.plugin.utils.IoUtils;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.plugin.utils.Mcv2Hosting;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code config.yml} into typed settings. Missing values fall back to defaults instead of failing, but a
 * file that cannot be read or is not valid YAML fails, so a broken file never silently turns into the defaults.
 *
 * <p>This class is thread-safe: {@link #deserialize()} and the getters of the settings are synchronized.
 *
 * @since 1.0.0
 */
public final class PluginDataConfigurationMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginDataConfigurationMapper.class);

  private static final String CONFIGURATION_FILE = "config.yml";

  private static final String PLUGIN_LANGUAGE = "language";

  private static final String DISCORD_BOT_TOKEN = "discord-bot.token";
  private static final String DISCORD_BOT_CHANNEL_ID = "discord-bot.channel-id";
  private static final String DISCORD_BOT_GUILD_ID = "discord-bot.guild-id";
  private static final String DISCORD_BOT_ENABLED = "discord-bot.enabled";

  private static final String HTTP_HOST_NAME = "http-server.host-name";
  private static final String HTTP_PORT = "http-server.port";
  private static final String HTTP_ENABLED = "http-server.enabled";

  private static final String SIMPLE_VOICE_CHAT_ENABLED = "simple-voice-chat.enabled";

  private static final String MCV2_ENCODER_THREADS = "mcv2.encoder-threads";
  private static final String MCV2_NATIVE = "mcv2.native";
  private static final String MCV2_DEFAULT_CODEC = "mcv2.default-codec";
  private static final String MCV2_PACK_HOSTING = "mcv2.pack.hosting";
  private static final String MCV2_PACK_HTTP_HOST = "mcv2.pack.http-host";
  private static final String MCV2_PACK_HTTP_PORT = "mcv2.pack.http-port";

  private static final String BROWSER_PRIVATE_NETWORKS = "browser.allow-private-networks";
  private static final String BROWSER_JAVASCRIPT_JIT = "browser.javascript-jit";
  private static final String BROWSER_AUTOPLAY_SOUND = "browser.autoplay-sound";
  private static final String BROWSER_CONFINE_CHROMIUM = "browser.confine-chromium";
  private static final String BROWSER_REFUSED_HOSTS = "browser.refused-hosts";

  private static final String VM_ALLOW_NETWORK = "vm.allow-network";

  private static final String VNC_ALLOWED_HOSTS = "vnc.allowed-hosts";

  private static final String DEFAULT_HOST = "localhost";
  private static final String DEFAULT_LOCALE = "EN_US";
  private static final int DEFAULT_HTTP_PORT = 3000;
  private static final int DEFAULT_PACK_HTTP_PORT = 25580;
  private static final int DEFAULT_ENCODER_THREADS = 0;

  private static final int MAXIMUM_PORT = 65535;

  private static final String INVALID_SETTING = "Invalid {} {}, using {} instead.";
  // names the host and the port of the entry, never its password
  private static final String INVALID_VNC_HOST = "Ignoring the entry {}:{} of {}, which is not a host and a port from 1 to 65535.";

  private static final char HOST_SEPARATOR = ',';

  private static final boolean DO_NOT_REPLACE = false;

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
  private boolean browserConfineChromium;
  private List<String> browserRefusedHosts;

  private boolean vmAllowNetwork;

  private int mcv2EncoderThreads;
  private String mcv2Native;
  private MapCodec mcv2DefaultCodec;
  private Mcv2Hosting mcv2PackHosting;
  private String mcv2PackHttpHost;
  private int mcv2PackHttpPort;

  private VncAllowList vncAllowList;

  /**
   * Creates the mapper with default settings. Call {@link #deserialize()} to read the file.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public PluginDataConfigurationMapper(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    this.locale = Locale.EN_US;
    this.discordBotToken = "";
    this.discordBotChannelId = "";
    this.discordBotGuildId = "";
    this.httpHostName = DEFAULT_HOST;
    this.httpPort = DEFAULT_HTTP_PORT;
    this.browserConfineChromium = true;
    this.browserRefusedHosts = List.of();
    this.mcv2Native = Mcv2.NATIVE_AUTO;
    this.mcv2DefaultCodec = MapCodec.DITHER;
    this.mcv2PackHosting = Mcv2Hosting.INJECTOR;
    this.mcv2PackHttpHost = "";
    this.mcv2PackHttpPort = DEFAULT_PACK_HTTP_PORT;
    this.vncAllowList = VncAllowList.NONE;
  }

  /**
   * Reads the configuration file, creating it from the bundled default first if it does not exist.
   *
   * @throws PluginException if the file cannot be read or is not valid YAML
   * @since 1.0.0
   */
  public synchronized void deserialize() {
    final FileConfiguration configuration = this.loadConfiguration();
    final String language = this.getString(configuration, PLUGIN_LANGUAGE, DEFAULT_LOCALE);
    final Optional<Locale> foundLocale = Locale.fromString(language);
    this.locale = requireNonNull(foundLocale.orElse(Locale.EN_US)); // checker framework
    this.discordBotEnabled = configuration.getBoolean(DISCORD_BOT_ENABLED, false);
    this.discordBotToken = this.getString(configuration, DISCORD_BOT_TOKEN, "");
    this.discordBotChannelId = this.getString(configuration, DISCORD_BOT_CHANNEL_ID, "");
    this.discordBotGuildId = this.getString(configuration, DISCORD_BOT_GUILD_ID, "");
    this.httpEnabled = configuration.getBoolean(HTTP_ENABLED, false);
    this.httpHostName = this.getString(configuration, HTTP_HOST_NAME, DEFAULT_HOST);
    this.httpPort = this.readPort(configuration, HTTP_PORT, DEFAULT_HTTP_PORT);
    this.simpleVoiceChatEnabled = configuration.getBoolean(SIMPLE_VOICE_CHAT_ENABLED, false);
    this.mcv2EncoderThreads = this.readEncoderThreads(configuration);
    this.mcv2Native = this.readNative(configuration);
    this.mcv2DefaultCodec = this.readChoice(configuration, MCV2_DEFAULT_CODEC, MapCodec.class, MapCodec.DITHER);
    this.mcv2PackHosting = this.readChoice(configuration, MCV2_PACK_HOSTING, Mcv2Hosting.class, Mcv2Hosting.INJECTOR);
    this.mcv2PackHttpHost = this.getString(configuration, MCV2_PACK_HTTP_HOST, "").strip();
    this.mcv2PackHttpPort = this.readPort(configuration, MCV2_PACK_HTTP_PORT, DEFAULT_PACK_HTTP_PORT);
    this.browserPrivateNetworks = configuration.getBoolean(BROWSER_PRIVATE_NETWORKS, false);
    this.browserJavaScriptJit = configuration.getBoolean(BROWSER_JAVASCRIPT_JIT, false);
    this.browserAutoplaySound = configuration.getBoolean(BROWSER_AUTOPLAY_SOUND, false);
    this.browserConfineChromium = configuration.getBoolean(BROWSER_CONFINE_CHROMIUM, true);
    this.browserRefusedHosts = this.readRefusedHosts(configuration);
    this.vmAllowNetwork = configuration.getBoolean(VM_ALLOW_NETWORK, false);
    this.vncAllowList = this.readVncAllowList(configuration);
  }

  private FileConfiguration loadConfiguration() {
    final Path folder = IoUtils.getPluginDataFolderPath();
    final Path file = folder.resolve(CONFIGURATION_FILE);
    final boolean exists = Files.exists(file);
    if (!exists) {
      this.plugin.saveResource(CONFIGURATION_FILE, DO_NOT_REPLACE);
    }

    try (final Reader reader = Files.newBufferedReader(file)) {
      final YamlConfiguration configuration = new YamlConfiguration();
      configuration.load(reader);
      return configuration;
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      final String message = "Because of \"%s\", file %s could not be read!".formatted(reason, file);
      throw new PluginException(message, exception);
    } catch (final InvalidConfigurationException | YAMLException | IllegalArgumentException exception) {
      final String reason = exception.getMessage();
      final String message = "Because of \"%s\", file %s is not valid YAML!".formatted(reason, file);
      throw new PluginException(message, exception);
    }
  }

  private String getString(final FileConfiguration configuration, final String key, final String fallback) {
    // never null because the fallback is not null
    final String value = configuration.getString(key, fallback);
    return Objects.requireNonNullElse(value, fallback);
  }

  private int readPort(final FileConfiguration configuration, final String key, final int fallback) {
    // only a YAML int is taken: getInt cuts a longer number down to its low 32 bits and a fraction down to a whole number
    final Object value = configuration.get(key, fallback);
    if (!(value instanceof final Integer port) || port < 1 || port > MAXIMUM_PORT) {
      LOGGER.warn(INVALID_SETTING, key, value, fallback);
      return fallback;
    }

    return port;
  }

  private <ChoiceType extends Enum<ChoiceType>> ChoiceType readChoice(
    final FileConfiguration configuration,
    final String key,
    final Class<ChoiceType> type,
    final ChoiceType fallback
  ) {
    final String value = this.getString(configuration, key, fallback.name());
    for (final ChoiceType constant : EnumSet.allOf(type)) {
      if (constant.name().equalsIgnoreCase(value.strip())) {
        return constant;
      }
    }

    LOGGER.warn(INVALID_SETTING, key, value, fallback);

    return fallback;
  }

  private List<String> readRefusedHosts(final FileConfiguration configuration) {
    final List<String> hosts = new ArrayList<>();
    for (final String entry : configuration.getStringList(BROWSER_REFUSED_HOSTS)) {
      final String host = entry.strip();
      if (host.isEmpty() || host.chars().anyMatch(character -> character == HOST_SEPARATOR || Character.isWhitespace(character))) {
        LOGGER.warn("Ignoring the entry {} of {}, which is not a host name or address.", entry, BROWSER_REFUSED_HOSTS);
      } else {
        hosts.add(host);
      }
    }

    return List.copyOf(hosts);
  }

  private int readEncoderThreads(final FileConfiguration configuration) {
    // only a YAML int is taken, as for a port
    final Object value = configuration.get(MCV2_ENCODER_THREADS, DEFAULT_ENCODER_THREADS);
    if (!(value instanceof final Integer threads) || threads < 0 || threads > Pool.MAXIMUM_THREADS) {
      LOGGER.warn("Invalid {} {}, using half the processors.", MCV2_ENCODER_THREADS, value);
      return DEFAULT_ENCODER_THREADS;
    }

    return threads;
  }

  private String readNative(final FileConfiguration configuration) {
    // YAML reads an unquoted off as false, and on as true
    if (configuration.isBoolean(MCV2_NATIVE)) {
      return configuration.getBoolean(MCV2_NATIVE) ? Mcv2.NATIVE_AUTO : Mcv2.NATIVE_OFF;
    }

    final String mode = this.getString(configuration, MCV2_NATIVE, Mcv2.NATIVE_AUTO);
    if (!mode.equals(Mcv2.NATIVE_AUTO) && !mode.equals(Mcv2.NATIVE_OFF)) {
      LOGGER.warn(INVALID_SETTING, MCV2_NATIVE, mode, Mcv2.NATIVE_AUTO);
      return Mcv2.NATIVE_AUTO;
    }

    return mode;
  }

  private VncAllowList readVncAllowList(final FileConfiguration configuration) {
    final List<VncAllowList.Entry> entries = new ArrayList<>();
    for (final Map<?, ?> map : configuration.getMapList(VNC_ALLOWED_HOSTS)) {
      final VncAllowList.Entry entry = this.readVncHost(map);
      if (entry != null) {
        entries.add(entry);
      }
    }

    return VncAllowList.of(entries);
  }

  private VncAllowList.@Nullable Entry readVncHost(final Map<?, ?> map) {
    final Object hostValue = map.get("host");
    final Object portValue = map.get("port");
    if (
      !(hostValue instanceof final String host) ||
      host.isBlank() ||
      !(portValue instanceof final Integer port) ||
      port < 1 ||
      port > MAXIMUM_PORT
    ) {
      LOGGER.warn(INVALID_VNC_HOST, hostValue, portValue, VNC_ALLOWED_HOSTS);
      return null;
    }

    final Object password = map.get("password");
    final String secret = password == null ? "" : password.toString();
    try {
      return VncAllowList.Entry.of(host, port, secret.isEmpty() ? null : secret);
    } catch (final IllegalArgumentException exception) {
      LOGGER.warn(INVALID_VNC_HOST, hostValue, portValue, VNC_ALLOWED_HOSTS);
      return null;
    }
  }

  /**
   * Returns whether pages of the browser may reach loopback, private and link-local addresses.
   *
   * @return true if {@code browser.allow-private-networks} is on
   * @since 1.0.0
   */
  public synchronized boolean isBrowserPrivateNetworks() {
    return this.browserPrivateNetworks;
  }

  /**
   * Returns whether the browser compiles JavaScript to machine code.
   *
   * @return true if {@code browser.javascript-jit} is on
   * @since 1.0.0
   */
  public synchronized boolean isBrowserJavaScriptJit() {
    return this.browserJavaScriptJit;
  }

  /**
   * Returns whether pages of the browser play sound before a player clicked or typed into them.
   *
   * @return true if {@code browser.autoplay-sound} is on
   * @since 1.0.0
   */
  public synchronized boolean isBrowserAutoplaySound() {
    return this.browserAutoplaySound;
  }

  /**
   * Returns whether the browser confines Chromium where the system can.
   *
   * @return true if {@code browser.confine-chromium} is on
   * @since 1.0.0
   */
  public synchronized boolean isBrowserConfineChromium() {
    return this.browserConfineChromium;
  }

  /**
   * Returns the hosts whose addresses pages of the browser may not reach, besides the private ones and the server's own.
   *
   * @return the names and addresses of {@code browser.refused-hosts}, unmodifiable
   * @since 1.0.0
   */
  public synchronized List<String> getBrowserRefusedHosts() {
    return this.browserRefusedHosts;
  }

  /**
   * Returns whether virtual machines get QEMU's own user-mode network, which reaches the internet and every service of
   * the server's loopback address, instead of one that reaches nothing.
   *
   * @return true if {@code vm.allow-network} is on
   * @since 1.0.0
   */
  public synchronized boolean isVmAllowNetwork() {
    return this.vmAllowNetwork;
  }

  /**
   * Returns the plugin.
   *
   * @return the plugin
   * @since 1.0.0
   */
  public MCAVSandbox getPlugin() {
    return this.plugin;
  }

  /**
   * Returns the language of the messages.
   *
   * @return the locale
   * @since 1.0.0
   */
  public synchronized Locale getLocale() {
    return this.locale;
  }

  /**
   * Returns the Discord bot token.
   *
   * @return the token, empty if not set
   * @since 1.0.0
   */
  public synchronized String getDiscordBotToken() {
    return this.discordBotToken;
  }

  /**
   * Returns the id of the Discord voice channel.
   *
   * @return the channel id, empty if not set
   * @since 1.0.0
   */
  public synchronized String getDiscordBotChannelId() {
    return this.discordBotChannelId;
  }

  /**
   * Returns the id of the Discord server.
   *
   * @return the guild id, empty if not set
   * @since 1.0.0
   */
  public synchronized String getDiscordBotGuildId() {
    return this.discordBotGuildId;
  }

  /**
   * Returns the host name listeners use to reach the audio web page.
   *
   * @return the host name
   * @since 1.0.0
   */
  public synchronized String getHttpHostName() {
    return this.httpHostName;
  }

  /**
   * Returns the port of the audio web page.
   *
   * @return the port
   * @since 1.0.0
   */
  public synchronized int getHttpPort() {
    return this.httpPort;
  }

  /**
   * Returns whether the Discord bot is enabled.
   *
   * @return true if enabled
   * @since 1.0.0
   */
  public synchronized boolean isDiscordBotEnabled() {
    return this.discordBotEnabled;
  }

  /**
   * Returns whether the audio web page is enabled.
   *
   * @return true if enabled
   * @since 1.0.0
   */
  public synchronized boolean isHttpEnabled() {
    return this.httpEnabled;
  }

  /**
   * Returns whether Simple Voice Chat audio is enabled.
   *
   * @return true if enabled
   * @since 1.0.0
   */
  public synchronized boolean isSimpleVoiceChatEnabled() {
    return this.simpleVoiceChatEnabled;
  }

  /**
   * Returns how many threads the MCV2 encoders of the server share.
   *
   * @return the thread count, or 0 for half the processors
   * @since 1.0.0
   */
  public synchronized int getMcv2EncoderThreads() {
    return this.mcv2EncoderThreads;
  }

  /**
   * Returns the VNC servers {@code /mcav vnc create} may connect to.
   *
   * @return the entries of {@code vnc.allowed-hosts}, which may be none
   * @since 1.0.0
   */
  public synchronized VncAllowList getVncAllowList() {
    return this.vncAllowList;
  }

  /**
   * Returns the codec of a map screen whose command does not say.
   *
   * @return {@code mcv2.default-codec}, dither unless set
   * @since 1.0.0
   */
  public synchronized MapCodec getMcv2DefaultCodec() {
    return this.mcv2DefaultCodec;
  }

  /**
   * Returns where the players download the MCV2 resource pack from.
   *
   * @return {@code mcv2.pack.hosting}, the injector unless set
   * @since 1.0.0
   */
  public synchronized Mcv2Hosting getMcv2PackHosting() {
    return this.mcv2PackHosting;
  }

  /**
   * Returns the host name or address players reach the pack's HTTP server by.
   *
   * @return {@code mcv2.pack.http-host}, empty for the address the server finds for itself
   * @since 1.0.0
   */
  public synchronized String getMcv2PackHttpHost() {
    return this.mcv2PackHttpHost;
  }

  /**
   * Returns the port of the pack's HTTP server.
   *
   * @return {@code mcv2.pack.http-port}, from 1 to 65535
   * @since 1.0.0
   */
  public synchronized int getMcv2PackHttpPort() {
    return this.mcv2PackHttpPort;
  }

  /**
   * Returns the mode that decides whether the live encoders use the native kernels where they load.
   *
   * @return {@code auto} or {@code off}
   * @since 1.0.0
   */
  public synchronized String getMcv2Native() {
    return this.mcv2Native;
  }
}

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
package me.brandonli.mcav.sandbox.audio;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.http.MediaInfo;
import me.brandonli.mcav.jda.DiscordPlayer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.svc.SVCFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the audio outputs enabled in the configuration: a Discord bot, the audio web page, and Simple Voice
 * Chat speakers.
 *
 * <p>Logging the bot in and starting the web server take a few seconds, so they start on another thread and the
 * server does not wait for them. An output can be used once it is ready, see {@link #isDiscordBotReady()} and
 * {@link #isHttpReady()}; one that fails to start is logged and stays unavailable.
 */
public final class AudioProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(AudioProvider.class);

  // the source a video plays as, as there is one video at a time
  private static final Object VIDEO = new Object();
  // sources are told apart by identity
  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private final PluginDataConfigurationMapper configuration;
  private final MCAVSandbox sandbox;
  private final ExecutorService startup;
  private final Object lock;

  private volatile @Nullable JDA jda;
  private volatile @Nullable DiscordConnection discord;
  private volatile @Nullable HttpResult httpServer;
  private volatile @Nullable SVCFilter voiceChatFilter;
  private boolean stopped;
  // the outputs play one source at a time, a video or a virtual machine; the newest takes them over
  private final Object outputLock;
  private volatile @Nullable Object owner;

  /**
   * Constructs the provider.
   *
   * @param sandbox the plugin, whose configuration must be loaded
   */
  public AudioProvider(final MCAVSandbox sandbox) {
    this(sandbox, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Constructs the provider with the executor that starts the Discord bot and the web server.
   *
   * @param sandbox the plugin, whose configuration must be loaded
   * @param startup the executor, which {@link #shutdown()} shuts down
   */
  @VisibleForTesting
  AudioProvider(final MCAVSandbox sandbox, final ExecutorService startup) {
    Preconditions.checkNotNull(sandbox, "Plugin must not be null");
    Preconditions.checkNotNull(startup, "Startup executor must not be null");
    this.configuration = sandbox.getConfiguration();
    this.sandbox = sandbox;
    this.startup = startup;
    this.lock = new Object();
    this.outputLock = new Object();
  }

  /**
   * Starts the outputs enabled in the configuration. The Discord bot and the web server start on another thread,
   * so this returns at once; Simple Voice Chat is registered before it returns.
   *
   * @throws MissingVoiceChatException if Simple Voice Chat audio is enabled but the voicechat plugin is not installed
   */
  public void initialize() {
    final boolean discordEnabled = this.configuration.isDiscordBotEnabled();
    if (discordEnabled) {
      this.startDiscord();
    }
    final boolean httpEnabled = this.configuration.isHttpEnabled();
    if (httpEnabled) {
      this.startHttp();
    }
    final boolean voiceChatEnabled = this.configuration.isSimpleVoiceChatEnabled();
    if (voiceChatEnabled) {
      this.registerVoiceChat();
    }
  }

  // Simple Voice Chat offers its service only once the server has loaded, so the plugin registers with it then
  private void registerVoiceChat() {
    final Server server = Bukkit.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    final boolean installed = pluginManager.isPluginEnabled("voicechat");
    if (!installed) {
      throw new MissingVoiceChatException("Simple Voice Chat audio is enabled, but the voicechat plugin is not installed");
    }
    final ServerLoadListener listener = new ServerLoadListener(this.sandbox);
    pluginManager.registerEvents(listener, this.sandbox);
  }

  private void startHttp() {
    final String host = this.configuration.getHttpHostName();
    final int port = this.configuration.getHttpPort();
    final HttpResult server = HttpResult.http(host, port);
    final CompletableFuture<Void> start = CompletableFuture.runAsync(server::start, this.startup);
    TaskUtils.whenComplete(start, (_, error) -> this.onHttpStarted(server, error));
  }

  private void onHttpStarted(final HttpResult server, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error("The audio web page could not be started, so its audio is not available", error);
      return;
    }
    final boolean accepted;
    synchronized (this.lock) {
      accepted = !this.stopped;
      if (accepted) {
        this.httpServer = server;
      }
    }
    // the plugin was disabled while the server started
    if (!accepted) {
      server.stop();
      return;
    }
    final String address = server.getFullUrl();
    LOGGER.info("The audio web page is available at {}", address);
  }

  private void startDiscord() {
    final String token = this.configuration.getDiscordBotToken();
    final String guildId = this.configuration.getDiscordBotGuildId();
    final String channelId = this.configuration.getDiscordBotChannelId();
    final JDA bot = createJDA(token);
    synchronized (this.lock) {
      this.jda = bot;
    }
    final CompletableFuture<DiscordConnection> login = CompletableFuture.supplyAsync(
      () -> connectDiscord(bot, guildId, channelId),
      this.startup
    );
    TaskUtils.whenComplete(login, (connection, error) -> this.onDiscordConnected(bot, connection, error));
  }

  private static JDA createJDA(final String token) {
    // a light bot only needs voice states to join a channel; building it starts the login in the background
    final JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES);
    builder.enableCache(CacheFlag.VOICE_STATE);
    return builder.build();
  }

  // runs on the startup thread
  private static DiscordConnection connectDiscord(final JDA bot, final String guildId, final String channelId) {
    try {
      bot.awaitReady();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IllegalStateException("Interrupted while logging the Discord bot in", exception);
    }
    final Guild guild = bot.getGuildById(guildId);
    if (guild == null) {
      throw new IllegalStateException("The Discord bot is not a member of the guild " + guildId);
    }
    final VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
    if (voiceChannel == null) {
      throw new IllegalStateException("The Discord voice channel " + channelId + " does not exist");
    }
    final AudioManager audioManager = guild.getAudioManager();
    final DiscordPlayer player = DiscordPlayer.voice(bot);
    return new DiscordConnection(voiceChannel, audioManager, player);
  }

  private void onDiscordConnected(final JDA bot, final @Nullable DiscordConnection connection, final @Nullable Throwable error) {
    if (error != null) {
      this.onDiscordFailed(bot, error);
      return;
    }
    synchronized (this.lock) {
      // the bot was shut down with the plugin while it connected, so the connection is dropped
      if (!this.stopped) {
        this.discord = connection;
      }
    }
  }

  private void onDiscordFailed(final JDA bot, final Throwable error) {
    LOGGER.error("The Discord bot could not connect, so its audio is not available", error);
    final boolean current;
    synchronized (this.lock) {
      // JDA does not override equals, so this asks whether the failed bot is still the very instance in use
      current = bot.equals(this.jda);
      if (current) {
        this.jda = null;
      }
    }
    // a bot that is no longer current was already shut down with the plugin
    if (current) {
      bot.shutdownNow();
    }
  }

  /**
   * Gets the link to the Discord voice channel.
   *
   * @return the channel URL
   */
  public String constructVoiceChannelUrl() {
    final String guildId = this.configuration.getDiscordBotGuildId();
    final String channelId = this.configuration.getDiscordBotChannelId();
    return "https://discord.com/channels/" + guildId + "/" + channelId;
  }

  /**
   * Gets the link to the audio web page.
   *
   * @return the page URL
   */
  public String constructHttpUrl() {
    final HttpResult server = this.httpServer;
    if (server != null) {
      return server.getFullUrl();
    }
    final String host = this.configuration.getHttpHostName();
    final int port = this.configuration.getHttpPort();
    return "http://" + host + ":" + port + "/";
  }

  /**
   * Checks whether the Discord bot is enabled in the configuration.
   *
   * @return true if enabled
   */
  public boolean isDiscordBotEnabled() {
    return this.configuration.isDiscordBotEnabled();
  }

  /**
   * Checks whether the Discord bot has logged in and found its voice channel, so it can play.
   *
   * @return true if ready, false while it starts, after it failed to start, or when it is disabled
   */
  public boolean isDiscordBotReady() {
    return this.discord != null;
  }

  /**
   * Checks whether the audio web page is enabled in the configuration.
   *
   * @return true if enabled
   */
  public boolean isHttpEnabled() {
    return this.configuration.isHttpEnabled();
  }

  /**
   * Checks whether the web server of the audio web page has started, so it can play.
   *
   * @return true if ready, false while it starts, after it failed to start, or when it is disabled
   */
  public boolean isHttpReady() {
    return this.httpServer != null;
  }

  /**
   * Creates the audio filter of an output for the video.
   *
   * @param argument the output chosen in the command
   * @param dump     information about the media, shown by outputs that display a title
   * @param players  the players Simple Voice Chat plays from
   * @return the filter to attach to the audio pipeline
   * @throws IllegalStateException if the Discord bot or the web page is chosen but not ready
   */
  public AudioFilter constructFilter(final AudioArgument argument, final URLParseDump dump, final Object[] players) {
    return this.constructFilter(argument, dump, players, VIDEO);
  }

  /**
   * Creates the audio filter of an output for a source. The outputs play one source at a time: the source takes them
   * over from any other, whose filter falls silent, and a filter plays only while its source has the outputs.
   *
   * @param argument the output chosen in the command
   * @param dump     information about the media, shown by outputs that display a title
   * @param players  the players Simple Voice Chat plays from
   * @param source   what plays, such as a virtual machine
   * @return the filter to attach to the audio pipeline
   * @throws IllegalStateException if the Discord bot or the web page is chosen but not ready
   */
  public AudioFilter constructFilter(final AudioArgument argument, final URLParseDump dump, final Object[] players, final Object source) {
    Preconditions.checkNotNull(argument, "Audio argument must not be null");
    Preconditions.checkNotNull(dump, "Dump must not be null");
    Preconditions.checkNotNull(players, "Players must not be null");
    Preconditions.checkNotNull(source, "Source must not be null");
    if (argument == AudioArgument.NONE) {
      return AudioFilter.NO_OP;
    }
    synchronized (this.outputLock) {
      if (!IDENTITY.equivalent(this.owner, source)) {
        // the speakers of the previous source stop; the bot and the web page play the new one from now on
        this.releaseSpeakers();
        this.owner = source;
      }
      final AudioFilter output =
        switch (argument) {
          case DISCORD_BOT -> this.constructDiscordFilter(dump);
          case HTTP_SERVER -> this.constructHttpFilter(dump);
          default -> this.constructSVCFilter(players);
        };
      return (samples, metadata) -> IDENTITY.equivalent(this.owner, source) && output.applyFilter(samples, metadata);
    }
  }

  private AudioFilter constructSVCFilter(final Object[] players) {
    final SVCFilter filter = SVCFilter.svc(players);
    filter.start();
    this.voiceChatFilter = filter;
    return filter;
  }

  private AudioFilter constructHttpFilter(final URLParseDump dump) {
    final HttpResult server = this.httpServer;
    if (server == null) {
      throw new IllegalStateException("The audio web page is not ready");
    }
    server.setCurrentMedia(dump);
    return server;
  }

  private AudioFilter constructDiscordFilter(final URLParseDump dump) {
    final DiscordConnection connection = this.discord;
    if (connection == null) {
      throw new IllegalStateException("The Discord bot is not ready");
    }
    final VoiceChannel voiceChannel = connection.getChannel();
    final AudioManager manager = connection.getAudioManager();
    final DiscordPlayer player = connection.getPlayer();
    player.flush();
    manager.setSendingHandler(player);
    manager.openAudioConnection(voiceChannel);
    player.setCurrentMedia(dump);
    return player;
  }

  /**
   * Disconnects the outputs, unless a virtual machine plays through them.
   */
  public void releaseAudioFilter() {
    this.releaseAudioFilter(VIDEO);
  }

  /**
   * Disconnects the outputs, unless another source plays through them.
   *
   * @param source the source that is done, such as a virtual machine
   */
  public void releaseAudioFilter(final Object source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    synchronized (this.outputLock) {
      final Object current = this.owner;
      if (current != null && !IDENTITY.equivalent(current, source)) {
        return;
      }
      this.owner = null;
      this.releaseOutputs();
    }
  }

  private void releaseOutputs() {
    final DiscordConnection connection = this.discord;
    if (connection != null) {
      final AudioManager manager = connection.getAudioManager();
      manager.setSendingHandler(null);
      manager.closeAudioConnection();
      final DiscordPlayer player = connection.getPlayer();
      player.flush();
    }
    final HttpResult server = this.httpServer;
    if (server != null) {
      server.setCurrentMedia(MediaInfo.EMPTY);
    }
    this.releaseSpeakers();
  }

  private void releaseSpeakers() {
    final SVCFilter speakers = this.voiceChatFilter;
    if (speakers != null) {
      speakers.release();
      this.voiceChatFilter = null;
    }
  }

  /**
   * Stops every output, including those that are still starting.
   */
  public void shutdown() {
    synchronized (this.lock) {
      this.stopped = true;
    }
    synchronized (this.outputLock) {
      this.owner = null;
      this.releaseOutputs();
    }
    final HttpResult server;
    final JDA bot;
    synchronized (this.lock) {
      server = this.httpServer;
      bot = this.jda;
      this.httpServer = null;
      this.jda = null;
      this.discord = null;
    }
    if (server != null) {
      server.stop();
    }
    if (bot != null) {
      bot.shutdown();
    }
    this.startup.shutdownNow();
  }

  /**
   * The voice channel the bot plays into, the audio connection of its guild, and the player that sends the audio.
   */
  private static final class DiscordConnection {

    private final VoiceChannel channel;
    private final AudioManager audioManager;
    private final DiscordPlayer player;

    DiscordConnection(final VoiceChannel channel, final AudioManager audioManager, final DiscordPlayer player) {
      this.channel = channel;
      this.audioManager = audioManager;
      this.player = player;
    }

    VoiceChannel getChannel() {
      return this.channel;
    }

    AudioManager getAudioManager() {
      return this.audioManager;
    }

    DiscordPlayer getPlayer() {
      return this.player;
    }
  }
}

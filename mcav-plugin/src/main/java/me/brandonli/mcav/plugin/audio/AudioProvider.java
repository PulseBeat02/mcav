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
package me.brandonli.mcav.plugin.audio;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.discord.DiscordPlayer;
import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.http.MediaInformation;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import me.brandonli.mcav.voicechat.SvcFilter;
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
 *
 * @since 1.0.0
 */
public final class AudioProvider {

  private static final String SOURCE_NULL_MESSAGE = "Source must not be null!";

  private static final Logger LOGGER = LoggerFactory.getLogger(AudioProvider.class);

  private static final long STARTUP_STOP_TIMEOUT_SECONDS = 10L;

  private static final Object VIDEO = new Object();
  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private static final String VOICE_CHAT_PLUGIN = "voicechat";

  private static final String DISCORD_CHANNELS_URL = "https://discord.com/channels/";

  private static final String HTTP_PREFIX = "http://";
  private static final String PORT_SEPARATOR = ":";
  private static final String PATH_SEPARATOR = "/";

  private final PluginDataConfigurationMapper configuration;
  private final MCAVSandbox plugin;
  private final ExecutorService startup;
  private final Object lock;

  private volatile @Nullable JDA jda;
  private volatile @Nullable DiscordConnection discord;
  private volatile @Nullable HttpResult httpServer;
  private volatile @Nullable SvcFilter voiceChatFilter;
  private boolean stopped;
  private final Object outputLock;
  private final Deque<Claim> claims;
  private volatile @Nullable Claim owner;

  /**
   * Creates the provider.
   *
   * @param plugin the plugin, whose configuration must be loaded
   * @since 1.0.0
   */
  public AudioProvider(final MCAVSandbox plugin) {
    this(plugin, Executors.newVirtualThreadPerTaskExecutor());
  }

  // the plugin's configuration must already be loaded
  @VisibleForTesting
  AudioProvider(final MCAVSandbox plugin, final ExecutorService startup) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");
    Preconditions.checkNotNull(startup, "Startup executor must not be null!");

    this.configuration = plugin.getConfiguration();
    this.plugin = plugin;
    this.startup = startup;
    this.lock = new Object();
    this.outputLock = new Object();
    this.claims = new ArrayDeque<>();
  }

  /**
   * Stops every output, including those that are still starting.
   *
   * <p>Interrupts startup and waits up to ten seconds for its cleanup before Paper closes the library loader.
   * A startup that does not terminate is reported; an interrupted wait preserves the caller's interrupt flag.
   *
   * @since 1.0.0
   */
  public void shutdown() {
    synchronized (this.lock) {
      this.stopped = true;
    }
    synchronized (this.outputLock) {
      this.owner = null;
      this.claims.clear();
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
    this.stopStartup();
  }

  /**
   * Starts the outputs enabled in the configuration. The Discord bot and the web server start on another thread,
   * so this returns at once; Simple Voice Chat is registered before it returns.
   *
   * @throws MissingVoiceChatException if Simple Voice Chat audio is enabled but the voicechat plugin is not installed
   * @since 1.0.0
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
    final boolean installed = pluginManager.isPluginEnabled(VOICE_CHAT_PLUGIN);
    if (!installed) {
      final String message = "Simple Voice Chat audio is enabled, but the voicechat plugin is not installed!";
      throw new MissingVoiceChatException(message);
    }

    final ServerLoadListener listener = new ServerLoadListener(this.plugin);
    pluginManager.registerEvents(listener, this.plugin);
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
      LOGGER.error("The audio web page could not be started, so its audio is not available.", error);
      return;
    }

    final boolean accepted;
    synchronized (this.lock) {
      accepted = !this.stopped;
      if (accepted) {
        this.httpServer = server;
      }
    }
    if (!accepted) {
      server.stop();
      return;
    }

    final String address = server.getFullUrl();
    LOGGER.info("Audio web page {} is available.", address);
  }

  private void startDiscord() {
    final String token = this.configuration.getDiscordBotToken();
    final String guildId = this.configuration.getDiscordBotGuildId();
    final String channelId = this.configuration.getDiscordBotChannelId();
    final JDA bot = this.createJda(token);
    synchronized (this.lock) {
      this.jda = bot;
    }
    final CompletableFuture<DiscordConnection> login = CompletableFuture.supplyAsync(
      () -> this.connectDiscord(bot, guildId, channelId),
      this.startup
    );
    TaskUtils.whenComplete(login, (connection, error) -> this.onDiscordConnected(bot, connection, error));
  }

  private JDA createJda(final String token) {
    final JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES);
    builder.enableCache(CacheFlag.VOICE_STATE);
    return builder.build();
  }

  private DiscordConnection connectDiscord(final JDA bot, final String guildId, final String channelId) {
    try {
      bot.awaitReady();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while logging the Discord bot in!";
      throw new PluginException(message, exception);
    }
    final Guild guild = bot.getGuildById(guildId);
    if (guild == null) {
      final String message = "Guild %s has not added the Discord bot!".formatted(guildId);
      throw new PluginException(message);
    }

    final VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
    if (voiceChannel == null) {
      final String message = "Discord voice channel %s does not exist!".formatted(channelId);
      throw new PluginException(message);
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
      if (!this.stopped) {
        this.discord = connection;
      }
    }
  }

  private void onDiscordFailed(final JDA bot, final Throwable error) {
    LOGGER.error("The Discord bot could not connect, so its audio is not available.", error);
    final boolean current;
    synchronized (this.lock) {
      // JDA does not override equals, so this asks whether the failed bot is still the very instance in use
      current = bot.equals(this.jda);
      if (current) {
        this.jda = null;
      }
    }
    if (current) {
      bot.shutdownNow();
    }
  }

  /**
   * Returns the link to the Discord voice channel.
   *
   * @return the channel URL
   * @since 1.0.0
   */
  public String constructVoiceChannelUrl() {
    final String guildId = this.configuration.getDiscordBotGuildId();
    final String channelId = this.configuration.getDiscordBotChannelId();
    return DISCORD_CHANNELS_URL + guildId + PATH_SEPARATOR + channelId;
  }

  /**
   * Returns the link to the audio web page.
   *
   * @return the page URL
   * @since 1.0.0
   */
  public String constructHttpUrl() {
    final HttpResult server = this.httpServer;
    if (server != null) {
      return server.getFullUrl();
    }

    final String host = this.configuration.getHttpHostName();
    final int port = this.configuration.getHttpPort();
    return HTTP_PREFIX + host + PORT_SEPARATOR + port + PATH_SEPARATOR;
  }

  /**
   * Creates the audio filter of an output for the video.
   *
   * @param argument the output chosen in the command
   * @param dump     information about the media, shown by outputs that display a title
   * @param players  the players Simple Voice Chat plays from
   * @return the filter to attach to the audio pipeline
   * @throws IllegalStateException if the Discord bot or the web page is chosen but not ready
   * @since 1.0.0
   */
  public AudioFilter constructFilter(final AudioArgument argument, final UrlParseDump dump, final Object[] players) {
    return this.constructFilter(argument, dump, players, VIDEO);
  }

  /**
   * Creates the audio filter of an output for a source. The outputs play one source at a time: the source takes them
   * over from any other, whose filter falls silent, and a filter plays only while its source has the outputs. When
   * the source is released, the outputs go back to the source it took them from, if that one was not released
   * meanwhile.
   *
   * @param argument the output chosen in the command
   * @param dump     information about the media, shown by outputs that display a title
   * @param players  the players Simple Voice Chat plays from
   * @param source   what plays, such as a virtual machine
   * @return the filter to attach to the audio pipeline
   * @throws IllegalStateException if the Discord bot or the web page is chosen but not ready
   * @since 1.0.0
   */
  public AudioFilter constructFilter(final AudioArgument argument, final UrlParseDump dump, final Object[] players, final Object source) {
    Preconditions.checkNotNull(argument, "Audio argument must not be null!");
    Preconditions.checkNotNull(dump, "Dump must not be null!");
    Preconditions.checkNotNull(players, "Players must not be null!");
    Preconditions.checkNotNull(source, SOURCE_NULL_MESSAGE);

    if (argument == AudioArgument.NONE) {
      return AudioFilter.NO_OP;
    }

    synchronized (this.outputLock) {
      // the speakers of the previous source stop only once the new source has its output, so a takeover that fails,
      // such as one through a bot that is not ready, leaves the previous source playing
      final SvcFilter previousSpeakers = this.voiceChatFilter;
      final Claim claim = new Claim(source, argument, dump, players);
      claim.output = this.connect(claim);
      this.claims.removeIf(held -> IDENTITY.equivalent(held.source, source));
      this.claims.push(claim);
      this.owner = claim;
      if (previousSpeakers != null) {
        this.retireSpeakers(previousSpeakers);
      }
      return (samples, metadata) -> this.owner == claim && claim.output.applyFilter(samples, metadata);
    }
  }

  @SuppressWarnings("ReferenceEquality") // clears the field only while it still holds this very filter
  private void retireSpeakers(final SvcFilter speakers) {
    if (this.voiceChatFilter == speakers) {
      this.voiceChatFilter = null;
    }
    speakers.release();
  }

  private AudioFilter connect(final Claim claim) {
    return switch (claim.argument) {
      case DISCORD_BOT -> this.constructDiscordFilter(claim.dump);
      case HTTP_SERVER -> this.constructHttpFilter(claim.dump);
      case SIMPLE_VOICE_CHAT -> this.constructSvcFilter(claim.players);
      case NONE -> {
        final String message = "Audio argument NONE has no output to connect!";
        throw new IllegalStateException(message);
      }
    };
  }

  private AudioFilter constructSvcFilter(final Object[] players) {
    final SvcFilter filter = SvcFilter.svc(players);
    filter.start();
    this.voiceChatFilter = filter;

    return filter;
  }

  private AudioFilter constructHttpFilter(final UrlParseDump dump) {
    final HttpResult server = this.httpServer;
    if (server == null) {
      final String message = "The audio web page is not ready!";
      throw new IllegalStateException(message);
    }

    server.setCurrentMedia(dump);
    return server;
  }

  private AudioFilter constructDiscordFilter(final UrlParseDump dump) {
    final DiscordConnection connection = this.discord;
    if (connection == null) {
      final String message = "The Discord bot is not ready!";
      throw new IllegalStateException(message);
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
   * Disconnects the outputs from the video, unless a virtual machine plays through them; they go back to the source the
   * video took them from, if that one still plays.
   *
   * @since 1.0.0
   */
  public void releaseAudioFilter() {
    this.releaseAudioFilter(VIDEO);
  }

  /**
   * Disconnects the outputs from a source that is done, unless another source plays through them; they go back to the
   * source it took them from, if that one still plays.
   *
   * @param source the source that is done, such as a virtual machine
   * @since 1.0.0
   */
  public void releaseAudioFilter(final Object source) {
    Preconditions.checkNotNull(source, SOURCE_NULL_MESSAGE);

    synchronized (this.outputLock) {
      this.claims.removeIf(held -> IDENTITY.equivalent(held.source, source));
      final Claim current = this.owner;
      if (current != null && !IDENTITY.equivalent(current.source, source)) {
        return;
      }

      this.owner = null;
      this.releaseOutputs();
      final Claim previous = this.claims.peek();
      if (previous == null) {
        return;
      }

      try {
        previous.output = this.connect(previous);
        this.owner = previous;
      } catch (final RuntimeException exception) {
        this.claims.remove(previous);
        LOGGER.warn("The audio outputs could not go back to the source that played before.", exception);
      }
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
      server.setCurrentMedia(MediaInformation.EMPTY);
    }
    this.releaseSpeakers();
  }

  private void releaseSpeakers() {
    final SvcFilter speakers = this.voiceChatFilter;
    if (speakers != null) {
      speakers.release();
      this.voiceChatFilter = null;
    }
  }

  private void stopStartup() {
    this.startup.shutdownNow();
    // Paper closes the library loader after onDisable returns, including jars a startup callback still needs.
    try {
      final boolean terminated = this.startup.awaitTermination(STARTUP_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!terminated) {
        LOGGER.warn(
          "Audio startup did not stop within {} seconds; restart the server before enabling MCAV again.",
          STARTUP_STOP_TIMEOUT_SECONDS
        );
      }
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      LOGGER.warn("Interrupted while waiting for audio startup to stop.");
    }
  }

  /**
   * Returns whether the Discord bot is enabled in the configuration.
   *
   * @return true if enabled
   * @since 1.0.0
   */
  public boolean isDiscordBotEnabled() {
    return this.configuration.isDiscordBotEnabled();
  }

  /**
   * Returns whether the Discord bot has logged in and found its voice channel, so it can play.
   *
   * @return true if ready, false while it starts, after it failed to start, or when it is disabled
   * @since 1.0.0
   */
  public boolean isDiscordBotReady() {
    return this.discord != null;
  }

  /**
   * Returns whether the audio web page is enabled in the configuration.
   *
   * @return true if enabled
   * @since 1.0.0
   */
  public boolean isHttpEnabled() {
    return this.configuration.isHttpEnabled();
  }

  /**
   * Returns whether the web server of the audio web page has started, so it can play.
   *
   * @return true if ready, false while it starts, after it failed to start, or when it is disabled
   * @since 1.0.0
   */
  public boolean isHttpReady() {
    return this.httpServer != null;
  }

  private static final class Claim {

    private final Object source;
    private final AudioArgument argument;
    private final UrlParseDump dump;
    private final Object[] players;
    private volatile AudioFilter output;

    private Claim(final Object source, final AudioArgument argument, final UrlParseDump dump, final Object[] players) {
      this.source = source;
      this.argument = argument;
      this.dump = dump;
      this.players = players;
      this.output = AudioFilter.NO_OP;
    }
  }

  private static final class DiscordConnection {

    private final VoiceChannel channel;
    private final AudioManager audioManager;
    private final DiscordPlayer player;

    DiscordConnection(final VoiceChannel channel, final AudioManager audioManager, final DiscordPlayer player) {
      this.channel = channel;
      this.audioManager = audioManager;
      this.player = player;
    }

    private VoiceChannel getChannel() {
      return this.channel;
    }

    private AudioManager getAudioManager() {
      return this.audioManager;
    }

    private DiscordPlayer getPlayer() {
      return this.player;
    }
  }
}

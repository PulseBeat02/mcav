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
package me.brandonli.mcav.sandbox.audio;

import static java.util.Objects.requireNonNull;

import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.jda.DiscordPlayer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class AudioProvider {

  private static final AudioFilter NO_OP = (samples, metadata) -> {};

  private final PluginDataConfigurationMapper config;

  private @Nullable JDA jda;
  private @Nullable AudioManager audioManager;
  private @Nullable VoiceChannel channel;
  private @Nullable DiscordPlayer discord;
  private @Nullable HttpResult result;

  public AudioProvider(final MCAVSandbox sandbox) {
    this.config = sandbox.getConfiguration();
  }

  public void initialize() {
    if (this.config.isDiscordBotEnabled()) {
      final String token = this.config.getDiscordBotToken();
      final String channelId = this.config.getDiscordBotChannelId();
      final String guildId = this.config.getDiscordBotGuildId();
      final JDA jda = this.createJDA(token);
      final Guild guild = requireNonNull(jda.getGuildById(guildId));
      this.channel = requireNonNull(guild.getVoiceChannelById(channelId));
      this.audioManager = guild.getAudioManager();
      this.discord = DiscordPlayer.voice();
      this.jda = jda;
    }
    if (this.config.isHttpEnabled()) {
      final int port = this.config.getHttpPort();
      final HttpResult result = HttpResult.port(port);
      result.start();
      this.result = result;
    }
  }

  public String constructVoiceChannelUrl() {
    final String channelId = this.config.getDiscordBotChannelId();
    final String guildId = this.config.getDiscordBotGuildId();
    return "https://discord.com/channels/%s/%s".formatted(guildId, channelId);
  }

  public String constructHttpUrl() {
    final String host = this.config.getHttpHostName();
    final int port = this.config.getHttpPort();
    return "http://%s:%d".formatted(host, port);
  }

  public boolean isDiscordBotEnabled() {
    return this.config.isDiscordBotEnabled();
  }

  public boolean isHttpEnabled() {
    return this.config.isHttpEnabled();
  }

  private JDA createJDA(final String token) {
    try {
      final JDA jda = JDABuilder.createDefault(token).build();
      jda.awaitReady();
      return jda;
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new AssertionError(e);
    }
  }

  public AudioFilter constructFilter(final AudioArgument argument) {
    return switch (argument) {
      case NONE -> NO_OP;
      case DISCORD_BOT -> this.constructDiscordFilter();
      case HTTP_SERVER -> this.constructHttpFilter();
    };
  }

  private AudioFilter constructHttpFilter() {
    return requireNonNull(this.result);
  }

  private AudioFilter constructDiscordFilter() {
    final VoiceChannel channel = this.channel;
    final DiscordPlayer discord = this.discord;
    final AudioManager audioManager = this.audioManager;
    requireNonNull(audioManager);
    requireNonNull(channel);
    requireNonNull(discord);
    audioManager.openAudioConnection(channel);
    audioManager.setSendingHandler(discord);
    return discord;
  }

  public void releaseAudioFilter() {
    final AudioManager audioManager = this.audioManager;
    if (audioManager != null) {
      audioManager.setSendingHandler(null);
      audioManager.closeAudioConnection();
    }
  }

  public void shutdown() {
    if (this.jda != null) {
      this.jda.shutdown();
      this.jda = null;
    }
    this.audioManager = null;
    this.channel = null;
  }
}

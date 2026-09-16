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
package me.brandonli.mcav.jda;

import java.nio.file.Path;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

/**
 * Plays the audio of a file into a voice channel. The bot token, guild id, voice channel id, and file path are
 * read from the {@code DISCORD_TOKEN}, {@code DISCORD_GUILD}, {@code DISCORD_CHANNEL}, and {@code MEDIA_FILE}
 * environment variables so that no secret ends up in the source.
 */
public final class JDAAudioExample {

  private JDAAudioExample() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Logs the bot in, joins the voice channel, and plays the file until the JVM exits.
   *
   * @throws InterruptedException if interrupted while the bot logs in
   */
  static void main() throws InterruptedException {
    final String token = require("DISCORD_TOKEN");
    final String guildId = require("DISCORD_GUILD");
    final String channelId = require("DISCORD_CHANNEL");
    final String file = require("MEDIA_FILE");
    final MCAVApi api = MCAV.api();
    api.install(JDAModule.class);

    final JDA jda = login(token);
    final Guild guild = findGuild(jda, guildId);
    final VoiceChannel channel = findVoiceChannel(guild, channelId);
    final DiscordPlayer player = DiscordPlayer.voice(jda);
    final AudioManager audioManager = guild.getAudioManager();
    audioManager.setSendingHandler(player);
    audioManager.openAudioConnection(channel);

    final VideoPlayerMultiplexer multiplexer = play(player, file);
    player.setPlaying(file);
    final Thread shutdownHook = new Thread(() -> {
      multiplexer.release();
      audioManager.closeAudioConnection();
      jda.shutdown();
      api.release();
    });
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(shutdownHook);
  }

  private static JDA login(final String token) throws InterruptedException {
    final JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES);
    builder.enableCache(CacheFlag.VOICE_STATE);
    final JDA jda = builder.build();
    jda.awaitReady();
    return jda;
  }

  private static Guild findGuild(final JDA jda, final String guildId) {
    final Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      throw new IllegalStateException("The bot is not a member of guild " + guildId);
    }
    return guild;
  }

  private static VoiceChannel findVoiceChannel(final Guild guild, final String channelId) {
    final VoiceChannel channel = guild.getVoiceChannelById(channelId);
    if (channel == null) {
      throw new IllegalStateException("Voice channel " + channelId + " does not exist");
    }
    return channel;
  }

  private static VideoPlayerMultiplexer play(final DiscordPlayer player, final String file) {
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(player);
    final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
    final AudioAttachableCallback audio = multiplexer.getAudioAttachableCallback();
    audio.attach(audioPipeline);
    final Path path = Path.of(file);
    final Source source = FileSource.path(path);
    multiplexer.start(source);
    return multiplexer;
  }

  private static String require(final String variable) {
    final String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Set the " + variable + " environment variable");
    }
    return value;
  }
}

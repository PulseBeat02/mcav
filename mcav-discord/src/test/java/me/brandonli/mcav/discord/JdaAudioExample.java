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
package me.brandonli.mcav.discord;

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

public final class JdaAudioExample {

  private static final String TOKEN_VARIABLE = "DISCORD_TOKEN";
  private static final String GUILD_VARIABLE = "DISCORD_GUILD";
  private static final String CHANNEL_VARIABLE = "DISCORD_CHANNEL";
  private static final String MEDIA_FILE_VARIABLE = "MEDIA_FILE";

  private JdaAudioExample() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static void main() throws InterruptedException {
    final String token = getEnvironmentVariable(TOKEN_VARIABLE);
    final String guildId = getEnvironmentVariable(GUILD_VARIABLE);
    final String channelId = getEnvironmentVariable(CHANNEL_VARIABLE);
    final String file = getEnvironmentVariable(MEDIA_FILE_VARIABLE);

    final MCAVApi api = MCAV.api();
    api.install(JdaModule.class);

    final JDA jda = login(token);
    final Guild guild = findGuild(jda, guildId);
    final VoiceChannel channel = findVoiceChannel(guild, channelId);
    final DiscordPlayer player = DiscordPlayer.voice(jda);
    final AudioManager audioManager = guild.getAudioManager();
    audioManager.setSendingHandler(player);
    audioManager.openAudioConnection(channel);

    final VideoPlayerMultiplexer multiplexer = play(player, file);
    player.setPlaying(file);

    final Thread shutdownHook = new Thread(() -> release(multiplexer, audioManager, jda, api));
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(shutdownHook);
  }

  private static String getEnvironmentVariable(final String variable) {
    final String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      final String message = "Environment variable %s must be set!".formatted(variable);
      throw new IllegalStateException(message);
    }

    return value;
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
      final String message = "Guild %s must have the bot as a member!".formatted(guildId);
      throw new IllegalStateException(message);
    }

    return guild;
  }

  private static VoiceChannel findVoiceChannel(final Guild guild, final String channelId) {
    final VoiceChannel channel = guild.getVoiceChannelById(channelId);
    if (channel == null) {
      final String message = "Voice channel %s does not exist!".formatted(channelId);
      throw new IllegalStateException(message);
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

  private static void release(final VideoPlayerMultiplexer multiplexer, final AudioManager audioManager, final JDA jda, final MCAVApi api) {
    multiplexer.release();
    audioManager.closeAudioConnection();
    jda.shutdown();
    api.release();
  }
}

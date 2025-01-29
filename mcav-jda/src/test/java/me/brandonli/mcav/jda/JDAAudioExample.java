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
package me.brandonli.mcav.jda;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;

public final class JDAAudioExample {

  public static void main(final String[] args) throws InterruptedException {
    final MCAVApi api = MCAV.api();
    api.install();

    // "MTM3MjczNDQyNDQwMzE0ODk3MA.Gv7sgZ.3t5njZYT97wAFvM6dHSKjaEh6VZ9IfJRu4jg_0"

    final JDA jda = JDABuilder.createDefault("MTM3MjczNDQyNDQwMzE0ODk3MA.Gywon0.8YE4dZkQAq7AFEjKUSitxSSB39hAe_xcf6qN-M").build();
    jda.awaitReady();

    final Guild guild = requireNonNull(jda.getGuildById("1372733795769520271"));
    final VoiceChannel voiceChannel = requireNonNull(guild.getVoiceChannelById("1372733796709040221"));
    final AudioManager audioManager = guild.getAudioManager();
    audioManager.openAudioConnection(voiceChannel);

    final Source source = FileSource.path(Path.of("C:\\rickroll.mp4"));
    final DiscordPlayer player = DiscordPlayer.voice();
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(player);
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.NO_OP;
    audioManager.setSendingHandler(player);

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.start(audioPipelineStep, videoPipelineStep, source);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          multiplexer.release();
          api.release();
          jda.shutdown();
        })
      );
  }
}

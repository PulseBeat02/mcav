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

import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/**
 * Represents a Discord audio filter which can be used to play audio in voice channels. Implements both
 * {@link AudioFilter}, and JDA {@link AudioSendHandler} and {@link AudioReceiveHandler} interfaces for
 * providing and sending audio data. You don't have to worry about transcoding data, as the implementation
 * already handles that conversion for you.
 * <p>
 * Here is an example of how to use it with the JDA API.
 *
 * <pre><code>
 *     final JDA jda = JDABuilder.createDefault(...).build();
 *     jda.awaitReady();
 *
 *     final Guild guild = jda.getGuildById(...);
 *     final VoiceChannel voiceChannel = guild.getVoiceChannelById(...);
 *     final AudioManager audioManager = guild.getAudioManager();
 *     audioManager.openAudioConnection(voiceChannel);
 *
 *     final DiscordPlayer player = DiscordPlayer.voice();
 *     audioManager.setSendingHandler(player);
 *
 *     final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(player);
 *     ...
 * </code></pre>
 */
public interface DiscordPlayer extends AudioFilter, AudioSendHandler, AudioReceiveHandler {
  /**
   * Creates a new instance of {@link DiscordPlayer}.
   *
   * @return a new instance of {@link DiscordPlayer}
   */
  static DiscordPlayer voice() {
    return new DiscordPlayerImpl();
  }
}

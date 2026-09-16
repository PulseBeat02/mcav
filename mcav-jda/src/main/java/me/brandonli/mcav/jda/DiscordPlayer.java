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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/**
 * Sends the audio of a pipeline into a Discord voice channel.
 *
 * <p>The player is both an {@link AudioFilter}, so it can be attached to the audio pipeline of any
 * {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer}, and an {@link AudioSendHandler}, so JDA can pull
 * 20 millisecond frames from it. Samples are queued in a buffer of a few seconds; when the pipeline runs ahead of
 * Discord, the oldest samples are dropped.
 *
 * <pre><code>
 *   final DiscordPlayer player = DiscordPlayer.voice(jda);
 *   final AudioManager audioManager = guild.getAudioManager();
 *   audioManager.setSendingHandler(player);
 *   audioManager.openAudioConnection(channel);
 *   final AudioAttachableCallback audio = videoPlayer.getAudioAttachableCallback();
 *   final AudioPipelineStep step = AudioPipelineStep.of(player);
 *   audio.attach(step);
 * </code></pre>
 */
public interface DiscordPlayer extends AudioFilter, AudioSendHandler {
  /**
   * Creates a player for a bot.
   *
   * @param jda the logged-in bot
   * @return the player
   */
  static DiscordPlayer voice(final JDA jda) {
    Preconditions.checkNotNull(jda, "JDA must not be null");
    return new DiscordPlayerImpl(jda);
  }

  /**
   * Sets the presence of the bot to "Playing" the title of the media yt-dlp resolved. The title is shown as
   * {@link #setPlaying(String)} describes; media without a title shows "Unknown title".
   *
   * @param dump the media information from yt-dlp
   */
  void setCurrentMedia(final URLParseDump dump);

  /**
   * Sets the presence of the bot to "Playing" a title. Any title is accepted and adjusted to the rules of
   * Discord: surrounding whitespace is removed, a blank title shows "Unknown title", and a title longer than
   * {@link net.dv8tion.jda.api.entities.Activity#MAX_ACTIVITY_NAME_LENGTH} characters is cut and ends with an
   * ellipsis character (U+2026), so it fits the limit.
   *
   * @param title the title
   */
  void setPlaying(final String title);

  /**
   * Drops every queued sample, for example after seeking or stopping the source.
   */
  void flush();

  /**
   * Gets the number of milliseconds of audio waiting to be sent.
   *
   * @return the queued duration in milliseconds
   */
  long getQueuedMillis();
}

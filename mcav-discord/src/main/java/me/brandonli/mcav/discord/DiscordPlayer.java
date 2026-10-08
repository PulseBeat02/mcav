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
 * Discord, the oldest complete frames are dropped. The default player keeps at most three seconds of complete
 * frames and a partial frame, using 48 kHz signed 16-bit stereo PCM as required by {@link AudioFilter}.
 *
 * <p>The default player synchronizes queue operations, so pipeline producers and JDA's sending thread may run
 * concurrently. Do not change an input buffer while a filter call reads it. Queue queries are snapshots.
 * The caller owns the JDA instance and voice connection: detach the audio pipeline, {@link #flush()} the queue,
 * close the voice connection and shut down the bot when they are no longer needed. This player has no native
 * resources to close, and stopping {@link JDAModule} does not close the bot.
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
   * @param jda the non-null logged-in bot; ownership remains with the caller
   * @return a new player with an empty queue; it does not open a voice connection
   * @throws NullPointerException if {@code jda} is null
   */
  static DiscordPlayer voice(final JDA jda) {
    Preconditions.checkNotNull(jda, "JDA must not be null");
    return new DiscordPlayerImpl(jda);
  }

  /**
   * Sets the presence of the bot to "Playing" the title of the media yt-dlp resolved. The title is shown as
   * {@link #setPlaying(String)} describes; media without a title shows "Unknown title".
   *
   * @param dump the non-null media information from yt-dlp; its title may be null
   * @throws NullPointerException if {@code dump} is null
   */
  void setCurrentMedia(final URLParseDump dump);

  /**
   * Sets the presence of the bot to "Playing" a title. Any title is accepted and adjusted to the rules of
   * Discord: surrounding whitespace is removed, a blank title shows "Unknown title", and a title longer than
   * {@link net.dv8tion.jda.api.entities.Activity#MAX_ACTIVITY_NAME_LENGTH} characters is cut and ends with an
   * ellipsis character (U+2026), so it fits the limit.
   *
   * @param title the non-null title; an empty or blank string shows "Unknown title"
   * @throws NullPointerException if {@code title} is null
   */
  void setPlaying(final String title);

  /**
   * Drops every queued sample and any incomplete frame, for example after seeking or stopping the source.
   * Producers can enqueue new audio immediately after this call.
   */
  void flush();

  /**
   * Gets the number of milliseconds of audio waiting to be sent.
   *
   * @return the duration of complete frames in milliseconds, from 0 to 3000 in multiples of 20;
   *         the incomplete frame is not counted
   */
  long getQueuedMillis();
}

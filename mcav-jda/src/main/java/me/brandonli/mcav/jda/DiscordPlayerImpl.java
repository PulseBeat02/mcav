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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.utils.natives.ByteUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.managers.Presence;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link DiscordPlayer}. Incoming samples are little-endian, as the {@link AudioFilter} contract
 * guarantees, whatever order the buffer object reports. They are converted to the big-endian order Discord expects
 * and cut into 20 millisecond frames of {@value #FRAME_BYTES} bytes, which are queued until JDA asks for them.
 * The player sends PCM, so it keeps the {@link #isOpus()} of {@link net.dv8tion.jda.api.audio.AudioSendHandler},
 * which returns false.
 */
public final class DiscordPlayerImpl implements DiscordPlayer {

  /**
   * The size of one 20 millisecond frame of 48 kHz 16-bit stereo audio in bytes.
   */
  static final int FRAME_BYTES = (AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * 20) / 1000;

  /**
   * The activity shown for media without a title.
   */
  static final String UNKNOWN_TITLE = "Unknown title";

  /**
   * The character that ends a title cut to the length limit of Discord.
   */
  static final String ELLIPSIS = "…";

  private static final int MAX_QUEUED_FRAMES = 150; // three seconds
  private static final int FRAME_MILLIS = 20;

  private final JDA jda;
  private final Deque<ByteBuffer> frames;
  private final ByteBuffer partial;

  /**
   * Creates a player for a bot.
   *
   * @param jda the logged-in bot whose presence shows the current title
   */
  DiscordPlayerImpl(final JDA jda) {
    Preconditions.checkNotNull(jda, "JDA must not be null");
    this.jda = jda;
    this.frames = new ArrayDeque<>(MAX_QUEUED_FRAMES);
    this.partial = ByteBuffer.allocate(FRAME_BYTES);
  }

  /**
   * Converts the remaining samples to big-endian and queues them as 20 millisecond frames. Samples that do not
   * fill a frame yet wait for the next call. When three seconds of frames are queued, the oldest frame is
   * dropped. The position and byte order of the buffer are not changed.
   *
   * @param samples  the little-endian 16-bit stereo samples between the position and the limit of the buffer
   * @param metadata the metadata of the original audio
   * @return always false, because the samples are only read out into frames and never changed
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");

    // the pipeline always carries little-endian samples, even in a buffer whose order was never set
    final ByteBuffer littleEndian = samples.duplicate();
    littleEndian.order(ByteOrder.LITTLE_ENDIAN);
    final ByteBuffer bigEndian = ByteUtils.toBigEndian(littleEndian);
    synchronized (this.frames) {
      while (bigEndian.hasRemaining()) {
        this.fillPartialFrame(bigEndian);
      }
    }
    return false;
  }

  /**
   * Moves as many bytes into the partial frame as it has room for, and queues the frame once it is full.
   *
   * @param bigEndian the converted samples; its position advances by the number of bytes moved
   */
  private void fillPartialFrame(final ByteBuffer bigEndian) {
    final int wanted = this.partial.remaining();
    final int available = bigEndian.remaining();
    final int count = Math.min(wanted, available);
    final int limit = bigEndian.limit();
    final int position = bigEndian.position();
    bigEndian.limit(position + count);
    this.partial.put(bigEndian);
    bigEndian.limit(limit);

    final boolean full = !this.partial.hasRemaining();
    if (full) {
      this.enqueueFrame();
    }
  }

  private void enqueueFrame() {
    final byte[] copy = new byte[FRAME_BYTES];
    this.partial.flip();
    this.partial.get(copy);
    this.partial.clear();

    final ByteBuffer frame = ByteBuffer.wrap(copy);
    if (this.frames.size() >= MAX_QUEUED_FRAMES) {
      this.frames.pollFirst();
    }
    this.frames.addLast(frame);
  }

  /**
   * Checks whether a complete frame is queued, which JDA asks before every 20 millisecond interval.
   *
   * @return true if {@link #provide20MsAudio()} returns a frame
   */
  @Override
  public boolean canProvide() {
    synchronized (this.frames) {
      return !this.frames.isEmpty();
    }
  }

  /**
   * Takes the oldest queued frame for JDA to send.
   *
   * @return a big-endian frame of {@value #FRAME_BYTES} bytes, or null if nothing is queued
   */
  @Override
  public @Nullable ByteBuffer provide20MsAudio() {
    synchronized (this.frames) {
      return this.frames.pollFirst();
    }
  }

  /**
   * Drops every queued frame and the samples that did not fill a frame yet.
   */
  @Override
  public void flush() {
    synchronized (this.frames) {
      this.frames.clear();
      this.partial.clear();
    }
  }

  /**
   * Gets the duration of the complete frames waiting to be sent; samples that do not fill a frame yet are not
   * counted.
   *
   * @return the queued duration in milliseconds, a multiple of 20
   */
  @Override
  public long getQueuedMillis() {
    synchronized (this.frames) {
      final int count = this.frames.size();
      return (long) count * FRAME_MILLIS;
    }
  }

  /**
   * Sets the presence of the bot to "Playing" the title yt-dlp reported, adjusted as {@link #setPlaying(String)}
   * describes.
   *
   * @param dump the media information from yt-dlp
   */
  @Override
  public void setCurrentMedia(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    final String title = dump.title;
    this.showPlaying(title);
  }

  /**
   * Sets the status of the bot to online and its activity to "Playing" the title, adjusted to the rules of
   * Discord.
   *
   * @param title the title
   */
  @Override
  public void setPlaying(final String title) {
    Preconditions.checkNotNull(title, "Title must not be null");
    this.showPlaying(title);
  }

  private void showPlaying(final @Nullable String title) {
    final String name = activityName(title);
    final Presence presence = this.jda.getPresence();
    final Activity activity = Activity.playing(name);
    presence.setStatus(OnlineStatus.ONLINE);
    presence.setActivity(activity);
  }

  /**
   * Turns a title into a name Discord accepts for an activity: surrounding whitespace is removed, a missing or
   * blank title becomes {@value #UNKNOWN_TITLE}, and a title longer than
   * {@link Activity#MAX_ACTIVITY_NAME_LENGTH} characters is cut and ends with an ellipsis, without splitting a
   * character outside the Basic Multilingual Plane such as an emoji.
   *
   * @param title the title, or null if unknown
   * @return the activity name, at most {@link Activity#MAX_ACTIVITY_NAME_LENGTH} characters long
   */
  private static String activityName(final @Nullable String title) {
    if (title == null) {
      return UNKNOWN_TITLE;
    }
    final String stripped = title.strip();
    if (stripped.isEmpty()) {
      return UNKNOWN_TITLE;
    }
    final int length = stripped.length();
    if (length <= Activity.MAX_ACTIVITY_NAME_LENGTH) {
      return stripped;
    }

    int end = Activity.MAX_ACTIVITY_NAME_LENGTH - ELLIPSIS.length();
    final char last = stripped.charAt(end - 1);
    if (Character.isHighSurrogate(last)) {
      end--;
    }
    final String kept = stripped.substring(0, end);
    return kept + ELLIPSIS;
  }
}

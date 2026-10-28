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

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
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
 *
 * <p>This class is thread-safe: queue operations are synchronized, so pipeline producers and JDA's sending thread
 * may call it concurrently.
 *
 * @since 1.0.0
 */
public final class PcmDiscordPlayer implements DiscordPlayer {

  private static final int FRAME_MILLISECONDS = 20;
  private static final int MILLISECONDS_PER_SECOND = 1000;

  static final int FRAME_BYTES = (AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * FRAME_MILLISECONDS) / MILLISECONDS_PER_SECOND;

  private static final int MAXIMUM_QUEUED_FRAMES = 150;

  private static final String UNKNOWN_TITLE = "Unknown title";

  private static final String ELLIPSIS = "…";

  private static final char LAST_CONTROL_CHARACTER = '\u001F';
  private static final CharMatcher WHITESPACE_OR_CONTROL = CharMatcher.forPredicate(Character::isWhitespace).or(
    CharMatcher.inRange(Character.MIN_VALUE, LAST_CONTROL_CHARACTER)
  );

  private final JDA jda;
  private final Deque<ByteBuffer> frames;
  private final ByteBuffer partial;

  PcmDiscordPlayer(final JDA jda) {
    this.jda = jda;
    this.frames = new ArrayDeque<>(MAXIMUM_QUEUED_FRAMES);
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
   * @throws NullPointerException if {@code samples} or {@code metadata} is null
   * @since 1.0.0
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

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
    if (this.frames.size() >= MAXIMUM_QUEUED_FRAMES) {
      this.frames.pollFirst();
    }
    this.frames.addLast(frame);
  }

  /**
   * Returns whether a complete frame is queued, which JDA asks before every 20 millisecond interval.
   *
   * @return true if a complete frame is queued at the time of this call; a concurrent flush or consumer
   *         can remove it before {@link #provide20MsAudio()}
   * @since 1.0.0
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
   * @return a big-endian PCM frame of {@value #FRAME_BYTES} bytes with position zero, owned by the caller,
   *         or null if nothing is queued; the player does not reuse or change a returned buffer
   * @since 1.0.0
   */
  @Override
  public @Nullable ByteBuffer provide20MsAudio() {
    synchronized (this.frames) {
      return this.frames.pollFirst();
    }
  }

  /**
   * Drops every queued frame and the samples that did not fill a frame yet.
   *
   * @since 1.0.0
   */
  @Override
  public void flush() {
    synchronized (this.frames) {
      this.frames.clear();
      this.partial.clear();
    }
  }

  /**
   * Sets the presence of the bot to "Playing" the title yt-dlp reported, adjusted as {@link #setPlaying(String)}
   * describes.
   *
   * @param dump the media information from yt-dlp
   * @throws NullPointerException if {@code dump} is null
   * @since 1.0.0
   */
  @Override
  public void setCurrentMedia(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null!");

    final Optional<String> titleValue = dump.getTitle();
    final @Nullable String title = titleValue.orElse(null);
    this.showPlaying(title);
  }

  /**
   * Sets the status of the bot to online and its activity to "Playing" the title, adjusted to the rules of
   * Discord.
   *
   * @param title the title
   * @throws NullPointerException if {@code title} is null
   * @since 1.0.0
   */
  @Override
  public void setPlaying(final String title) {
    Preconditions.checkNotNull(title, "Title must not be null!");

    this.showPlaying(title);
  }

  private void showPlaying(final @Nullable String title) {
    final String name = this.toActivityName(title);
    final Presence presence = this.jda.getPresence();
    final Activity activity = Activity.playing(name);
    presence.setStatus(OnlineStatus.ONLINE);
    presence.setActivity(activity);
  }

  private String toActivityName(final @Nullable String title) {
    if (title == null) {
      return UNKNOWN_TITLE;
    }

    final String trimmed = WHITESPACE_OR_CONTROL.trimFrom(title);
    if (trimmed.isEmpty()) {
      return UNKNOWN_TITLE;
    }

    final int length = trimmed.length();
    if (length <= Activity.MAX_ACTIVITY_NAME_LENGTH) {
      return trimmed;
    }

    final int end = this.findCutIndex(trimmed);
    final String kept = trimmed.substring(0, end);
    return kept + ELLIPSIS;
  }

  private int findCutIndex(final String title) {
    final int end = Activity.MAX_ACTIVITY_NAME_LENGTH - ELLIPSIS.length();
    final char last = title.charAt(end - 1);
    final boolean splitsCharacter = Character.isHighSurrogate(last);
    return splitsCharacter ? end - 1 : end;
  }

  /**
   * Returns the duration of the complete frames waiting to be sent; samples that do not fill a frame yet are not
   * counted.
   *
   * @return the queued duration in milliseconds, from 0 to 3000 in multiples of 20
   * @since 1.0.0
   */
  @Override
  public long getQueuedMilliseconds() {
    synchronized (this.frames) {
      final int count = this.frames.size();
      return (long) count * FRAME_MILLISECONDS;
    }
  }
}

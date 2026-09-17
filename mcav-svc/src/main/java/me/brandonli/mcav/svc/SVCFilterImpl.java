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
package me.brandonli.mcav.svc;

import com.google.common.base.Preconditions;
import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.MonoDownmixer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The default {@link SVCFilter}.
 *
 * <p>Samples are mixed to mono and cut into 20 millisecond frames of {@value #FRAME_SAMPLES} samples. Every
 * speaker has its own queue, so each of them plays every frame; a speaker that falls behind drops its oldest
 * frames. Voice chat pulls frames on its own thread, and a speaker without a frame plays silence instead of
 * stopping. When {@link #start()} fails part way, every speaker it created is stopped again, so the filter stays
 * stopped and can simply be started again.
 */
public final class SVCFilterImpl implements SVCFilter {

  /**
   * The number of mono samples in one 20 millisecond frame at 48 kHz.
   */
  static final int FRAME_SAMPLES = 960;

  private static final int MAX_QUEUED_FRAMES = 25; // half a second
  private static final short[] SILENCE = new short[FRAME_SAMPLES];

  private final float distance;
  private final Object[] entities;
  private final List<Speaker> speakers;
  private final short[] partial;

  private int partialLength;
  private boolean running;

  /**
   * Constructs a stopped filter. {@link SVCFilter#withDistance(float, Object...)} validates the arguments.
   *
   * @param distance the distance in blocks the audio can be heard from
   * @param entities the platform entities the audio comes from; the array is copied
   * @throws IllegalStateException if the voice chat API was not injected into {@link SVCModule}
   */
  SVCFilterImpl(final float distance, final Object[] entities) {
    // fail early when the API is missing instead of on the first samples
    SVCModule.requireVoiceChatApi();
    this.distance = distance;
    this.entities = entities.clone();
    this.speakers = new ArrayList<>();
    this.partial = new short[FRAME_SAMPLES];
  }

  /**
   * Creates one voice chat speaker per entity and starts playing. Calling it while the filter already plays does
   * nothing. When voice chat fails to create one of the speakers, the speakers created so far are stopped again and
   * the failure is rethrown, so the filter stays stopped.
   *
   * @throws IllegalStateException if the voice chat API was removed from {@link SVCModule} after creation
   * @throws PlayerException       if voice chat refuses to create an audio channel for an entity
   */
  @Override
  public synchronized void start() {
    if (this.running) {
      return;
    }

    final VoicechatServerApi api = SVCModule.requireVoiceChatApi();
    try {
      this.createSpeakers(api);
    } catch (final RuntimeException | Error exception) {
      // voice chat is third-party code that loads the native Opus codec, so it can fail with a LinkageError as well;
      // the speakers that already play are stopped for any recoverable failure, so a later start does not add them a
      // second time, and the failure is rethrown unchanged
      ThrowableUtils.throwIfFatal(exception);
      this.stopSpeakers();
      throw exception;
    }

    this.partialLength = 0;
    this.running = true;
  }

  private void createSpeakers(final VoicechatServerApi api) {
    for (final Object platformEntity : this.entities) {
      final Speaker speaker = this.createSpeaker(api, platformEntity);
      this.speakers.add(speaker);
    }
  }

  private Speaker createSpeaker(final VoicechatServerApi api, final Object platformEntity) {
    final Entity entity = api.fromEntity(platformEntity);
    final UUID channelId = UUID.randomUUID();
    final EntityAudioChannel channel = api.createEntityAudioChannel(channelId, entity);
    if (channel == null) {
      throw new PlayerException("Simple Voice Chat refused to create an audio channel for " + platformEntity);
    }
    channel.setDistance(this.distance);

    final OpusEncoder encoder = api.createEncoder();
    final Speaker speaker = new Speaker(encoder);
    try {
      final AudioPlayer player = api.createAudioPlayer(channel, encoder, speaker::nextFrame);
      speaker.setPlayer(player);
      player.startPlaying();
    } catch (final RuntimeException | Error exception) {
      // creating or starting the player runs third-party code that can fail with a LinkageError as well; the speaker
      // is not listed yet, so its encoder and player are released here before the failure is rethrown unchanged
      ThrowableUtils.throwIfFatal(exception);
      speaker.stop();
      throw exception;
    }
    return speaker;
  }

  /**
   * Stops every speaker, closes their encoders and drops all queued and partially collected audio. The filter can be
   * started again afterwards, and releasing a stopped filter does nothing.
   */
  @Override
  public synchronized void release() {
    this.running = false;
    this.stopSpeakers();
    this.partialLength = 0;
  }

  private void stopSpeakers() {
    for (final Speaker speaker : this.speakers) {
      speaker.stop();
    }
    this.speakers.clear();
  }

  /**
   * Queues the samples for every speaker. The samples are not changed and are always passed on to the next step;
   * while the filter is not started they are only not played.
   *
   * @param samples  little-endian interleaved 16-bit stereo samples
   * @param metadata the metadata of the original stream
   * @return always false, because the samples are only downmixed into a copy and never changed
   * @throws NullPointerException if the samples or the metadata are null
   */
  @Override
  public synchronized boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    if (!this.running) {
      return false;
    }

    final short[] mono = MonoDownmixer.downmix(samples);
    int offset = 0;
    while (offset < mono.length) {
      offset = this.collect(mono, offset);
    }
    return false;
  }

  /**
   * Copies as many samples as still fit into the partial frame, and hands the frame to every speaker once it is full.
   *
   * @param mono   the mono samples
   * @param offset the index of the first sample that was not collected yet
   * @return the index of the first sample that is still left after this call
   */
  private int collect(final short[] mono, final int offset) {
    final int space = FRAME_SAMPLES - this.partialLength;
    final int available = mono.length - offset;
    final int count = Math.min(space, available);
    System.arraycopy(mono, offset, this.partial, this.partialLength, count);
    this.partialLength += count;

    if (this.partialLength == FRAME_SAMPLES) {
      final short[] frame = this.partial.clone();
      for (final Speaker speaker : this.speakers) {
        speaker.enqueue(frame);
      }
      this.partialLength = 0;
    }
    return offset + count;
  }

  /**
   * Gets the number of 20 millisecond frames the slowest speaker still has to play.
   *
   * @return the largest queue length of all speakers, or 0 while the filter is stopped
   */
  @Override
  public synchronized int getQueuedFrames() {
    int most = 0;
    for (final Speaker speaker : this.speakers) {
      final int queued = speaker.getQueuedFrames();
      most = Math.max(most, queued);
    }
    return most;
  }

  /**
   * One entity that plays the audio, with its own frame queue.
   */
  private static final class Speaker {

    private final OpusEncoder encoder;
    private final Deque<short[]> frames;

    // null only until voice chat has created the player of this speaker, never null again afterwards
    private @MonotonicNonNull AudioPlayer player;

    Speaker(final OpusEncoder encoder) {
      this.encoder = encoder;
      this.frames = new ArrayDeque<>(MAX_QUEUED_FRAMES);
    }

    void setPlayer(final AudioPlayer player) {
      this.player = player;
    }

    void enqueue(final short[] frame) {
      synchronized (this.frames) {
        if (this.frames.size() >= MAX_QUEUED_FRAMES) {
          this.frames.pollFirst();
        }
        this.frames.addLast(frame);
      }
    }

    // called by voice chat every 20 ms; returning null would end playback, so silence is returned instead
    short[] nextFrame() {
      synchronized (this.frames) {
        final short[] frame = this.frames.pollFirst();
        if (frame == null) {
          return SILENCE;
        }
        return frame;
      }
    }

    int getQueuedFrames() {
      synchronized (this.frames) {
        return this.frames.size();
      }
    }

    // the player is missing when voice chat failed to create it
    void stop() {
      final AudioPlayer current = this.player;
      if (current != null) {
        current.stopPlaying();
      }
      this.encoder.close();
      synchronized (this.frames) {
        this.frames.clear();
      }
    }
  }
}

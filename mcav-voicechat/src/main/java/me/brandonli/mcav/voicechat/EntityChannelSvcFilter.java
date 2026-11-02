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
package me.brandonli.mcav.voicechat;

import com.google.common.base.Equivalence;
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
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.MonoDownmixer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link SvcFilter}.
 *
 * <p>Samples are mixed to mono and cut into 20 millisecond frames of {@value #FRAME_SAMPLES} samples. Every
 * speaker has its own queue, so each of them plays every frame; a speaker that falls behind drops its oldest
 * frames. Voice chat pulls frames on its own thread, and a speaker without a frame plays silence instead of
 * stopping. When {@link #start()} fails part way, every speaker it created is stopped again, so the filter stays
 * stopped and can simply be started again.
 *
 * <p>This class is thread-safe: its public methods are synchronized, and each speaker guards its own queue for the
 * voice chat thread.
 *
 * @since 1.0.0
 */
public final class EntityChannelSvcFilter implements SvcFilter {

  private static final int FRAME_SAMPLES = 960;
  private static final short[] SILENCE = new short[FRAME_SAMPLES];

  private static final int MAXIMUM_QUEUED_FRAMES = 25;

  private final float distance;
  private final Object[] entities;
  private final List<Speaker> speakers;
  private final short[] partial;

  private int partialLength;
  private boolean running;

  EntityChannelSvcFilter(final float distance, final Object[] entities) {
    SvcModule.requireVoiceChatApi();

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
   * @throws IllegalStateException if the voice chat API was removed from {@link SvcModule} after creation
   * @throws SvcException          if voice chat refuses to create an audio channel for an entity
   * @since 1.0.0
   */
  @Override
  public synchronized void start() {
    if (this.running) {
      return;
    }

    final VoicechatServerApi api = SvcModule.requireVoiceChatApi();
    try {
      this.createSpeakers(api);
    } catch (final RuntimeException | Error exception) {
      // Voice chat loads native Opus code, so cleanup must also handle LinkageError.
      ThrowableUtils.throwIfFatal(exception);
      this.stopSpeakers(exception);
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
      final String message = "Entity %s was refused an audio channel by Simple Voice Chat!".formatted(platformEntity);
      throw new SvcException(message);
    }

    channel.setDistance(this.distance);

    final OpusEncoder encoder = api.createEncoder();
    final Speaker speaker = new Speaker(encoder);
    try {
      final AudioPlayer player = api.createAudioPlayer(channel, encoder, speaker::nextFrame);
      speaker.setPlayer(player);
      player.startPlaying();
    } catch (final RuntimeException | Error exception) {
      // Third-party player creation/start can throw LinkageError before the speaker is registered.
      ThrowableUtils.throwIfFatal(exception);
      speaker.stop(exception);
      throw exception;
    }

    return speaker;
  }

  /**
   * Stops every speaker, closes their encoders and drops all queued and partially collected audio. The filter can be
   * started again afterwards, and releasing a stopped filter does nothing. Cleanup continues after recoverable
   * failures and rethrows the first with later failures suppressed. Fatal VM failures propagate immediately.
   *
   * @throws RuntimeException if a voice chat player or encoder fails during cleanup
   * @throws Error            if a voice chat player or encoder reports an error during cleanup
   * @since 1.0.0
   */
  @Override
  public synchronized void release() {
    this.running = false;
    this.partialLength = 0;

    final @Nullable Throwable failure = this.stopSpeakers(null);

    if (failure instanceof final RuntimeException exception) {
      throw exception;
    }

    if (failure instanceof final Error error) {
      throw error;
    }
  }

  private @Nullable Throwable stopSpeakers(final @Nullable Throwable initialFailure) {
    final List<Speaker> stopped = new ArrayList<>(this.speakers);
    this.speakers.clear();

    Throwable failure = initialFailure;
    for (final Speaker speaker : stopped) {
      failure = speaker.stop(failure);
    }

    return failure;
  }

  /**
   * Queues the samples for every speaker. The samples are not changed and are always passed on to the next step;
   * while the filter is not started they are only not played.
   *
   * @param samples  little-endian interleaved 16-bit stereo samples
   * @param metadata the metadata of the original stream
   * @return always false, because the samples are only downmixed into a copy and never changed
   * @throws NullPointerException if the samples or the metadata are null
   * @since 1.0.0
   */
  @Override
  public synchronized boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

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

  private int collect(final short[] mono, final int offset) {
    final int space = FRAME_SAMPLES - this.partialLength;
    final int available = mono.length - offset;
    final int count = Math.min(space, available);
    System.arraycopy(mono, offset, this.partial, this.partialLength, count);
    this.partialLength += count;

    if (this.partialLength == FRAME_SAMPLES) {
      this.enqueuePartialFrame();
    }

    return offset + count;
  }

  private void enqueuePartialFrame() {
    final short[] frame = this.partial.clone();
    for (final Speaker speaker : this.speakers) {
      speaker.enqueue(frame);
    }

    this.partialLength = 0;
  }

  /**
   * Returns the number of 20 millisecond frames the slowest speaker still has to play.
   *
   * @return the largest complete-frame queue length, from 0 to 25, or 0 while stopped; partially collected
   *         audio is not counted
   * @since 1.0.0
   */
  @Override
  public synchronized int getQueuedFrames() {
    return this.speakers.stream().mapToInt(Speaker::getQueuedFrames).max().orElse(0);
  }

  private static final class Speaker {

    private final OpusEncoder encoder;
    private final Deque<short[]> frames;

    private @MonotonicNonNull AudioPlayer player;

    Speaker(final OpusEncoder encoder) {
      this.encoder = encoder;
      this.frames = new ArrayDeque<>(MAXIMUM_QUEUED_FRAMES);
    }

    private void enqueue(final short[] frame) {
      synchronized (this.frames) {
        if (this.frames.size() >= MAXIMUM_QUEUED_FRAMES) {
          this.frames.pollFirst();
        }

        this.frames.addLast(frame);
      }
    }

    // called by voice chat every 20 ms; returning null would end playback, so silence is returned instead
    private short[] nextFrame() {
      synchronized (this.frames) {
        final short[] frame = this.frames.pollFirst();
        if (frame == null) {
          return SILENCE;
        }

        return frame;
      }
    }

    private @Nullable Throwable stop(final @Nullable Throwable initialFailure) {
      synchronized (this.frames) {
        this.frames.clear();
      }

      final @Nullable Throwable failure = this.stopPlayer(initialFailure);
      return this.runCleanup(failure, this.encoder::close);
    }

    private @Nullable Throwable stopPlayer(final @Nullable Throwable initialFailure) {
      final AudioPlayer current = this.player;
      if (current == null) {
        return initialFailure;
      }

      return this.runCleanup(initialFailure, current::stopPlaying);
    }

    private @Nullable Throwable runCleanup(final @Nullable Throwable previous, final Runnable cleanup) {
      try {
        cleanup.run();

        return previous;
      } catch (final RuntimeException | Error exception) {
        ThrowableUtils.throwIfFatal(exception);
        return this.keepFirstFailure(previous, exception);
      }
    }

    private Throwable keepFirstFailure(final @Nullable Throwable previous, final Throwable failure) {
      if (previous == null) {
        return failure;
      }

      final Equivalence<Object> identity = Equivalence.identity();
      final boolean same = identity.equivalent(previous, failure);
      if (!same) {
        previous.addSuppressed(failure);
      }

      return previous;
    }

    private int getQueuedFrames() {
      synchronized (this.frames) {
        return this.frames.size();
      }
    }

    private void setPlayer(final AudioPlayer player) {
      this.player = player;
    }
  }
}

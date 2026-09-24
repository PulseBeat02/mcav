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
package me.brandonli.mcav.media.player.multimedia.vlc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A player that decodes media with VLC through vlcj.
 *
 * <p>VLC handles the widest range of formats and streams, including DVDs, HLS, and YouTube-style playlists, and
 * keeps audio and video in sync on its own. Frames are rendered into a memory surface and copied out on VLC's
 * decoding thread, then handed to a dedicated rendering thread that runs the video pipeline; only the latest
 * frame is kept, so a slow pipeline lowers the frame rate instead of piling up work. Audio is requested from VLC
 * as 16-bit stereo at 48 kHz, so no resampling is needed, and is run through the audio pipeline on its own thread.
 *
 * <p>VLC opens media asynchronously. Starting waits up to five seconds for VLC to open the media, so a source that
 * cannot be opened makes {@link #start(Source)} return false; errors that happen later, while the media plays, are
 * reported to the exception handler.
 *
 * <p>When separate video and audio sources are given, two VLC media players are used and kept in sync by nudging
 * the playback rate of the video, or seeking when they drift far apart. All VLC instances share one native
 * VLC engine, see {@link SharedMediaPlayerFactory}.
 *
 * <p>Requires VLC to be available, see {@link me.brandonli.mcav.capability.Capability#VLC}.
 */
public final class VLCPlayer implements VideoPlayerMultiplexer {

  private static final long OPEN_TIMEOUT_MILLIS = 5_000L;
  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private final SharedMediaPlayerFactory sharedFactory;
  private final long openTimeoutMillis;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final DimensionAttachableCallback dimensionCallback;
  private final String[] mediaOptions;
  private final Lock lock;
  private final AtomicBoolean released;

  private volatile BiConsumer<String, Throwable> exceptionHandler;
  private @Nullable VLCPlayback playback;
  private @Nullable StartTransition pending;

  /** One candidate and its previous playback; all access to claimed is guarded by the player lock. */
  private static final class StartTransition {

    private @Nullable VLCPlayback created;
    private @Nullable VLCPlayback previous;
    private boolean claimed;
  }

  private enum OpenResult {
    CANCELLED,
    FAILED,
    STARTED,
  }

  /**
   * Constructs a new VLC player. Create instances with
   * {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#vlc(String...)}.
   *
   * <p>The player is refused while the library still prepares VLC in the background, and when that preparation found
   * that VLC is not available on this system; wait for
   * {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(Capability)} with {@link Capability#VLC} first.
   *
   * @param mediaOptions VLC media options applied to every source, such as {@code :network-caching=1000}
   * @throws IllegalStateException if VLC is still being prepared, or is not available on this system; the message
   *                               says which
   */
  public VLCPlayer(final String... mediaOptions) {
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.checkUsable(Capability.VLC);
    final SharedMediaPlayerFactory sharedFactory = SharedMediaPlayerFactory.getInstance();
    this(sharedFactory, OPEN_TIMEOUT_MILLIS, mediaOptions);
  }

  /**
   * Constructs a new VLC player with its own VLC instance and open timeout.
   *
   * @param sharedFactory     provides the VLC instance
   * @param openTimeoutMillis how long starting waits at most for VLC to open the media, in milliseconds
   * @param mediaOptions      VLC media options applied to every source
   */
  @VisibleForTesting
  VLCPlayer(final SharedMediaPlayerFactory sharedFactory, final long openTimeoutMillis, final String... mediaOptions) {
    Preconditions.checkNotNull(sharedFactory, "Shared factory must not be null");
    Preconditions.checkArgument(openTimeoutMillis >= 0, "Open timeout must not be negative but was %s", openTimeoutMillis);
    Preconditions.checkNotNull(mediaOptions, "Media options must not be null");
    for (final String option : mediaOptions) {
      Preconditions.checkNotNull(option, "Media options must not contain null");
    }
    final ExceptionHandler defaultHandler = ExceptionHandler.createDefault();
    this.sharedFactory = sharedFactory;
    this.openTimeoutMillis = openTimeoutMillis;
    this.videoCallback = VideoAttachableCallback.create();
    this.audioCallback = AudioAttachableCallback.create();
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.mediaOptions = mediaOptions.clone();
    this.lock = new ReentrantLock();
    this.released = new AtomicBoolean(false);
    this.exceptionHandler = defaultHandler.getExceptionHandler();
  }

  /**
   * Starts playing a source that contains both video and audio, replacing the current playback. Waits until VLC has
   * opened the media. The current playback keeps playing if VLC cannot even be set up for the new source.
   *
   * @param combined the source
   * @return true if playback started; false if the player is released, another start is in progress, or the source
   * cannot be opened. Source failures are reported to the exception handler.
   */
  @Override
  public boolean start(final Source combined) {
    Preconditions.checkNotNull(combined, "Source must not be null");
    return this.startPlayback(combined, null);
  }

  /**
   * Starts playing video from one source and audio from another, replacing the current playback. Equal sources are
   * played as one combined source. Waits until VLC has opened both sources, which it does at the same time, so
   * starting waits at most the open timeout. The current playback keeps playing if VLC cannot even be set up.
   *
   * @param video the video source
   * @param audio the audio source
   * @return true if playback started; false if the player is released, another start is in progress, or a source
   * cannot be opened. Source failures are reported to the exception handler.
   */
  @Override
  public boolean start(final Source video, final Source audio) {
    Preconditions.checkNotNull(video, "Video source must not be null");
    Preconditions.checkNotNull(audio, "Audio source must not be null");
    final boolean sameSource = video.equals(audio);
    if (sameSource) {
      return this.startPlayback(video, null);
    }
    return this.startPlayback(video, audio);
  }

  private boolean startPlayback(final Source video, final @Nullable Source audio) {
    final StartTransition transition = new StartTransition();
    try {
      if (!this.prepareCandidate(transition, video, audio)) {
        return false;
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      VLCPlayback.attemptAfterFailure(exception, () -> this.cleanFailedPreparation(transition));
      return this.reportFailedStart(video, exception);
    }
    try {
      final VLCPlayback previous = transition.previous;
      if (previous != null) {
        previous.stop();
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      if (this.claimCandidate(transition)) {
        VLCPlayback.attemptAfterFailure(exception, () -> this.cleanCandidate(transition));
      }
      throw exception;
    }
    return this.startReservedPlayback(transition, video);
  }

  /**
   * Reserves ownership before foreign acquisition code can reenter the player. Reservation and acquisition share
   * one lock scope; no empty, unclaimed reservation is ever visible to release. The replacement acquires the shared
   * VLC engine before the previous playback is detached.
   */
  private boolean prepareCandidate(final StartTransition transition, final Source video, final @Nullable Source audio) {
    this.lock.lock();
    try {
      if (this.released.get() || this.pending != null) {
        return false;
      }
      transition.previous = this.playback;
      transition.claimed = true;
      this.pending = transition;
      transition.created = VLCPlayback.create(this, video, audio);
      this.playback = null;
      transition.claimed = false;
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /** A failed acquisition keeps the old playback unless a reentrant release cancelled it. */
  private void cleanFailedPreparation(final StartTransition transition) {
    this.lock.lock();
    try {
      if (!this.released.get()) {
        this.pending = null;
        return;
      }
    } finally {
      this.lock.unlock();
    }
    try {
      final VLCPlayback previous = transition.previous;
      if (previous != null) {
        previous.stop();
      }
    } finally {
      this.clearReservation();
    }
  }

  /** Claims a candidate for failed-transition cleanup only if release has not taken its ownership. */
  private boolean claimCandidate(final StartTransition transition) {
    this.lock.lock();
    try {
      if (!IDENTITY.equivalent(this.pending, transition)) {
        return false;
      }
      transition.claimed = true;
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private boolean startReservedPlayback(final StartTransition transition, final Source video) {
    final OpenResult result;
    try {
      result = this.openCandidate(transition);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      VLCPlayback.attemptAfterFailure(exception, () -> this.cleanCandidate(transition));
      return this.reportFailedStart(video, exception);
    }
    if (result == OpenResult.FAILED) {
      this.cleanCandidate(transition);
    }
    return result == OpenResult.STARTED;
  }

  /** Serializes native open with release; no renderer joins or failure cleanup run under this lock. */
  private OpenResult openCandidate(final StartTransition transition) {
    this.lock.lock();
    try {
      if (!IDENTITY.equivalent(this.pending, transition)) {
        return OpenResult.CANCELLED;
      }
      transition.claimed = true;
      if (this.released.get()) {
        return OpenResult.FAILED;
      }
      final VLCPlayback created = Objects.requireNonNull(transition.created, "A prepared transition owns a candidate");
      final boolean started = created.start();
      if (started && !this.released.get()) {
        this.playback = created;
        this.pending = null;
        return OpenResult.STARTED;
      }
      return OpenResult.FAILED;
    } finally {
      this.lock.unlock();
    }
  }

  /** Retains the reservation during cleanup so another start cannot overtake unfinished renderer shutdown. */
  private void cleanCandidate(final StartTransition transition) {
    try {
      final VLCPlayback created = Objects.requireNonNull(transition.created, "A prepared transition owns a candidate");
      created.stop();
    } finally {
      this.clearReservation();
    }
  }

  /** Only the owner of a claimed candidate calls this; release and competing starts leave that reservation intact. */
  private void clearReservation() {
    this.lock.lock();
    try {
      this.pending = null;
    } finally {
      this.lock.unlock();
    }
  }

  private boolean reportFailedStart(final Source video, final Throwable exception) {
    ThrowableUtils.throwIfFatal(exception);
    final boolean reported = this.reportStartFailure(video, exception);
    if (!reported) {
      if (exception instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw (Error) exception;
    }
    return false;
  }

  private boolean reportStartFailure(final Source video, final Throwable exception) {
    return VLCPlayback.attemptAfterFailure(exception, () -> {
      final String resource = video.getResource();
      this.exceptionHandler.accept("Failed to start VLC playback of " + resource, exception);
    });
  }

  /**
   * Pauses playback, keeping the current position.
   *
   * @return true if playback was paused, false if nothing is playing or it was already paused
   */
  @Override
  public boolean pause() {
    this.lock.lock();
    try {
      final VLCPlayback current = this.playback;
      if (current == null) {
        return false;
      }
      return current.pause();
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Resumes playback after {@link #pause()}.
   *
   * @return true if playback was resumed, false if nothing is playing or it was not paused
   */
  @Override
  public boolean resume() {
    this.lock.lock();
    try {
      final VLCPlayback current = this.playback;
      if (current == null) {
        return false;
      }
      return current.resume();
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Jumps to a position. A paused player stays paused.
   *
   * @param time the position in milliseconds from the start of the media, not negative
   * @return true if the player seeked, false if nothing is playing or the source cannot be seeked
   */
  @Override
  public boolean seek(final long time) {
    Preconditions.checkArgument(time >= 0, "Seek time must not be negative");
    this.lock.lock();
    try {
      final VLCPlayback current = this.playback;
      if (current == null) {
        return false;
      }
      return current.seek(time);
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Stops playback and releases the VLC resources of the player. Render threads are interrupted and each is awaited
   * for up to five seconds without holding the player lock. A pipeline may release its own player without waiting
   * for itself; a callback that ignores interruption may outlive the wait. A pending replacement is cancelled.
   * If a start already owns native opening or failure cleanup, it finishes that cleanup when it unwinds; it cannot
   * publish playback after release. A released player cannot be started again.
   *
   * @return true if the player was released, false if it had already been released
   */
  @Override
  public boolean release() {
    final VLCPlayback current;
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }
      final StartTransition transition = this.pending;
      if (transition == null) {
        current = this.playback;
      } else if (transition.claimed) {
        current = null;
      } else {
        current = Objects.requireNonNull(transition.created, "An unclaimed transition owns a prepared candidate");
        this.pending = null;
      }
      this.playback = null;
    } finally {
      this.lock.unlock();
    }
    if (current != null) {
      current.stop();
    }
    return true;
  }

  /**
   * Gets the slot of the video pipeline. The render thread looks the pipeline up for every frame, so a pipeline
   * attached while the player plays takes effect with the next frame.
   *
   * @return the video slot, the same instance for the whole life of the player
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  /**
   * Gets the slot of the audio pipeline. The render thread looks the pipeline up for every chunk of samples, so a
   * pipeline attached while the player plays takes effect with the next chunk.
   *
   * @return the audio slot, the same instance for the whole life of the player
   */
  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  /**
   * Gets the slot of the size frames are scaled to. A size attached before VLC chooses its buffer, which it does once
   * it knows the format of the video, makes VLC render at that size; a size attached or changed later is applied by
   * resizing each frame on the render thread.
   *
   * @return the size slot, the same instance for the whole life of the player
   */
  @Override
  public DimensionAttachableCallback getDimensionAttachableCallback() {
    return this.dimensionCallback;
  }

  /**
   * Gets the handler that receives failures, such as media that cannot be opened or a filter that throws.
   *
   * @return the exception handler, which receives a description of the failure and its cause
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler;
  }

  /**
   * Sets the handler that receives failures. It is called on VLC's threads and on the render threads, so it must be
   * thread safe and should return quickly.
   *
   * @param exceptionHandler the exception handler, which receives a description of the failure and its cause
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.exceptionHandler = exceptionHandler;
  }

  /**
   * Gets the VLC instance the playbacks of this player use.
   *
   * @return the shared factory
   */
  SharedMediaPlayerFactory getSharedFactory() {
    return this.sharedFactory;
  }

  /**
   * Gets how long starting waits at most for VLC to open the media.
   *
   * @return the timeout in milliseconds
   */
  long getOpenTimeoutMillis() {
    return this.openTimeoutMillis;
  }

  /**
   * Creates the media options for one VLC media player: the options of this player, followed by an extra option.
   *
   * @param extra the extra option, or {@code null} for none
   * @return a new array of options
   */
  String[] createMediaOptions(final @Nullable String extra) {
    if (extra == null) {
      return this.mediaOptions.clone();
    }
    final List<String> configured = List.of(this.mediaOptions);
    final List<String> options = new ArrayList<>(configured);
    options.add(extra);
    return options.toArray(new String[0]);
  }
}

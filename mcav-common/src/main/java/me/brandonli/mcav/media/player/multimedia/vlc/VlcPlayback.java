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

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerApi;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.AudioApi;
import uk.co.caprica.vlcj.player.base.ControlsApi;
import uk.co.caprica.vlcj.player.base.EventApi;
import uk.co.caprica.vlcj.player.base.MediaApi;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.base.StatusApi;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;

// One playback of a VlcPlayer: the VLC media players, their callbacks, and the render threads. A combined source is
// played by one VLC media player. When separate video and audio sources are given, a second player plays the audio, and
// the video player is kept in sync with it. Audio is the master clock, because listeners notice audio glitches far more
// than dropped frames: small drifts are corrected by playing the video slightly faster or slower, and large ones by
// seeking the video to the audio position. All methods except the synchronizer are called with the lock of the owning
// player held. A playback cannot be restarted once it is stopped.
final class VlcPlayback {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private static final String NO_AUDIO_OPTION = ":no-audio";
  private static final String NO_VIDEO_OPTION = ":no-video";

  private static final String AUDIO_FORMAT = "S16N";

  private static final boolean LOCK_BUFFERS = true;

  private static final long DRIFT_NUDGE_MILLISECONDS = 100L;
  private static final long DRIFT_SEEK_MILLISECONDS = 2_000L;

  private static final long SYNCHRONIZATION_INTERVAL_MILLISECONDS = 250L;
  private static final String SYNCHRONIZATION_THREAD_NAME = "mcav-vlc-sync";

  private static final float RATE_NORMAL = 1.0f;
  private static final float RATE_SLOWER = 0.97f;
  private static final float RATE_FASTER = 1.03f;

  private static final Set<State> SEEKABLE_STATES = EnumSet.of(State.OPENING, State.BUFFERING, State.PLAYING, State.PAUSED);

  private final VlcPlayer owner;
  private final SharedMediaPlayerFactory sharedFactory;
  private final MediaPlayerFactory factory;
  private final Source video;
  private final @Nullable Source audio;
  private final EmbeddedMediaPlayer videoPlayer;
  private final @Nullable EmbeddedMediaPlayer audioPlayer;
  private final List<EmbeddedMediaPlayer> players;
  private final VideoRenderer videoRenderer;
  private final AudioRenderer audioRenderer;
  private final AtomicBoolean running;

  private boolean paused;
  private @Nullable Synchronizer synchronizer;

  private VlcPlayback(final VlcPlayer owner, final MediaPlayerFactory factory, final Source video, final @Nullable Source audio) {
    this.owner = owner;
    this.sharedFactory = owner.getSharedFactory();
    this.factory = factory;
    this.video = video;
    this.audio = audio;
    this.videoRenderer = new VideoRenderer(owner);
    this.audioRenderer = new AudioRenderer(owner);
    this.running = new AtomicBoolean(true);
    final MediaPlayerApi playerApi = factory.mediaPlayers();
    final EmbeddedMediaPlayer createdVideoPlayer = playerApi.newEmbeddedMediaPlayer();
    this.videoPlayer = createdVideoPlayer;
    this.audioPlayer = this.createAudioPlayer(playerApi, createdVideoPlayer, audio);
    this.players = this.listPlayers(createdVideoPlayer, this.audioPlayer);
  }

  // Creates a playback: acquires the shared VLC instance and creates the media players. Nothing is played until start()
  // is called, but stop() must be called in any case to release the players.
  static VlcPlayback create(final VlcPlayer owner, final Source video, final @Nullable Source audio) {
    final SharedMediaPlayerFactory sharedFactory = owner.getSharedFactory();
    final MediaPlayerFactory factory = sharedFactory.acquire();
    try {
      return new VlcPlayback(owner, factory, video, audio);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      runAddingFailureTo(exception, sharedFactory::release);
      throw exception;
    }
  }

  private @Nullable EmbeddedMediaPlayer createAudioPlayer(
    @UnderInitialization VlcPlayback this,
    final MediaPlayerApi playerApi,
    final EmbeddedMediaPlayer videoPlayer,
    final @Nullable Source audio
  ) {
    if (audio == null) {
      return null;
    }

    try {
      return playerApi.newEmbeddedMediaPlayer();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      runAddingFailureTo(exception, videoPlayer::release);
      throw exception;
    }
  }

  // Runs cleanup or reporting after the primary failure and adds the action's own failure, unless it is the same one,
  // to the primary failure as suppressed, so neither is lost. Fatal VM errors still propagate.
  static boolean runAddingFailureTo(final Throwable primary, final Runnable action) {
    try {
      action.run();

      return true;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final boolean sameFailure = FAILURE_IDENTITY.equivalent(primary, exception);
      if (!sameFailure) {
        primary.addSuppressed(exception);
      }

      return false;
    }
  }

  private List<EmbeddedMediaPlayer> listPlayers(
    @UnderInitialization VlcPlayback this,
    final EmbeddedMediaPlayer videoPlayer,
    final @Nullable EmbeddedMediaPlayer audioPlayer
  ) {
    if (audioPlayer == null) {
      return List.of(videoPlayer);
    }

    return List.of(videoPlayer, audioPlayer);
  }

  // Installs the callbacks, starts the render threads, and plays the media, waiting until VLC has opened it or the open
  // timeout of the player elapses. Separate video and audio sources are opened at the same time and share the timeout.
  boolean start() {
    this.installVideoSurface();
    this.installAudioCallback();
    this.videoRenderer.start();
    this.audioRenderer.start();
    final EmbeddedMediaPlayer separateAudio = this.audioPlayer;
    if (separateAudio == null) {
      return this.startCombined();
    }

    return this.startSeparate(separateAudio);
  }

  private boolean startCombined() {
    final long deadlineNanoseconds = this.createOpenDeadline();
    final PlaybackEventListener listener = this.listen(this.videoPlayer, this.video);
    final boolean playing = this.play(this.videoPlayer, this.video, null);
    return playing && this.awaitOpened(listener, deadlineNanoseconds);
  }

  private boolean startSeparate(final EmbeddedMediaPlayer separateAudio) {
    final Source audioSource = requireNonNull(this.audio, "A separate audio player always has an audio source!");
    final long deadlineNanoseconds = this.createOpenDeadline();
    final PlaybackEventListener videoListener = this.listen(this.videoPlayer, this.video);
    final PlaybackEventListener audioListener = this.listen(separateAudio, audioSource);
    final boolean videoPlaying = this.play(this.videoPlayer, this.video, NO_AUDIO_OPTION);
    final boolean audioPlaying = this.play(separateAudio, audioSource, NO_VIDEO_OPTION);
    final boolean bothPlaying = videoPlaying && audioPlaying;
    final boolean opened =
      bothPlaying && this.awaitOpened(videoListener, deadlineNanoseconds) && this.awaitOpened(audioListener, deadlineNanoseconds);
    if (opened) {
      this.startSynchronizer(separateAudio);
    }

    return opened;
  }

  private void installVideoSurface() {
    final FrameFormatCallback formatCallback = new FrameFormatCallback(this.videoRenderer, this.videoPlayer);
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi surfaces = this.factory.videoSurfaces(); // fqn: VideoSurfaceApi is imported as uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi
    final CallbackVideoSurface surface = surfaces.newVideoSurface(formatCallback, this.videoRenderer, LOCK_BUFFERS);
    final VideoSurfaceApi surfaceApi = this.videoPlayer.videoSurface();
    surfaceApi.set(surface);
  }

  private void installAudioCallback() {
    final EmbeddedMediaPlayer target = Objects.requireNonNullElse(this.audioPlayer, this.videoPlayer);
    final AudioApi audioApi = target.audio();
    audioApi.callback(AUDIO_FORMAT, AudioFilter.SAMPLE_RATE, AudioFilter.CHANNELS, this.audioRenderer);
  }

  private PlaybackEventListener listen(final EmbeddedMediaPlayer player, final Source source) {
    final String resource = source.getResource();
    final PlaybackEventListener listener = new PlaybackEventListener(this.owner, resource, this.running);
    final EventApi events = player.events();
    events.addMediaPlayerEventListener(listener);
    return listener;
  }

  private boolean play(final EmbeddedMediaPlayer player, final Source source, final @Nullable String extraOption) {
    final String resource = source.getResource();
    final String[] options = this.owner.createMediaOptions(extraOption);
    final MediaApi media = player.media();
    final boolean playing = media.play(resource, options);
    if (!playing) {
      final BiConsumer<String, Throwable> handler = this.owner.getExceptionHandler();
      final String message = "Media %s could not be opened by VLC!".formatted(resource);
      final PlayerException exception = new PlayerException(message);
      final String handlerMessage = "VLC playback of %s could not be started.".formatted(resource);
      handler.accept(handlerMessage, exception);
    }

    return playing;
  }

  private long createOpenDeadline() {
    final long timeoutMilliseconds = this.owner.getOpenTimeoutMilliseconds();
    final long timeoutNanoseconds = TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
    final long now = System.nanoTime();
    return now + timeoutNanoseconds;
  }

  private boolean awaitOpened(final PlaybackEventListener listener, final long deadlineNanoseconds) {
    final long now = System.nanoTime();
    final long remainingNanoseconds = deadlineNanoseconds - now;
    final long remainingMilliseconds = TimeUnit.NANOSECONDS.toMillis(remainingNanoseconds);
    try {
      return listener.awaitOpened(remainingMilliseconds);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      return false;
    }
  }

  private void startSynchronizer(final EmbeddedMediaPlayer audioMaster) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(this::createSynchronizationThread);
    final Runnable task = () -> this.synchronizeVideoToAudio(audioMaster);
    final ScheduledFuture<?> schedule = executor.scheduleAtFixedRate(
      task,
      SYNCHRONIZATION_INTERVAL_MILLISECONDS,
      SYNCHRONIZATION_INTERVAL_MILLISECONDS,
      TimeUnit.MILLISECONDS
    );
    this.synchronizer = new Synchronizer(executor, schedule);
  }

  private Thread createSynchronizationThread(final Runnable task) {
    final Thread thread = new Thread(task, SYNCHRONIZATION_THREAD_NAME);
    thread.setDaemon(true);
    return thread;
  }

  // Moves the video player back in line with the audio player, if both are playing. Failures are reported to the
  // exception handler of the player instead of ending the synchronization.
  @VisibleForTesting
  void synchronizeVideoToAudio(final EmbeddedMediaPlayer audioMaster) {
    if (!this.running.get()) {
      return;
    }

    try {
      final StatusApi audioStatus = audioMaster.status();
      final StatusApi videoStatus = this.videoPlayer.status();
      if (!audioStatus.isPlaying() || !videoStatus.isPlaying()) {
        return;
      }

      final long audioTime = audioStatus.time();
      final long videoTime = videoStatus.time();
      final long drift = videoTime - audioTime;
      final ControlsApi videoControls = this.videoPlayer.controls();
      this.correctDrift(videoControls, drift, audioTime);
    } catch (final RuntimeException exception) {
      final BiConsumer<String, Throwable> handler = this.owner.getExceptionHandler();
      handler.accept("Failed to synchronize VLC players.", exception);
    }
  }

  @VisibleForTesting
  void correctDrift(final ControlsApi videoControls, final long drift, final long audioTime) {
    final long absoluteDrift = Math.abs(drift);
    if (absoluteDrift > DRIFT_SEEK_MILLISECONDS) {
      videoControls.setTime(audioTime);
      videoControls.setRate(RATE_NORMAL);
      return;
    }

    final float rate = this.chooseRate(drift);
    videoControls.setRate(rate);
  }

  // Chooses the playback rate of the video for a small drift: 3% slower when the video is more than 100 ms ahead, 3%
  // faster when it is more than 100 ms behind, and normal speed otherwise.
  @VisibleForTesting
  float chooseRate(final long drift) {
    if (drift > DRIFT_NUDGE_MILLISECONDS) {
      return RATE_SLOWER;
    }

    if (drift < -DRIFT_NUDGE_MILLISECONDS) {
      return RATE_FASTER;
    }

    return RATE_NORMAL;
  }

  boolean pause() {
    final StatusApi status = this.videoPlayer.status();
    if (this.paused || !status.isPlaying()) {
      return false;
    }

    this.paused = true;
    // VLC keeps repainting the last picture while paused, so the renderer drops those repaints itself
    this.videoRenderer.setPaused(true);
    this.setPause(true);

    return true;
  }

  // Resumes playback after pause(). The paused state is tracked here rather than asked from VLC, because VLC changes
  // its state asynchronously and still reports playing right after it was told to pause.
  boolean resume() {
    if (!this.paused) {
      return false;
    }

    this.paused = false;
    this.setPause(false);
    this.videoRenderer.setPaused(false);

    return true;
  }

  private void setPause(final boolean pause) {
    for (final EmbeddedMediaPlayer player : this.players) {
      final ControlsApi controls = player.controls();
      controls.setPause(pause);
    }
  }

  boolean seek(final long timeMilliseconds) {
    for (final EmbeddedMediaPlayer player : this.players) {
      final boolean seekable = this.isSeekable(player);
      if (!seekable) {
        return false;
      }
    }

    for (final EmbeddedMediaPlayer player : this.players) {
      final ControlsApi controls = player.controls();
      controls.setTime(timeMilliseconds);
    }

    return true;
  }

  private boolean isSeekable(final EmbeddedMediaPlayer player) {
    final StatusApi status = player.status();
    final State state = status.state();
    return SEEKABLE_STATES.contains(state) && status.isSeekable();
  }

  // Stops playback, waits for the render threads to exit, releases the media players, and releases the shared VLC
  // instance. Calling this method again has no effect.
  void stop() {
    final boolean wasRunning = this.running.getAndSet(false);
    if (!wasRunning) {
      return;
    }

    final StopFailures failures = new StopFailures();
    final Synchronizer currentSynchronizer = this.synchronizer;
    if (currentSynchronizer != null) {
      failures.run(currentSynchronizer::stop);
    }

    for (final EmbeddedMediaPlayer player : this.players) {
      failures.run(() -> this.stopControls(player));
    }

    failures.run(this.videoRenderer::stop);
    failures.run(this.audioRenderer::stop);
    for (final EmbeddedMediaPlayer player : this.players) {
      failures.run(player::release);
    }

    failures.run(this.sharedFactory::release);
    failures.rethrow();
  }

  private void stopControls(final EmbeddedMediaPlayer player) {
    final ControlsApi controls = player.controls();
    controls.stop();
  }

  private static final class StopFailures {

    private @Nullable Throwable first;

    void run(final Runnable action) {
      try {
        action.run();
      } catch (final RuntimeException | Error exception) {
        ThrowableUtils.throwIfFatal(exception);
        final Throwable previous = this.first;
        if (previous == null) {
          this.first = exception;
        } else if (!FAILURE_IDENTITY.equivalent(previous, exception)) {
          previous.addSuppressed(exception);
        }
      }
    }

    private void rethrow() {
      final Throwable failure = this.first;
      if (failure instanceof final RuntimeException runtimeException) {
        throw runtimeException;
      }

      if (failure instanceof final Error error) {
        throw error;
      }
    }
  }

  // The thread that keeps the video player in line with a separate audio player, together with the schedule of the task
  // it repeats, so stopping cancels the schedule before the thread is shut down.
  private static final class Synchronizer {

    private static final boolean DO_NOT_INTERRUPT = false;

    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> schedule;

    Synchronizer(final ScheduledExecutorService executor, final ScheduledFuture<?> schedule) {
      this.executor = executor;
      this.schedule = schedule;
    }

    void stop() {
      this.schedule.cancel(DO_NOT_INTERRUPT);
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
    }
  }
}

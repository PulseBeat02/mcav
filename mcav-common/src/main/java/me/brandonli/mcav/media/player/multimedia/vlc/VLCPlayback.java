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

/**
 * One playback of a {@link VLCPlayer}: the VLC media players, their callbacks, and the render threads.
 *
 * <p>A combined source is played by one VLC media player. When separate video and audio sources are given, a second
 * player plays the audio, and the video player is kept in sync with it. Audio is the master clock, because listeners
 * notice audio glitches far more than dropped frames: small drifts are corrected by playing the video slightly
 * faster or slower, and large ones by seeking the video to the audio position.
 *
 * <p>All methods except the synchronizer are called with the lock of the owning player held. A playback cannot be
 * restarted once it is stopped.
 */
final class VLCPlayback {

  private static final String NO_AUDIO_OPTION = ":no-audio";
  private static final String NO_VIDEO_OPTION = ":no-video";
  private static final String AUDIO_FORMAT = "S16N";
  private static final long DRIFT_NUDGE_MILLIS = 100L;
  private static final long DRIFT_SEEK_MILLIS = 2_000L;
  private static final long SYNC_INTERVAL_MILLIS = 250L;
  private static final float RATE_NORMAL = 1.0f;
  private static final float RATE_SLOWER = 0.97f;
  private static final float RATE_FASTER = 1.03f;
  private static final Set<State> SEEKABLE_STATES = EnumSet.of(State.OPENING, State.BUFFERING, State.PLAYING, State.PAUSED);

  private final VLCPlayer owner;
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

  private VLCPlayback(final VLCPlayer owner, final MediaPlayerFactory factory, final Source video, final @Nullable Source audio) {
    this.owner = owner;
    this.sharedFactory = owner.getSharedFactory();
    this.factory = factory;
    this.video = video;
    this.audio = audio;
    final MediaPlayerApi playerApi = factory.mediaPlayers();
    final EmbeddedMediaPlayer createdVideoPlayer = playerApi.newEmbeddedMediaPlayer();
    this.videoPlayer = createdVideoPlayer;
    this.audioPlayer = createAudioPlayer(playerApi, createdVideoPlayer, audio);
    this.players = listPlayers(createdVideoPlayer, this.audioPlayer);
    this.videoRenderer = new VideoRenderer(owner);
    this.audioRenderer = new AudioRenderer(owner);
    this.running = new AtomicBoolean(true);
  }

  /**
   * Creates a playback: acquires the shared VLC instance and creates the media players. Nothing is played until
   * {@link #start()} is called, but {@link #stop()} must be called in any case to release the players.
   *
   * @param owner the player that provides the pipelines, the options, and the exception handler
   * @param video the source of the video, which also provides the audio when there is no separate audio source
   * @param audio the separate audio source, or {@code null} to take the audio from the video source
   * @return the playback
   * @throws RuntimeException if VLC cannot create the media players; the shared VLC instance is released again
   */
  static VLCPlayback create(final VLCPlayer owner, final Source video, final @Nullable Source audio) {
    final SharedMediaPlayerFactory sharedFactory = owner.getSharedFactory();
    final MediaPlayerFactory factory = sharedFactory.acquire();
    try {
      return new VLCPlayback(owner, factory, video, audio);
    } catch (final RuntimeException exception) {
      sharedFactory.release();
      throw exception;
    }
  }

  /**
   * Creates the player of a separate audio source, releasing the already created video player if that fails.
   *
   * @return the audio player, or {@code null} if there is no separate audio source
   */
  private static @Nullable EmbeddedMediaPlayer createAudioPlayer(
    final MediaPlayerApi playerApi,
    final EmbeddedMediaPlayer videoPlayer,
    final @Nullable Source audio
  ) {
    if (audio == null) {
      return null;
    }
    try {
      return playerApi.newEmbeddedMediaPlayer();
    } catch (final RuntimeException exception) {
      videoPlayer.release();
      throw exception;
    }
  }

  private static List<EmbeddedMediaPlayer> listPlayers(
    final EmbeddedMediaPlayer videoPlayer,
    final @Nullable EmbeddedMediaPlayer audioPlayer
  ) {
    if (audioPlayer == null) {
      return List.of(videoPlayer);
    }
    return List.of(videoPlayer, audioPlayer);
  }

  /**
   * Installs the callbacks, starts the render threads, and plays the media, waiting until VLC has opened it or the
   * open timeout of the player elapses. Separate video and audio sources are opened at the same time and share the
   * timeout.
   *
   * @return true if VLC plays the media or is still opening it after the timeout, false if VLC could not open it or
   * the calling thread was interrupted; failures are reported to the exception handler of the player
   */
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
    final long deadlineNanos = this.createOpenDeadline();
    final PlaybackEventListener listener = this.listen(this.videoPlayer, this.video);
    final boolean playing = this.play(this.videoPlayer, this.video, null);
    return playing && awaitOpened(listener, deadlineNanos);
  }

  private boolean startSeparate(final EmbeddedMediaPlayer separateAudio) {
    final Source audioSource = Objects.requireNonNull(this.audio, "A separate audio player always has an audio source");
    // VLC opens both sources at the same time, so they share one deadline instead of waiting one after the other
    final long deadlineNanos = this.createOpenDeadline();
    final PlaybackEventListener videoListener = this.listen(this.videoPlayer, this.video);
    final PlaybackEventListener audioListener = this.listen(separateAudio, audioSource);
    final boolean videoPlaying = this.play(this.videoPlayer, this.video, NO_AUDIO_OPTION);
    final boolean audioPlaying = this.play(separateAudio, audioSource, NO_VIDEO_OPTION);
    final boolean bothPlaying = videoPlaying && audioPlaying;
    final boolean opened = bothPlaying && awaitOpened(videoListener, deadlineNanos) && awaitOpened(audioListener, deadlineNanos);
    if (opened) {
      this.startSynchronizer(separateAudio);
    }
    return opened;
  }

  private void installVideoSurface() {
    final FrameFormatCallback formatCallback = new FrameFormatCallback(this.videoRenderer, this.videoPlayer);
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi surfaces = this.factory.videoSurfaces();
    final CallbackVideoSurface surface = surfaces.newVideoSurface(formatCallback, this.videoRenderer, true);
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
      final PlayerException exception = new PlayerException("VLC could not open " + resource);
      handler.accept("Failed to start VLC playback of " + resource, exception);
    }
    return playing;
  }

  private long createOpenDeadline() {
    final long timeoutMillis = this.owner.getOpenTimeoutMillis();
    final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    final long now = System.nanoTime();
    return now + timeoutNanos;
  }

  /**
   * Waits until VLC has opened the media of a listener or the deadline has passed.
   *
   * @param listener      the listener of the player that opens the media
   * @param deadlineNanos the {@link System#nanoTime()} at which waiting ends
   * @return false if VLC reported an error or the calling thread was interrupted, true otherwise
   */
  private static boolean awaitOpened(final PlaybackEventListener listener, final long deadlineNanos) {
    final long now = System.nanoTime();
    final long remainingNanos = deadlineNanos - now;
    final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
    try {
      return listener.awaitOpened(remainingMillis);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      return false;
    }
  }

  private void startSynchronizer(final EmbeddedMediaPlayer audioMaster) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(VLCPlayback::createSyncThread);
    final Runnable task = () -> this.synchronizeVideoToAudio(audioMaster);
    final ScheduledFuture<?> schedule = executor.scheduleAtFixedRate(
      task,
      SYNC_INTERVAL_MILLIS,
      SYNC_INTERVAL_MILLIS,
      TimeUnit.MILLISECONDS
    );
    this.synchronizer = new Synchronizer(executor, schedule);
  }

  private static Thread createSyncThread(final Runnable task) {
    final Thread thread = new Thread(task, "mcav-vlc-sync");
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Moves the video player back in line with the audio player, if both are playing. Failures are reported to the
   * exception handler of the player instead of ending the synchronization.
   *
   * @param audioMaster the player of the separate audio source
   */
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
      correctDrift(videoControls, drift, audioTime);
    } catch (final RuntimeException exception) {
      final BiConsumer<String, Throwable> handler = this.owner.getExceptionHandler();
      handler.accept("Failed to synchronize VLC players", exception);
    }
  }

  /**
   * Corrects the drift of the video: drifts of more than two seconds are corrected by seeking the video to the audio
   * position at normal speed, smaller ones by choosing a playback rate with {@link #chooseRate(long)}.
   *
   * @param videoControls the controls of the video player
   * @param drift         the video position minus the audio position in milliseconds
   * @param audioTime     the audio position in milliseconds
   */
  @VisibleForTesting
  static void correctDrift(final ControlsApi videoControls, final long drift, final long audioTime) {
    final long absoluteDrift = Math.abs(drift);
    if (absoluteDrift > DRIFT_SEEK_MILLIS) {
      videoControls.setTime(audioTime);
      videoControls.setRate(RATE_NORMAL);
      return;
    }
    final float rate = chooseRate(drift);
    videoControls.setRate(rate);
  }

  /**
   * Chooses the playback rate of the video for a small drift: 3% slower when the video is more than 100 ms ahead,
   * 3% faster when it is more than 100 ms behind, and normal speed otherwise.
   *
   * @param drift the video position minus the audio position in milliseconds
   * @return the playback rate
   */
  @VisibleForTesting
  static float chooseRate(final long drift) {
    if (drift > DRIFT_NUDGE_MILLIS) {
      return RATE_SLOWER;
    }
    if (drift < -DRIFT_NUDGE_MILLIS) {
      return RATE_FASTER;
    }
    return RATE_NORMAL;
  }

  /**
   * Pauses playback, keeping the position.
   *
   * @return true if playback was paused, false if VLC is not playing or playback is already paused
   */
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

  /**
   * Resumes playback after {@link #pause()}. The paused state is tracked here rather than asked from VLC, because VLC
   * changes its state asynchronously and still reports playing right after it was told to pause.
   *
   * @return true if playback was resumed, false if it was not paused
   */
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

  /**
   * Jumps to a position in every player.
   *
   * @param timeMillis the position in milliseconds
   * @return true if the players seeked, false if one of them has no open media or its media cannot be seeked, such
   * as a live stream or media that has ended
   */
  boolean seek(final long timeMillis) {
    for (final EmbeddedMediaPlayer player : this.players) {
      final boolean seekable = isSeekable(player);
      if (!seekable) {
        return false;
      }
    }
    for (final EmbeddedMediaPlayer player : this.players) {
      final ControlsApi controls = player.controls();
      controls.setTime(timeMillis);
    }
    return true;
  }

  private static boolean isSeekable(final EmbeddedMediaPlayer player) {
    final StatusApi status = player.status();
    final State state = status.state();
    return SEEKABLE_STATES.contains(state) && status.isSeekable();
  }

  /**
   * Stops playback, waits for the render threads to exit, releases the media players, and releases the shared VLC
   * instance. Calling this method again has no effect.
   */
  void stop() {
    final boolean wasRunning = this.running.getAndSet(false);
    if (!wasRunning) {
      return;
    }
    final Synchronizer currentSynchronizer = this.synchronizer;
    if (currentSynchronizer != null) {
      currentSynchronizer.stop();
    }
    for (final EmbeddedMediaPlayer player : this.players) {
      final ControlsApi controls = player.controls();
      controls.stop();
    }
    this.videoRenderer.stop();
    this.audioRenderer.stop();
    for (final EmbeddedMediaPlayer player : this.players) {
      player.release();
    }
    this.sharedFactory.release();
  }

  /**
   * The thread that keeps the video player in line with a separate audio player, together with the schedule of the
   * task it repeats, so stopping cancels the schedule before the thread is shut down.
   */
  private static final class Synchronizer {

    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> schedule;

    Synchronizer(final ScheduledExecutorService executor, final ScheduledFuture<?> schedule) {
      this.executor = executor;
      this.schedule = schedule;
    }

    /**
     * Cancels the repeated task, letting a running synchronization finish, and shuts the thread down.
     */
    void stop() {
      this.schedule.cancel(false);
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
    }
  }
}

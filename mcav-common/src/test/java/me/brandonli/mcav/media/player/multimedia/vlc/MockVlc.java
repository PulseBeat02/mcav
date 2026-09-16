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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.invocation.InvocationOnMock;
import uk.co.caprica.vlcj.factory.MediaPlayerApi;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.AudioApi;
import uk.co.caprica.vlcj.player.base.ControlsApi;
import uk.co.caprica.vlcj.player.base.EventApi;
import uk.co.caprica.vlcj.player.base.MediaApi;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.base.StatusApi;
import uk.co.caprica.vlcj.player.base.VideoApi;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;

/**
 * A VLC engine made of Mockito mocks, so {@link VLCPlayer} and {@link VLCPlayback} can be tested without libvlc.
 *
 * <p>Every media player it creates behaves like VLC: asking it to play media records the resource and the options
 * and, depending on the {@link Outcome} of the player, reports playing or an error to the registered event
 * listeners on the calling thread, or reports nothing yet. Like VLC, pausing does not change the playing state
 * right away.
 */
final class MockVlc {

  private static final int SECOND_PLAYER_INDEX = 1;

  /**
   * What a player does when it is asked to play media.
   */
  enum Outcome {
    /** Reports that the media plays. */
    PLAYING,
    /** Reports an error, like VLC does for a missing file. */
    ERROR,
    /** Reports nothing, like VLC while it is still opening a stream. */
    SILENT,
    /** Refuses the media, like vlcj does for a malformed MRL. */
    REJECTED,
    /** Throws an exception. */
    THROWS,
  }

  private final MediaPlayerFactory factory;
  private final List<Player> players;
  private final Deque<Outcome> outcomes;
  private final AtomicInteger creations;
  private final SharedMediaPlayerFactory sharedFactory;
  private final AtomicReference<BufferFormatCallback> formatCallback;
  private final AtomicReference<RenderCallback> renderCallback;

  private volatile Throwable factoryFailure;
  private volatile int failingPlayerIndex;
  private volatile RuntimeException playerFailure;

  /**
   * Constructs a new engine whose players all play successfully unless configured otherwise.
   */
  MockVlc() {
    this.factory = mock(MediaPlayerFactory.class);
    this.players = new CopyOnWriteArrayList<>();
    this.outcomes = new ArrayDeque<>();
    this.creations = new AtomicInteger();
    this.formatCallback = new AtomicReference<>();
    this.renderCallback = new AtomicReference<>();
    this.failingPlayerIndex = -1;
    final MediaPlayerApi playerApi = mock(MediaPlayerApi.class);
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi surfaces = mock(uk.co.caprica.vlcj.factory.VideoSurfaceApi.class);
    when(this.factory.mediaPlayers()).thenReturn(playerApi);
    when(this.factory.videoSurfaces()).thenReturn(surfaces);
    when(playerApi.newEmbeddedMediaPlayer()).thenAnswer(_ -> this.createPlayer());
    when(surfaces.newVideoSurface(any(BufferFormatCallback.class), any(RenderCallback.class), anyBoolean())).thenAnswer(
      this::createSurface
    );
    this.sharedFactory = new SharedMediaPlayerFactory(this::createFactory);
  }

  private MediaPlayerFactory createFactory() {
    this.creations.incrementAndGet();
    final Throwable failure = this.factoryFailure;
    if (failure instanceof final RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (failure instanceof final Error error) {
      throw error;
    }
    return this.factory;
  }

  private EmbeddedMediaPlayer createPlayer() {
    final int index = this.players.size();
    if (index == this.failingPlayerIndex) {
      throw this.playerFailure;
    }
    final Outcome outcome;
    synchronized (this.outcomes) {
      outcome = this.outcomes.isEmpty() ? Outcome.PLAYING : this.outcomes.poll();
    }
    final Player created = new Player(outcome);
    this.players.add(created);
    return created.getPlayer();
  }

  private CallbackVideoSurface createSurface(final InvocationOnMock invocation) {
    final BufferFormatCallback format = invocation.getArgument(0);
    final RenderCallback render = invocation.getArgument(1);
    this.formatCallback.set(format);
    this.renderCallback.set(render);
    return mock(CallbackVideoSurface.class);
  }

  /**
   * Creates a player that uses this engine.
   *
   * @param openTimeoutMillis how long starting waits for the media to open
   * @param options           the media options of the player
   * @return the player
   */
  VLCPlayer newPlayer(final long openTimeoutMillis, final String... options) {
    return new VLCPlayer(this.sharedFactory, openTimeoutMillis, options);
  }

  /**
   * Sets the outcomes of the next created players, in creation order. Players without an outcome play successfully.
   *
   * @param next the outcomes
   */
  void queueOutcomes(final Outcome... next) {
    final List<Outcome> queued = List.of(next);
    synchronized (this.outcomes) {
      this.outcomes.addAll(queued);
    }
  }

  /**
   * Makes the creation of the VLC instance fail.
   *
   * @param failure a runtime exception or an error
   */
  void failFactoryCreation(final Throwable failure) {
    this.factoryFailure = failure;
  }

  /**
   * Makes the creation of the second media player fail, which is the audio player of separate sources.
   *
   * @param failure the exception thrown
   */
  void failSecondPlayerCreation(final RuntimeException failure) {
    this.playerFailure = failure;
    this.failingPlayerIndex = SECOND_PLAYER_INDEX;
  }

  SharedMediaPlayerFactory getSharedFactory() {
    return this.sharedFactory;
  }

  MediaPlayerFactory getFactory() {
    return this.factory;
  }

  int getCreations() {
    return this.creations.get();
  }

  List<Player> getPlayers() {
    return this.players;
  }

  Player player(final int index) {
    return this.players.get(index);
  }

  BufferFormatCallback getFormatCallback() {
    final BufferFormatCallback callback = this.formatCallback.get();
    assertNotNull(callback, "no video surface was created");
    return callback;
  }

  RenderCallback getRenderCallback() {
    final RenderCallback callback = this.renderCallback.get();
    assertNotNull(callback, "no video surface was created");
    return callback;
  }

  /**
   * One mocked VLC media player with its APIs.
   */
  static final class Player {

    private final EmbeddedMediaPlayer player;
    private final MediaApi media;
    private final ControlsApi controls;
    private final StatusApi status;
    private final AudioApi audio;
    private final EventApi events;
    private final VideoApi video;
    private final VideoSurfaceApi surface;
    private final List<MediaPlayerEventListener> listeners;
    private final AtomicBoolean playing;
    private final AtomicBoolean seekable;
    private final AtomicLong time;
    private final AtomicReference<State> state;
    private final AtomicReference<String> resource;
    private final AtomicReference<String[]> options;
    private final AtomicReference<AudioCallback> audioCallback;
    private final Outcome outcome;

    Player(final Outcome outcome) {
      this.outcome = outcome;
      this.player = mock(EmbeddedMediaPlayer.class);
      this.media = mock(MediaApi.class);
      this.controls = mock(ControlsApi.class);
      this.status = mock(StatusApi.class);
      this.audio = mock(AudioApi.class);
      this.events = mock(EventApi.class);
      this.video = mock(VideoApi.class);
      this.surface = mock(VideoSurfaceApi.class);
      this.listeners = new CopyOnWriteArrayList<>();
      this.playing = new AtomicBoolean();
      this.seekable = new AtomicBoolean(true);
      this.time = new AtomicLong();
      this.state = new AtomicReference<>(State.NOTHING_SPECIAL);
      this.resource = new AtomicReference<>();
      this.options = new AtomicReference<>();
      this.audioCallback = new AtomicReference<>();
      this.stubApis();
      this.stubBehavior();
    }

    private void stubApis() {
      when(this.player.media()).thenReturn(this.media);
      when(this.player.controls()).thenReturn(this.controls);
      when(this.player.status()).thenReturn(this.status);
      when(this.player.audio()).thenReturn(this.audio);
      when(this.player.events()).thenReturn(this.events);
      when(this.player.video()).thenReturn(this.video);
      when(this.player.videoSurface()).thenReturn(this.surface);
      when(this.status.isPlaying()).thenAnswer(_ -> this.playing.get());
      when(this.status.isSeekable()).thenAnswer(_ -> this.seekable.get());
      when(this.status.time()).thenAnswer(_ -> this.time.get());
      when(this.status.state()).thenAnswer(_ -> this.state.get());
    }

    private void stubBehavior() {
      doAnswer(this::addListener).when(this.events).addMediaPlayerEventListener(any(MediaPlayerEventListener.class));
      doAnswer(this::installAudioCallback).when(this.audio).callback(anyString(), anyInt(), anyInt(), any(AudioCallback.class));
      doAnswer(_ -> {
        this.playing.set(false);
        this.state.set(State.STOPPED);
        return null;
      })
        .when(this.controls)
        .stop();
      when(this.media.play(anyString(), any(String[].class))).thenAnswer(this::play);
    }

    private boolean addListener(final InvocationOnMock invocation) {
      final MediaPlayerEventListener listener = invocation.getArgument(0);
      return this.listeners.add(listener);
    }

    private Object installAudioCallback(final InvocationOnMock invocation) {
      final AudioCallback callback = invocation.getArgument(3);
      this.audioCallback.set(callback);
      return null;
    }

    private boolean play(final InvocationOnMock invocation) {
      final Object[] arguments = invocation.getRawArguments();
      this.resource.set((String) arguments[0]);
      this.options.set((String[]) arguments[1]);
      return switch (this.outcome) {
        case PLAYING -> {
          this.firePlaying();
          yield true;
        }
        case ERROR -> {
          this.fireError();
          yield true;
        }
        case SILENT -> {
          this.state.set(State.OPENING);
          yield true;
        }
        case REJECTED -> false;
        case THROWS -> throw new IllegalStateException("VLC crashed");
      };
    }

    void firePlaying() {
      this.playing.set(true);
      this.state.set(State.PLAYING);
      for (final MediaPlayerEventListener listener : this.listeners) {
        listener.playing(this.player);
      }
    }

    void fireFinished() {
      this.playing.set(false);
      this.state.set(State.ENDED);
      for (final MediaPlayerEventListener listener : this.listeners) {
        listener.finished(this.player);
      }
    }

    void fireError() {
      this.playing.set(false);
      this.state.set(State.ERROR);
      for (final MediaPlayerEventListener listener : this.listeners) {
        listener.error(this.player);
      }
    }

    void setPlaying(final boolean value) {
      this.playing.set(value);
    }

    void makeUnseekable() {
      this.seekable.set(false);
    }

    void setTime(final long value) {
      this.time.set(value);
    }

    void setState(final State value) {
      this.state.set(value);
    }

    EmbeddedMediaPlayer getPlayer() {
      return this.player;
    }

    ControlsApi getControls() {
      return this.controls;
    }

    StatusApi getStatus() {
      return this.status;
    }

    AudioApi getAudio() {
      return this.audio;
    }

    VideoApi getVideo() {
      return this.video;
    }

    VideoSurfaceApi getSurface() {
      return this.surface;
    }

    String getResource() {
      return this.resource.get();
    }

    String[] getOptions() {
      return this.options.get();
    }

    AudioCallback getAudioCallback() {
      final AudioCallback callback = this.audioCallback.get();
      assertNotNull(callback, "no audio callback was installed");
      return callback;
    }

    boolean hasAudioCallback() {
      return this.audioCallback.get() != null;
    }
  }
}

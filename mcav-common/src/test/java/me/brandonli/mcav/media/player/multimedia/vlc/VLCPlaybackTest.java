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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.source.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.ControlsApi;
import uk.co.caprica.vlcj.player.base.StatusApi;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

/**
 * Tests the synchronization and the cleanup of {@link VLCPlayback}; starting and controlling it through the player is
 * tested by {@link VLCPlayerTest}.
 */
final class VLCPlaybackTest {

  private static final List<String> PLAYBACK_THREAD_NAMES = List.of("mcav-vlc-sync", "mcav-vlc-render-video", "mcav-vlc-render-audio");
  private static final Duration THREAD_EXIT_TIMEOUT = Duration.ofSeconds(5);

  private final MockVlc vlc = new MockVlc();
  private final VLCPlayer owner = this.vlc.newPlayer(1_000L);
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
  private final Source video = source("video.mp4");
  private final Source audio = source("audio.ogg");

  VLCPlaybackTest() {
    this.owner.setExceptionHandler((message, error) -> {
        this.messages.add(message);
        this.errors.add(error);
      });
  }

  private static Source source(final String resource) {
    final Source source = mock(Source.class);
    when(source.getResource()).thenReturn(resource);
    return source;
  }

  private VLCPlayback separatePlayback(final long videoTime, final long audioTime) {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    videoPlayer.setPlaying(true);
    audioPlayer.setPlaying(true);
    videoPlayer.setTime(videoTime);
    audioPlayer.setTime(audioTime);
    return playback;
  }

  private void synchronize(final VLCPlayback playback) {
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final EmbeddedMediaPlayer audioMaster = audioPlayer.getPlayer();
    playback.synchronizeVideoToAudio(audioMaster);
  }

  private ControlsApi videoControls() {
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    return videoPlayer.getControls();
  }

  @ParameterizedTest
  @CsvSource({ "0, 1.0", "100, 1.0", "-100, 1.0", "101, 0.97", "1999, 0.97", "-101, 1.03", "-1999, 1.03" })
  void choosesTheRateThatClosesSmallDrifts(final long drift, final float expected) {
    final float rate = VLCPlayback.chooseRate(drift);
    assertEquals(expected, rate);
  }

  @ParameterizedTest
  @CsvSource({ "2000, 0.97", "-2000, 1.03", "500, 0.97", "0, 1.0" })
  void correctsSmallDriftsWithTheRate(final long drift, final float expected) {
    final ControlsApi controls = mock(ControlsApi.class);
    VLCPlayback.correctDrift(controls, drift, 10_000L);
    verify(controls).setRate(expected);
    verify(controls, never()).setTime(anyLong());
  }

  @ParameterizedTest
  @CsvSource({ "2001", "-2001", "60000" })
  void correctsLargeDriftsBySeekingTheVideoToTheAudio(final long drift) {
    final ControlsApi controls = mock(ControlsApi.class);
    VLCPlayback.correctDrift(controls, drift, 10_000L);
    verify(controls).setTime(10_000L);
    verify(controls).setRate(1.0f);
  }

  @Test
  void fatalSecondaryFailuresEscapeWithoutBeingSuppressed() {
    final IllegalStateException primary = new IllegalStateException("recoverable failure");
    final OutOfMemoryError fatal = new OutOfMemoryError("cleanup exhausted memory");
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
      VLCPlayback.attemptAfterFailure(primary, () -> {
        throw fatal;
      })
    );
    final Throwable[] suppressed = primary.getSuppressed();
    assertSame(fatal, thrown);
    assertEquals(0, suppressed.length);
  }

  @Test
  void synchronizesTheVideoToTheAudio() {
    final VLCPlayback playback = this.separatePlayback(1_500L, 1_000L);
    this.synchronize(playback);
    final ControlsApi controls = this.videoControls();
    verify(controls).setRate(0.97f);
    playback.stop();
  }

  @Test
  void seeksTheVideoWhenItIsFarAhead() {
    final VLCPlayback playback = this.separatePlayback(9_000L, 1_000L);
    this.synchronize(playback);
    final ControlsApi controls = this.videoControls();
    verify(controls).setTime(1_000L);
    verify(controls).setRate(1.0f);
    playback.stop();
  }

  @Test
  void leavesPlayersAloneThatAreNotBothPlaying() {
    final VLCPlayback playback = this.separatePlayback(9_000L, 1_000L);
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    audioPlayer.setPlaying(false);
    this.synchronize(playback);
    audioPlayer.setPlaying(true);
    videoPlayer.setPlaying(false);
    this.synchronize(playback);
    final ControlsApi controls = this.videoControls();
    verify(controls, never()).setRate(anyFloat());
    verify(controls, never()).setTime(anyLong());
    playback.stop();
  }

  @Test
  void reportsSynchronizationFailures() {
    final VLCPlayback playback = this.separatePlayback(0L, 0L);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final StatusApi status = audioPlayer.getStatus();
    final IllegalStateException failure = new IllegalStateException("native crash");
    when(status.isPlaying()).thenThrow(failure);
    this.synchronize(playback);
    final List<String> expectedMessages = List.of("Failed to synchronize VLC players");
    final List<Throwable> expectedErrors = List.of(failure);
    assertEquals(expectedMessages, this.messages);
    assertEquals(expectedErrors, this.errors);
    playback.stop();
  }

  @Test
  void doesNotSynchronizeAStoppedPlayback() {
    final VLCPlayback playback = this.separatePlayback(9_000L, 1_000L);
    playback.stop();
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final EmbeddedMediaPlayer audioMaster = audioPlayer.getPlayer();
    clearInvocations(audioMaster);
    playback.synchronizeVideoToAudio(audioMaster);
    verifyNoInteractions(audioMaster);
  }

  @Test
  void stopReleasesEverythingOnce() throws InterruptedException {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final boolean started = playback.start();
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    final int referencesWhilePlaying = shared.getReferences();
    playback.stop();
    playback.stop();

    final MediaPlayerFactory factory = this.vlc.getFactory();
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    for (final MockVlc.Player player : players) {
      final EmbeddedMediaPlayer mediaPlayer = player.getPlayer();
      final ControlsApi controls = player.getControls();
      verify(controls, times(1)).stop();
      verify(mediaPlayer, times(1)).release();
    }
    verify(factory, times(1)).release();

    final int referencesAfterStop = shared.getReferences();
    assertTrue(started);
    assertEquals(1, referencesWhilePlaying);
    assertEquals(0, referencesAfterStop);
    awaitNoPlaybackThreads();
  }

  static Stream<Throwable> recoverableStopFailures() {
    return Stream.of(
      new IllegalStateException("stop failed"),
      new UnsatisfiedLinkError("native stop missing"),
      new AssertionError("stop assertion")
    );
  }

  @ParameterizedTest
  @MethodSource("recoverableStopFailures")
  void stopCompletesCleanupAfterRecoverableFailures(final Throwable failure) throws InterruptedException {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final boolean started = playback.start();
    final MockVlc.Player first = this.vlc.player(0);
    final ControlsApi controls = first.getControls();
    final EmbeddedMediaPlayer firstPlayer = first.getPlayer();
    final IllegalStateException releaseFailure = new IllegalStateException("release failed");
    doThrow(failure).when(controls).stop();
    doThrow(releaseFailure).when(firstPlayer).release();

    final Class<? extends Throwable> failureType = failure.getClass();
    final Throwable thrown = assertThrows(failureType, playback::stop);
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(releaseFailure, suppressed[0]);
    playback.stop();

    final List<MockVlc.Player> players = this.vlc.getPlayers();
    for (final MockVlc.Player player : players) {
      final EmbeddedMediaPlayer mediaPlayer = player.getPlayer();
      final ControlsApi playerControls = player.getControls();
      verify(playerControls, times(1)).stop();
      verify(mediaPlayer, times(1)).release();
    }
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    final int references = shared.getReferences();
    assertTrue(started);
    assertEquals(0, references);
    awaitNoPlaybackThreads();
  }

  @Test
  void stopDoesNotSuppressTheSameFailureOntoItself() {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final MockVlc.Player first = this.vlc.player(0);
    final ControlsApi controls = first.getControls();
    final EmbeddedMediaPlayer firstPlayer = first.getPlayer();
    final IllegalStateException failure = new IllegalStateException("same native failure");
    doThrow(failure).when(controls).stop();
    doThrow(failure).when(firstPlayer).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, playback::stop);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(0, suppressed.length);
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    final int references = shared.getReferences();
    assertEquals(0, references);
  }

  @Test
  void stopPropagatesFatalVmErrorsImmediately() {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final ControlsApi controls = this.videoControls();
    final OutOfMemoryError failure = new OutOfMemoryError("fatal native allocation");
    doThrow(failure).when(controls).stop();
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    try {
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, playback::stop);
      assertSame(failure, thrown);
      final MockVlc.Player second = this.vlc.player(1);
      final EmbeddedMediaPlayer secondPlayer = second.getPlayer();
      verify(secondPlayer, never()).release();
    } finally {
      // No render threads were started; balance this test's mocked factory reference after the fatal-path check.
      shared.release();
    }
  }

  @Test
  void theSynchronizerRunsUntilThePlaybackStops() throws InterruptedException {
    final VLCPlayback playback = VLCPlayback.create(this.owner, this.video, this.audio);
    final boolean started = playback.start();
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    videoPlayer.setTime(8_000L);
    audioPlayer.setTime(3_000L);
    final ControlsApi controls = videoPlayer.getControls();
    try {
      verify(controls, timeout(3_000L).atLeastOnce()).setTime(3_000L);
      final boolean synchronizerAlive = isThreadAlive("mcav-vlc-sync");
      assertTrue(started);
      assertTrue(synchronizerAlive);
    } finally {
      playback.stop();
    }
    awaitNoPlaybackThreads();
  }

  private static boolean isThreadAlive(final String name) {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    for (final Thread thread : threads) {
      final String threadName = thread.getName();
      if (threadName.equals(name) && thread.isAlive()) {
        return true;
      }
    }
    return false;
  }

  private static List<String> findAlivePlaybackThreads() {
    final List<String> alive = new ArrayList<>();
    for (final String name : PLAYBACK_THREAD_NAMES) {
      final boolean running = isThreadAlive(name);
      if (running) {
        alive.add(name);
      }
    }
    return alive;
  }

  private static boolean noPlaybackThreadIsAlive() {
    final List<String> alive = findAlivePlaybackThreads();
    return alive.isEmpty();
  }

  /**
   * Waits up to five seconds until no thread of a playback runs anymore. Threads that stopped themselves, for example
   * because a pipeline released the player from its render thread, exit a moment after the call that stopped them.
   *
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  static void awaitNoPlaybackThreads() throws InterruptedException {
    final boolean exited = Polling.pollUntil(THREAD_EXIT_TIMEOUT, VLCPlaybackTest::noPlaybackThreadIsAlive);
    final List<String> alive = findAlivePlaybackThreads();
    assertTrue(exited, alive + " still running after the playback stopped");
  }
}

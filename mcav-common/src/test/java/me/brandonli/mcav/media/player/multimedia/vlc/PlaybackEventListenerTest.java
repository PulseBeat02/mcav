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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

/**
 * Tests {@link PlaybackEventListener}.
 */
final class PlaybackEventListenerTest {

  private final MockVlc vlc = new MockVlc();
  private final VLCPlayer owner = this.vlc.newPlayer(1_000L);
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final PlaybackEventListener listener = new PlaybackEventListener(this.owner, "movie.mkv", this.active);
  private final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  PlaybackEventListenerTest() {
    this.owner.setExceptionHandler((message, error) -> {
        this.messages.add(message);
        this.errors.add(error);
      });
  }

  private long awaitMillis(final long timeoutMillis) throws InterruptedException {
    final long start = System.nanoTime();
    this.listener.awaitOpened(timeoutMillis);
    final long end = System.nanoTime();
    final long elapsedNanos = end - start;
    return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
  }

  private static Thread startWaiter(final PlaybackEventListener waitedOn, final CountDownLatch waiting, final AtomicBoolean returned) {
    final Thread waiter = new Thread(() -> {
      waiting.countDown();
      try {
        waitedOn.awaitOpened(10_000L);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
      returned.set(true);
    });
    waiter.start();
    return waiter;
  }

  @Test
  void reportsAnErrorBeforeAWaitingStartWakesUp() throws Exception {
    final AtomicBoolean waiterReturned = new AtomicBoolean();
    final AtomicBoolean returnedBeforeTheReport = new AtomicBoolean();
    final BiConsumer<String, Throwable> handler = (_, _) -> {
      final boolean returned = waiterReturned.get();
      returnedBeforeTheReport.set(returned);
    };
    final VideoPlayerMultiplexer handlerOwner = mock(VideoPlayerMultiplexer.class);
    when(handlerOwner.getExceptionHandler()).thenReturn(handler);
    final AtomicBoolean running = new AtomicBoolean(true);
    final PlaybackEventListener waitedOn = new PlaybackEventListener(handlerOwner, "missing.mp4", running);
    final CountDownLatch waiting = new CountDownLatch(1);
    final Thread waiter = startWaiter(waitedOn, waiting, waiterReturned);

    final boolean waiterStarted = waiting.await(5, TimeUnit.SECONDS);
    waitedOn.error(this.mediaPlayer);
    waiter.join(5_000L);

    final boolean returned = waiterReturned.get();
    final boolean returnedEarly = returnedBeforeTheReport.get();
    assertTrue(waiterStarted);
    assertTrue(returned);
    assertFalse(returnedEarly, "a start that returns false finds the reason in the handler");
  }

  @Test
  void opensWhenVlcStartsPlaying() throws Exception {
    this.listener.playing(this.mediaPlayer);
    final long elapsed = this.awaitMillis(10_000L);
    final boolean opened = this.listener.awaitOpened(10_000L);
    final boolean noErrors = this.errors.isEmpty();
    assertTrue(opened);
    assertTrue(elapsed < 1_000L, "no waiting once playing, took " + elapsed);
    assertTrue(noErrors);
  }

  @Test
  void opensWhenVlcAlreadyFinishedTheMedia() throws Exception {
    this.listener.finished(this.mediaPlayer);
    final boolean opened = this.listener.awaitOpened(10_000L);
    assertTrue(opened);
  }

  @Test
  void failsAndReportsWhenVlcCannotPlayTheMedia() throws Exception {
    this.listener.error(this.mediaPlayer);
    final long elapsed = this.awaitMillis(10_000L);
    final boolean opened = this.listener.awaitOpened(10_000L);
    final List<String> expectedMessages = List.of("VLC playback of movie.mkv failed");
    final Throwable error = this.errors.getFirst();
    final PlayerException exception = assertInstanceOf(PlayerException.class, error);
    final String reason = exception.getMessage();
    assertFalse(opened);
    assertTrue(elapsed < 1_000L, "no waiting after an error, took " + elapsed);
    assertEquals(expectedMessages, this.messages);
    assertEquals("VLC could not play movie.mkv", reason);
  }

  @Test
  void reportsErrorsWhilePlaying() {
    this.listener.playing(this.mediaPlayer);
    this.listener.error(this.mediaPlayer);
    this.listener.error(this.mediaPlayer);
    final int reported = this.errors.size();
    assertEquals(2, reported, "every error is reported");
  }

  @Test
  void doesNotReportErrorsOfAStoppedPlayback() throws Exception {
    this.active.set(false);
    this.listener.error(this.mediaPlayer);
    final boolean opened = this.listener.awaitOpened(10_000L);
    final boolean noErrors = this.errors.isEmpty();
    assertFalse(opened);
    assertTrue(noErrors);
  }

  @Test
  void treatsMediaThatIsStillOpeningAsStartedAfterTheTimeout() throws Exception {
    final long elapsed = this.awaitMillis(100L);
    final boolean opened = this.listener.awaitOpened(10L);
    assertTrue(opened, "VLC may still open slow streams; later failures are reported");
    assertTrue(elapsed >= 90L, "waited only " + elapsed);
    assertTrue(elapsed < 2_000L, "waited " + elapsed);
  }

  @Test
  void treatsAnErrorAfterTheTimeoutAsALaterFailure() throws Exception {
    final boolean opened = this.listener.awaitOpened(10L);
    this.listener.error(this.mediaPlayer);
    final int reported = this.errors.size();
    assertTrue(opened, "the start already returned while VLC was still opening");
    assertEquals(1, reported, "the later failure reaches the exception handler");
  }

  @Test
  void propagatesInterrupts() {
    final Thread current = Thread.currentThread();
    current.interrupt();
    assertThrows(InterruptedException.class, () -> this.listener.awaitOpened(10_000L));
    final boolean stillInterrupted = Thread.interrupted();
    assertFalse(stillInterrupted);
  }
}

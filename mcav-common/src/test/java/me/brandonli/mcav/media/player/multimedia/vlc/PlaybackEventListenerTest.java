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

import com.google.common.util.concurrent.Uninterruptibles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

final class PlaybackEventListenerTest {

  private static final String MEDIA_NAME = "movie.mkv";
  private static final String MISSING_MEDIA_NAME = "missing.mp4";

  private static final long TIMEOUT_MILLISECONDS = 10_000L;
  private static final long SHORT_TIMEOUT_MILLISECONDS = 10L;

  private static final long QUICK_MILLISECONDS = 1_000L;

  private final MockVlc vlc = new MockVlc();
  private final VlcPlayer owner = this.vlc.newPlayer(1_000L);
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final PlaybackEventListener listener = new PlaybackEventListener(this.owner, MEDIA_NAME, this.active);
  private final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  PlaybackEventListenerTest() {
    this.owner.setExceptionHandler(this::recordFailure);
  }

  private void recordFailure(final String message, final Throwable failure) {
    this.messages.add(message);
    this.errors.add(failure);
  }

  private long awaitMilliseconds(final long timeoutMilliseconds) throws InterruptedException {
    final long start = System.nanoTime();
    this.listener.awaitOpened(timeoutMilliseconds);
    final long end = System.nanoTime();
    final long elapsedNanoseconds = end - start;
    return TimeUnit.NANOSECONDS.toMillis(elapsedNanoseconds);
  }

  private static Thread startWaiter(final PlaybackEventListener waitedOn, final CountDownLatch waiting, final AtomicBoolean returned) {
    final Thread waiter = new Thread(() -> awaitOpenedAndRecordReturn(waitedOn, waiting, returned));
    waiter.start();
    return waiter;
  }

  private static void awaitOpenedAndRecordReturn(
    final PlaybackEventListener waitedOn,
    final CountDownLatch waiting,
    final AtomicBoolean returned
  ) {
    waiting.countDown();
    try {
      waitedOn.awaitOpened(TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    returned.set(true);
  }

  @Test
  void shouldReportAnErrorBeforeAWaitingStartWakesUp() throws Exception {
    final AtomicBoolean waiterReturned = new AtomicBoolean();
    final AtomicBoolean returnedBeforeTheReport = new AtomicBoolean();
    final AtomicLong notificationCountAtReport = new AtomicLong(-1);
    final AtomicReference<LongSupplier> notificationCount = new AtomicReference<>();
    final BiConsumer<String, Throwable> handler = (_, _) ->
      this.recordStateAtReport(waiterReturned, returnedBeforeTheReport, notificationCountAtReport, notificationCount);
    final VideoPlayerMultiplexer handlerOwner = mock(VideoPlayerMultiplexer.class);
    when(handlerOwner.getExceptionHandler()).thenReturn(handler);
    final AtomicBoolean running = new AtomicBoolean(true);
    final PlaybackEventListener waitedOn = new PlaybackEventListener(handlerOwner, MISSING_MEDIA_NAME, running);
    final Field settledField = PlaybackEventListener.class.getDeclaredField("settled");
    settledField.setAccessible(true);
    final CountDownLatch notification = (CountDownLatch) settledField.get(waitedOn);
    notificationCount.set(notification::getCount);
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

    final long countAtReport = notificationCountAtReport.get();
    assertEquals(1, countAtReport, "notification cannot be released before its failure report");
  }

  private void recordStateAtReport(
    final AtomicBoolean waiterReturned,
    final AtomicBoolean returnedBeforeTheReport,
    final AtomicLong notificationCountAtReport,
    final AtomicReference<LongSupplier> notificationCount
  ) {
    final boolean returned = waiterReturned.get();
    returnedBeforeTheReport.set(returned);
    notificationCountAtReport.set(notificationCount.get().getAsLong());
  }

  @Test
  void shouldKeepAKnownOpeningFailureFailedWhileItsReportIsBlocked() throws Exception {
    final CountDownLatch reporting = new CountDownLatch(1);
    final CountDownLatch finishReport = new CountDownLatch(1);
    final AtomicReference<Throwable> reportingFailure = new AtomicReference<>();
    this.owner.setExceptionHandler((_, _) -> this.blockReport(reporting, finishReport));
    final Thread reporter = new Thread(() -> this.listener.error(this.mediaPlayer));
    reporter.setUncaughtExceptionHandler((_, failure) -> reportingFailure.set(failure));
    reporter.start();
    try {
      final boolean reported = reporting.await(10, TimeUnit.SECONDS);
      assertTrue(reported, "the opening error reached its handler");

      final boolean openedAtTimeout = this.listener.awaitOpened(0);
      assertFalse(openedAtTimeout, "a known failure cannot become a successful open at the timeout");
    } finally {
      finishReport.countDown();
      reporter.join(TIMEOUT_MILLISECONDS);
    }

    final boolean alive = reporter.isAlive();
    assertFalse(alive);

    final Throwable reportedFailure = reportingFailure.get();
    assertEquals(null, reportedFailure);

    final boolean opened = this.listener.awaitOpened(0);
    assertFalse(opened);
  }

  private void blockReport(final CountDownLatch reporting, final CountDownLatch finishReport) {
    reporting.countDown();
    final boolean finished = Uninterruptibles.awaitUninterruptibly(finishReport, 10, TimeUnit.SECONDS);
    assertTrue(finished);
  }

  @Test
  void shouldOpenWhenVlcStartsPlaying() throws Exception {
    this.listener.playing(this.mediaPlayer);
    final long elapsed = this.awaitMilliseconds(TIMEOUT_MILLISECONDS);
    final boolean opened = this.listener.awaitOpened(TIMEOUT_MILLISECONDS);
    final boolean noErrors = this.errors.isEmpty();
    assertTrue(opened);

    final boolean quick = elapsed < QUICK_MILLISECONDS;
    assertTrue(quick, "no waiting once playing, took %s".formatted(elapsed));
    assertTrue(noErrors);
  }

  @Test
  void shouldOpenWhenVlcAlreadyFinishedTheMedia() throws Exception {
    this.listener.finished(this.mediaPlayer);
    final boolean opened = this.listener.awaitOpened(TIMEOUT_MILLISECONDS);
    assertTrue(opened);
  }

  @Test
  void shouldFailAndReportWhenVlcCannotPlayTheMedia() throws Exception {
    this.listener.error(this.mediaPlayer);
    final long elapsed = this.awaitMilliseconds(TIMEOUT_MILLISECONDS);
    final boolean opened = this.listener.awaitOpened(TIMEOUT_MILLISECONDS);
    final List<String> expectedMessages = List.of("VLC playback of movie.mkv failed.");
    final Throwable error = this.errors.getFirst();
    final PlayerException exception = assertInstanceOf(PlayerException.class, error);
    final String reason = exception.getMessage();
    assertFalse(opened);

    final boolean quick = elapsed < QUICK_MILLISECONDS;
    assertTrue(quick, "no waiting after an error, took %s".formatted(elapsed));
    assertEquals(expectedMessages, this.messages);
    assertEquals("Media movie.mkv could not be played by VLC!", reason);
  }

  @Test
  void shouldReportErrorsWhilePlaying() {
    this.listener.playing(this.mediaPlayer);
    this.listener.error(this.mediaPlayer);
    this.listener.error(this.mediaPlayer);
    final int reported = this.errors.size();
    assertEquals(2, reported, "every error is reported");
  }

  @Test
  void shouldNotReportErrorsOfAStoppedPlayback() throws Exception {
    this.active.set(false);
    this.listener.error(this.mediaPlayer);
    final boolean opened = this.listener.awaitOpened(TIMEOUT_MILLISECONDS);
    final boolean noErrors = this.errors.isEmpty();
    assertFalse(opened);
    assertTrue(noErrors);
  }

  @Test
  void shouldTreatMediaThatIsStillOpeningAsStartedAfterTheTimeout() throws Exception {
    final long elapsed = this.awaitMilliseconds(100L);
    final boolean opened = this.listener.awaitOpened(SHORT_TIMEOUT_MILLISECONDS);
    assertTrue(opened, "VLC may still open slow streams; later failures are reported");

    final boolean waitedTheTimeout = elapsed >= 90L;
    assertTrue(waitedTheTimeout, "waited only %s".formatted(elapsed));

    final boolean waitedNotMuchLonger = elapsed < 2_000L;
    assertTrue(waitedNotMuchLonger, "waited %s".formatted(elapsed));
  }

  @Test
  void shouldTreatAnErrorAfterTheTimeoutAsALaterFailure() throws Exception {
    final boolean opened = this.listener.awaitOpened(SHORT_TIMEOUT_MILLISECONDS);
    this.listener.error(this.mediaPlayer);
    final int reported = this.errors.size();
    assertTrue(opened, "the start already returned while VLC was still opening");
    assertEquals(1, reported, "the later failure reaches the exception handler");
  }

  @Test
  void shouldPropagateInterrupts() {
    final Thread current = Thread.currentThread();
    current.interrupt();
    assertThrows(InterruptedException.class, () -> this.listener.awaitOpened(TIMEOUT_MILLISECONDS));

    final boolean stillInterrupted = Thread.interrupted();
    assertFalse(stillInterrupted);
  }
}

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
package me.brandonli.mcav;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.capability.Availability;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import me.brandonli.mcav.loader.LoaderPreparationProbe;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import org.slf4j.Logger;

// The logger is a mock, so the failures the installation threads report can be checked; blocked steps wait for latches, nothing sleeps.
final class BackgroundInstallationTest {

  private static final long WAIT_SECONDS = 10;
  private static final long SHORT_CANCEL_MILLISECONDS = 50L;
  private static final Duration SHORT_CANCEL_TIMEOUT = Duration.ofMillis(SHORT_CANCEL_MILLISECONDS);
  private static final String VLC_THREAD = "MCAV Installer VLC";
  private static final String YTDLP_THREAD = "MCAV Installer yt-dlp";
  private static final String NOT_STOPPED = "{} did not stop within {} ms after it was cancelled.";

  private static final String PREPARATION_FAILED = "{} could not be prepared because of an unexpected failure.";

  private static final String THREAD_FAILED = "{} ended with an error.";

  private final DependencyLoader dependencies = Mockito.mock(DependencyLoader.class);
  private final CapabilityGuard guard = new CapabilityGuard();
  private final Logger logger = Mockito.mock(Logger.class);

  private BackgroundInstallation createInstallation() {
    return new BackgroundInstallation(this.dependencies, this.guard, this.logger, SHORT_CANCEL_TIMEOUT);
  }

  @Test
  void shouldPrepareBothProgramsOnNamedDaemonThreads() throws Exception {
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    this.stubCapability(Capability.YT_DLP, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean hasVlc = await(installation, Capability.VLC);
    final Boolean hasYtdlp = await(installation, Capability.YT_DLP);
    final List<Thread> threads = installation.getThreads();
    final Thread vlcThread = threads.getFirst();
    final Thread ytdlpThread = threads.getLast();
    final String vlcName = vlcThread.getName();
    final String ytdlpName = ytdlpThread.getName();
    final boolean vlcDaemon = vlcThread.isDaemon();
    final boolean vlcAvailable = installation.isAvailable(Capability.VLC);
    assertTrue(hasVlc);
    assertTrue(hasYtdlp);
    assertEquals(VLC_THREAD, vlcName);
    assertEquals(YTDLP_THREAD, ytdlpName);
    assertTrue(vlcDaemon, "an installation thread never keeps the JVM alive");
    assertTrue(vlcAvailable);

    final DependencyLoader verifiedVlc = Mockito.verify(this.dependencies);
    verifiedVlc.installVlc();
    final DependencyLoader verifiedYtdlp = Mockito.verify(this.dependencies);
    verifiedYtdlp.installYtdlp();
  }

  @Test
  void shouldAnswerTheOtherCapabilitiesThroughTheLoader() {
    this.stubCapability(Capability.FFMPEG, Availability.AVAILABLE);
    this.stubCapability(Capability.FACE_DETECTION, Availability.UNAVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    final boolean hasFfmpeg = installation.isAvailable(Capability.FFMPEG);
    final boolean hasFaceDetection = installation.isAvailable(Capability.FACE_DETECTION);
    final CompletableFuture<Boolean> ffmpegReady = installation.whenReady(Capability.FFMPEG);
    final boolean ffmpegDone = ffmpegReady.isDone();
    final Boolean ffmpegPrepared = ffmpegReady.join();
    assertTrue(hasFfmpeg);
    assertFalse(hasFaceDetection);
    assertTrue(ffmpegDone);
    assertTrue(ffmpegPrepared);
  }

  @Test
  void shouldReportAnUnexpectedFailureOfAStepAndOnlyDisablesItsProgram() throws Exception {
    final IllegalStateException failure = new IllegalStateException("broken plugin cache!");
    final Stubber failingVlc = Mockito.doThrow(failure);
    final DependencyLoader stubbed = failingVlc.when(this.dependencies);
    stubbed.installVlc();
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    this.stubCapability(Capability.YT_DLP, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean hasVlc = await(installation, Capability.VLC);
    final Boolean hasYtdlp = await(installation, Capability.YT_DLP);
    final IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String refusalMessage = refusal.getMessage();
    assertFalse(hasVlc, "a step that failed unexpectedly does not provide its program");
    assertTrue(hasYtdlp);
    assertEquals("VLC is not available on this system!", refusalMessage);

    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn(PREPARATION_FAILED, "VLC", failure);
  }

  @Test
  void shouldEndThePreparationAndLogAnErrorThatEndsItsThread() throws Exception {
    final OutOfMemoryError error = new OutOfMemoryError("heap is full!");
    final Stubber failingYtdlp = Mockito.doThrow(error);
    final DependencyLoader stubbed = failingYtdlp.when(this.dependencies);
    stubbed.installYtdlp();
    this.stubCapability(Capability.YT_DLP, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean hasYtdlp = await(installation, Capability.YT_DLP);
    joinAll(installation);
    assertFalse(hasYtdlp, "nobody waits forever for a preparation that ended with an error");

    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.error(THREAD_FAILED, YTDLP_THREAD, error);
  }

  @Test
  void shouldRunCallbacksOutsideOfTheInstallationThreads() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlc(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);

    final AtomicReference<Thread> callbackThread = new AtomicReference<>();
    final CompletableFuture<Boolean> vlcReady = installation.whenReady(Capability.VLC);
    final CompletableFuture<Void> callback = vlcReady.thenRun(() -> this.recordCurrentThread(callbackThread));
    finishVlc.countDown();
    callback.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final List<Thread> threads = installation.getThreads();
    final Thread vlcThread = threads.getFirst();
    final Thread ranOn = callbackThread.get();
    assertNotSame(vlcThread, ranOn, "a callback may call release(), which waits for the installation threads");
  }

  private void recordCurrentThread(final AtomicReference<Thread> callbackThread) {
    final Thread current = Thread.currentThread();
    callbackThread.set(current);
  }

  @Test
  void shouldKeepPreparingWhenACallerCompletesOrCancelsItsFuture() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlc(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);

    final CompletableFuture<Boolean> cancelled = installation.whenReady(Capability.VLC);
    final CompletableFuture<Boolean> completed = installation.whenReady(Capability.VLC);
    final boolean cancelledByCaller = cancelled.cancel(true);
    final boolean completedByCaller = completed.complete(false);
    final boolean preparing = this.guard.isPreparing(Capability.VLC);
    assertTrue(cancelledByCaller);
    assertTrue(completedByCaller);
    assertTrue(preparing);

    finishVlc.countDown();
    final Boolean hasVlc = await(installation, Capability.VLC);
    final boolean vlcAvailable = installation.isAvailable(Capability.VLC);
    assertTrue(hasVlc);
    assertTrue(vlcAvailable);
  }

  @Test
  void shouldWaitForCooperativeCleanupWithinItsBudgetOnCancellation() throws Exception {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    Mockito.doAnswer(_ -> this.awaitFinishAcrossAnInterrupt(started, interrupted, finish))
      .when(this.dependencies)
      .installVlc();
    final BackgroundInstallation installation = new BackgroundInstallation(
      this.dependencies,
      this.guard,
      this.logger,
      Duration.ofSeconds(5)
    );
    installation.start();
    final boolean working = started.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(working);

    final Thread cancelling = new Thread(() -> this.cancelAndSignal(installation, cancelled));
    cancelling.start();
    try {
      final boolean receivedInterrupt = interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(receivedInterrupt);

      final boolean returnedBeforeCleanup = cancelled.await(100, TimeUnit.MILLISECONDS);
      assertFalse(returnedBeforeCleanup, "the cancellation budget lets a worker finish cleanup");
    } finally {
      finish.countDown();
      final boolean ended = cancelling.join(Duration.ofSeconds(WAIT_SECONDS));
      assertTrue(ended);

      joinAll(installation);
    }
  }

  private @Nullable Void awaitFinishAcrossAnInterrupt(
    final CountDownLatch started,
    final CountDownLatch interrupted,
    final CountDownLatch finish
  ) {
    started.countDown();
    try {
      finish.await();
    } catch (final InterruptedException exception) {
      interrupted.countDown();
      final boolean released = Uninterruptibles.awaitUninterruptibly(finish, WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(released);
    }

    return null;
  }

  private void cancelAndSignal(final BackgroundInstallation installation, final CountDownLatch cancelled) {
    try {
      installation.cancel();
    } finally {
      cancelled.countDown();
    }
  }

  @Test
  void shouldGiveUpWaitingForAThreadThatIgnoresTheInterrupt() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcUninterruptibly(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.startWithFinishedYtdlp(vlcStarted);

    installation.cancel();

    final Boolean vlcAfterCancel = await(installation, Capability.VLC);
    final boolean preparingAfterCancel = this.guard.isPreparing(Capability.VLC);
    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn(NOT_STOPPED, VLC_THREAD, SHORT_CANCEL_MILLISECONDS);
    assertFalse(vlcAfterCancel, "the cancelled program is unavailable at once, even while its thread still runs");
    assertFalse(preparingAfterCancel);

    finishVlc.countDown();
    joinAll(installation);

    final boolean vlcAfterThreadEnded = installation.isAvailable(Capability.VLC);
    assertFalse(vlcAfterThreadEnded, "a preparation that ends after the cancellation does not count");
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC), "it leaves the forgotten guard alone");
  }

  @Test
  void shouldStopWaitingWhenTheCancellingThreadIsInterrupted() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcUninterruptibly(vlcStarted, finishVlc);
    final BackgroundInstallation installation = this.startWithFinishedYtdlp(vlcStarted);
    final Thread current = Thread.currentThread();

    current.interrupt();
    installation.cancel();
    final boolean interruptKept = Thread.interrupted();

    final Boolean hasVlc = await(installation, Capability.VLC);
    assertTrue(interruptKept, "the interrupt of the cancelling thread is restored");
    assertFalse(hasVlc);

    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn(NOT_STOPPED, VLC_THREAD, SHORT_CANCEL_MILLISECONDS);
    finishVlc.countDown();
    joinAll(installation);
  }

  @Test
  void shouldNotLetACancelledPreparationOverrideANewerSuccessfulPreparation() throws Exception {
    final CountDownLatch oldStarted = new CountDownLatch(1);
    final CountDownLatch oldFinish = new CountDownLatch(1);
    final CountDownLatch newRecorded = new CountDownLatch(1);
    final CountDownLatch newFinish = new CountDownLatch(1);
    final AtomicInteger attempts = new AtomicInteger();
    final Logger progress = Mockito.mock(Logger.class);
    Mockito.doAnswer(_ -> this.signalAndAwaitUninterruptibly(newRecorded, newFinish))
      .when(progress)
      .info(ArgumentMatchers.eq("VLC ready in {} ms."), ArgumentMatchers.anyLong());
    final DependencyLoader loader = LoaderPreparationProbe.vlc(progress, () ->
      this.failTheFirstAttemptLate(attempts, oldStarted, oldFinish)
    );
    final BackgroundInstallation oldInstallation = new BackgroundInstallation(loader, this.guard, this.logger, SHORT_CANCEL_TIMEOUT);
    final BackgroundInstallation newInstallation = new BackgroundInstallation(loader, this.guard, this.logger, SHORT_CANCEL_TIMEOUT);
    try {
      oldInstallation.start();
      final boolean oldHasStarted = oldStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(oldHasStarted);

      oldInstallation.cancel();
      final boolean oldReady = await(oldInstallation, Capability.VLC);
      assertFalse(oldReady);

      newInstallation.start();
      final boolean newWasRecorded = newRecorded.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(newWasRecorded);

      oldFinish.countDown();
      joinAll(oldInstallation);
      newFinish.countDown();
      final boolean newReady = await(newInstallation, Capability.VLC);
      assertTrue(newReady, "the successful reinstall owns its readiness result");
      assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC));
    } finally {
      oldFinish.countDown();
      newFinish.countDown();
      oldInstallation.cancel();
      newInstallation.cancel();
      joinAll(oldInstallation);
      joinAll(newInstallation);
    }
  }

  private @Nullable Void signalAndAwaitUninterruptibly(final CountDownLatch recorded, final CountDownLatch finish) {
    recorded.countDown();
    Uninterruptibles.awaitUninterruptibly(finish);

    return null;
  }

  private void failTheFirstAttemptLate(final AtomicInteger attempts, final CountDownLatch started, final CountDownLatch finish) {
    if (attempts.incrementAndGet() == 1) {
      started.countDown();
      Uninterruptibles.awaitUninterruptibly(finish);

      final String message = "cancelled preparation ended late!";
      throw new IllegalStateException(message);
    }
  }

  @Test
  void shouldKeepTheResultsOfAFinishedInstallationWhenCancelling() throws Exception {
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    this.stubCapability(Capability.YT_DLP, Availability.AVAILABLE);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    await(installation, Capability.VLC);
    await(installation, Capability.YT_DLP);
    installation.cancel();
    final Boolean hasVlc = await(installation, Capability.VLC);
    Mockito.verifyNoInteractions(this.logger);
    assertTrue(hasVlc, "a program that was ready before the cancellation stays reported as ready");
  }

  private BackgroundInstallation startWithFinishedYtdlp(final CountDownLatch vlcStarted) throws Exception {
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);

    final List<Thread> threads = installation.getThreads();
    final Thread ytdlpThread = threads.getLast();
    final Duration wait = Duration.ofSeconds(WAIT_SECONDS);
    final boolean ytdlpEnded = ytdlpThread.join(wait);
    assertTrue(ytdlpEnded);

    return installation;
  }

  private void stubCapability(final Capability capability, final Availability availability) {
    final boolean available = availability == Availability.AVAILABLE;
    final Stubber stubber = Mockito.doReturn(available);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.hasCapability(capability);
  }

  private void blockVlc(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> blocking = _ -> this.signalAndAwaitFinish(vlcStarted, finishVlc);
    final Stubber stubber = Mockito.doAnswer(blocking);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVlc();
  }

  private @Nullable Void signalAndAwaitFinish(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) throws InterruptedException {
    vlcStarted.countDown();
    final boolean finished = finishVlc.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(finished);

    return null;
  }

  private void blockVlcUninterruptibly(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> ignoringInterrupts = _ -> this.signalAndAwaitFinishUninterruptibly(vlcStarted, finishVlc);
    final Stubber stubber = Mockito.doAnswer(ignoringInterrupts);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVlc();
  }

  private @Nullable Void signalAndAwaitFinishUninterruptibly(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    vlcStarted.countDown();
    final boolean finished = Uninterruptibles.awaitUninterruptibly(finishVlc, WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(finished);

    return null;
  }

  private static Boolean await(final BackgroundInstallation installation, final Capability capability) throws Exception {
    final CompletableFuture<Boolean> ready = installation.whenReady(capability);
    return ready.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private static void joinAll(final BackgroundInstallation installation) throws InterruptedException {
    final List<Thread> threads = installation.getThreads();
    final Duration wait = Duration.ofSeconds(WAIT_SECONDS);
    for (final Thread thread : threads) {
      final boolean ended = thread.join(wait);
      assertTrue(ended, thread::getName);
    }
  }
}

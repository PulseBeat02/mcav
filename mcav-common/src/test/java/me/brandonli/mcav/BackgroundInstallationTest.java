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
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import org.slf4j.Logger;

/**
 * Tests {@link BackgroundInstallation} with a mocked {@link DependencyLoader} and a mocked logger, so the failures
 * the installation threads report can be checked. Blocked steps wait for latches; nothing sleeps.
 */
final class BackgroundInstallationTest {

  private static final long WAIT_SECONDS = 10;
  private static final Duration SHORT_CANCEL_TIMEOUT = Duration.ofMillis(50);
  private static final String VLC_THREAD = "MCAV Installer VLC";
  private static final String YTDLP_THREAD = "MCAV Installer yt-dlp";
  private static final String NOT_STOPPED = "{} did not stop within {} ms after it was cancelled";

  private final DependencyLoader dependencies = Mockito.mock(DependencyLoader.class);
  private final CapabilityGuard guard = new CapabilityGuard();
  private final Logger logger = Mockito.mock(Logger.class);

  private BackgroundInstallation createInstallation() {
    return new BackgroundInstallation(this.dependencies, this.guard, this.logger, SHORT_CANCEL_TIMEOUT);
  }

  @Test
  void preparesBothProgramsOnNamedDaemonThreads() throws Exception {
    this.stubCapability(Capability.VLC, true);
    this.stubCapability(Capability.YT_DLP, true);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean vlc = await(installation, Capability.VLC);
    final Boolean ytdlp = await(installation, Capability.YT_DLP);
    final List<Thread> threads = installation.getThreads();
    final Thread vlcThread = threads.getFirst();
    final Thread ytdlpThread = threads.getLast();
    final String vlcName = vlcThread.getName();
    final String ytdlpName = ytdlpThread.getName();
    final boolean vlcDaemon = vlcThread.isDaemon();
    final boolean vlcAvailable = installation.isAvailable(Capability.VLC);
    assertTrue(vlc);
    assertTrue(ytdlp);
    assertEquals(VLC_THREAD, vlcName);
    assertEquals(YTDLP_THREAD, ytdlpName);
    assertTrue(vlcDaemon, "an installation thread never keeps the JVM alive");
    assertTrue(vlcAvailable);
    final DependencyLoader verifiedVlc = Mockito.verify(this.dependencies);
    verifiedVlc.installVLC();
    final DependencyLoader verifiedYtdlp = Mockito.verify(this.dependencies);
    verifiedYtdlp.installYTDLP();
  }

  @Test
  void answersTheOtherCapabilitiesThroughTheLoader() {
    this.stubCapability(Capability.FFMPEG, true);
    this.stubCapability(Capability.FACE_DETECTION, false);
    final BackgroundInstallation installation = this.createInstallation();
    final boolean ffmpeg = installation.isAvailable(Capability.FFMPEG);
    final boolean faceDetection = installation.isAvailable(Capability.FACE_DETECTION);
    final CompletableFuture<Boolean> ffmpegReady = installation.whenReady(Capability.FFMPEG);
    final boolean ffmpegDone = ffmpegReady.isDone();
    final Boolean ffmpegPrepared = ffmpegReady.join();
    assertTrue(ffmpeg);
    assertFalse(faceDetection);
    assertTrue(ffmpegDone);
    assertTrue(ffmpegPrepared);
  }

  @Test
  void reportsAnUnexpectedFailureOfAStepAndOnlyDisablesItsProgram() throws Exception {
    final IllegalStateException failure = new IllegalStateException("broken plugin cache");
    final Stubber failingVlc = Mockito.doThrow(failure);
    final DependencyLoader stubbed = failingVlc.when(this.dependencies);
    stubbed.installVLC();
    this.stubCapability(Capability.VLC, true);
    this.stubCapability(Capability.YT_DLP, true);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean vlc = await(installation, Capability.VLC);
    final Boolean ytdlp = await(installation, Capability.YT_DLP);
    final IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String refusalMessage = refusal.getMessage();
    assertFalse(vlc, "a step that failed unexpectedly does not provide its program");
    assertTrue(ytdlp);
    assertEquals("VLC is not available on this system", refusalMessage);
    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn("{} could not be prepared because of an unexpected failure", "VLC", failure);
  }

  @Test
  void endsThePreparationAndLogsAnErrorThatEndsItsThread() throws Exception {
    final OutOfMemoryError error = new OutOfMemoryError("heap is full");
    final Stubber failingYtdlp = Mockito.doThrow(error);
    final DependencyLoader stubbed = failingYtdlp.when(this.dependencies);
    stubbed.installYTDLP();
    this.stubCapability(Capability.YT_DLP, true);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final Boolean ytdlp = await(installation, Capability.YT_DLP);
    joinAll(installation);
    assertFalse(ytdlp, "nobody waits forever for a preparation that ended with an error");
    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.error("{} ended with an error", YTDLP_THREAD, error);
  }

  @Test
  void runsCallbacksOutsideOfTheInstallationThreads() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlc(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, true);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);
    final AtomicReference<Thread> callbackThread = new AtomicReference<>();
    final CompletableFuture<Boolean> vlcReady = installation.whenReady(Capability.VLC);
    final CompletableFuture<Void> callback = vlcReady.thenRun(() -> {
      final Thread current = Thread.currentThread();
      callbackThread.set(current);
    });
    finishVlc.countDown();
    callback.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final List<Thread> threads = installation.getThreads();
    final Thread vlcThread = threads.getFirst();
    final Thread ranOn = callbackThread.get();
    assertNotSame(vlcThread, ranOn, "a callback may call release(), which waits for the installation threads");
  }

  @Test
  void givesUpWaitingForAThreadThatIgnoresTheInterrupt() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcUninterruptibly(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, true);
    final BackgroundInstallation installation = this.startWithFinishedYtdlp(vlcStarted);

    installation.cancel();

    final Boolean vlcAfterCancel = await(installation, Capability.VLC);
    final boolean preparingAfterCancel = this.guard.isPreparing(Capability.VLC);
    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn(NOT_STOPPED, VLC_THREAD, 50L);
    assertFalse(vlcAfterCancel, "the cancelled program is unavailable at once, even while its thread still runs");
    assertFalse(preparingAfterCancel);

    finishVlc.countDown();
    joinAll(installation);

    final boolean vlcAfterThreadEnded = installation.isAvailable(Capability.VLC);
    assertFalse(vlcAfterThreadEnded, "a preparation that ends after the cancellation does not count");
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC), "it leaves the forgotten guard alone");
  }

  @Test
  void stopsWaitingWhenTheCancellingThreadIsInterrupted() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcUninterruptibly(vlcStarted, finishVlc);
    final BackgroundInstallation installation = this.startWithFinishedYtdlp(vlcStarted);
    final Thread current = Thread.currentThread();

    current.interrupt();
    installation.cancel();
    final boolean interruptKept = Thread.interrupted();

    final Boolean vlc = await(installation, Capability.VLC);
    assertTrue(interruptKept, "the interrupt of the cancelling thread is restored");
    assertFalse(vlc);
    final Logger verifiedLogger = Mockito.verify(this.logger);
    verifiedLogger.warn(NOT_STOPPED, VLC_THREAD, 50L);
    finishVlc.countDown();
    joinAll(installation);
  }

  @Test
  void cancellingAFinishedInstallationKeepsItsResults() throws Exception {
    this.stubCapability(Capability.VLC, true);
    this.stubCapability(Capability.YT_DLP, true);
    final BackgroundInstallation installation = this.createInstallation();
    installation.start();
    await(installation, Capability.VLC);
    await(installation, Capability.YT_DLP);
    installation.cancel();
    final Boolean vlc = await(installation, Capability.VLC);
    Mockito.verifyNoInteractions(this.logger);
    assertTrue(vlc, "a program that was ready before the cancellation stays reported as ready");
  }

  /**
   * Starts an installation whose yt-dlp step finishes at once, and waits until VLC started and yt-dlp's thread ended.
   */
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

  private void stubCapability(final Capability capability, final boolean available) {
    final Stubber stubber = Mockito.doReturn(available);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.hasCapability(capability);
  }

  private void blockVlc(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> blocking = _ -> {
      vlcStarted.countDown();
      final boolean finished = finishVlc.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(finished);
      return null;
    };
    final Stubber stubber = Mockito.doAnswer(blocking);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVLC();
  }

  private void blockVlcUninterruptibly(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> ignoringInterrupts = _ -> {
      vlcStarted.countDown();
      // stands in for native code that cannot be interrupted, such as loading libvlc
      final boolean finished = Uninterruptibles.awaitUninterruptibly(finishVlc, WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(finished);
      return null;
    };
    final Stubber stubber = Mockito.doAnswer(ignoringInterrupts);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVLC();
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

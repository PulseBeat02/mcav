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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import me.brandonli.mcav.module.MCAVModule;
import me.brandonli.mcav.module.ModuleException;
import me.brandonli.mcav.module.ModuleLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import org.mockito.verification.VerificationMode;

/**
 * Tests {@link MCAV} and {@link MCAVLoadingException} with a mocked {@link DependencyLoader}, so nothing is
 * downloaded. Every instance records its background installations in a guard of its own, so the shared guard that VLC
 * players consult is never touched.
 */
final class MCAVTest {

  private static final long WAIT_SECONDS = 10;
  private static final Duration PROMPT_RELEASE = Duration.ofSeconds(5);

  /**
   * A module that counts how often it was started and stopped.
   */
  static final class CountingModule implements MCAVModule {

    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();

    @Override
    public void start() {
      this.starts.incrementAndGet();
    }

    @Override
    public void stop() {
      this.stops.incrementAndGet();
    }

    @Override
    public String getModuleName() {
      return "counting";
    }
  }

  /**
   * A module that counts starts and stops of every instance, for installations whose modules cannot be looked up
   * afterward.
   */
  static final class TrackedModule implements MCAVModule {

    static final AtomicInteger STARTS = new AtomicInteger();
    static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      STARTS.incrementAndGet();
    }

    @Override
    public void stop() {
      STOPS.incrementAndGet();
    }

    @Override
    public String getModuleName() {
      return "tracked";
    }
  }

  /**
   * A module that cannot start.
   */
  static final class BrokenModule implements MCAVModule {

    @Override
    public void start() {
      throw new IllegalStateException("port in use");
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public String getModuleName() {
      return "broken";
    }
  }

  private final DependencyLoader dependencies = Mockito.mock(DependencyLoader.class);
  private final ModuleLoader modules = createSpiedModuleLoader();
  private final CapabilityGuard guard = new CapabilityGuard();
  private final MCAV mcav = new MCAV(this.dependencies, this.modules, this.guard);

  private static ModuleLoader createSpiedModuleLoader() {
    final ModuleLoader loader = new ModuleLoader();
    return Mockito.spy(loader);
  }

  @BeforeEach
  void resetTrackedModule() {
    TrackedModule.STARTS.set(0);
    TrackedModule.STOPS.set(0);
  }

  @AfterEach
  void releaseTheLibrary() {
    // ends the installation threads of every test, even of one that failed
    this.mcav.release();
  }

  @Test
  void createsSeparateUninstalledInstances() {
    final MCAVApi first = MCAV.api();
    final MCAVApi second = MCAV.api();
    assertInstanceOf(MCAV.class, first);
    assertNotSame(first, second);
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> first.hasCapability(Capability.FFMPEG));
    final String message = exception.getMessage();
    assertEquals("MCAV has not been installed yet, call install() first", message);
    final MCAVLoadingException waitException = assertThrows(MCAVLoadingException.class, () -> first.whenCapabilityReady(Capability.VLC));
    final String waitMessage = waitException.getMessage();
    assertEquals("MCAV has not been installed yet, call install() first", waitMessage);
    assertThrows(ModuleException.class, () -> first.getModule(CountingModule.class));
    first.release();
  }

  @Test
  void installRunsEveryStepAndStartsTheModules() throws Exception {
    final boolean previousCache = ImageIO.getUseCache();
    ImageIO.setUseCache(true);
    try {
      this.stubCapability(Capability.VLC, false);
      this.stubCapability(Capability.YT_DLP, true);
      this.mcav.install(CountingModule.class);
      final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
      final CompletableFuture<Boolean> ytdlpReady = this.mcav.whenCapabilityReady(Capability.YT_DLP);
      final Boolean vlcPrepared = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
      final Boolean ytdlpPrepared = ytdlpReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
      final CountingModule module = this.mcav.getModule(CountingModule.class);
      final int starts = module.starts.get();
      final boolean vlc = this.mcav.hasCapability(Capability.VLC);
      final boolean ytdlp = this.mcav.hasCapability(Capability.YT_DLP);
      final boolean imageCache = ImageIO.getUseCache();
      assertEquals(1, starts);
      assertFalse(vlcPrepared);
      assertTrue(ytdlpPrepared);
      assertFalse(vlc);
      assertTrue(ytdlp);
      assertFalse(imageCache);
      this.verifyEveryInstallationStep();
      final ModuleLoader verifiedModules = Mockito.verify(this.modules);
      verifiedModules.loadModules(CountingModule.class);
    } finally {
      ImageIO.setUseCache(previousCache);
    }
  }

  @Test
  void installsWithoutModules() throws Exception {
    this.mcav.install();
    // the mocked loader is stubbed below, which must not race with the installation threads using it
    this.awaitBackgroundInstallation();
    final Collection<MCAVModule> started = this.modules.getModules();
    final boolean startedEmpty = started.isEmpty();
    assertTrue(startedEmpty);
    this.stubCapability(Capability.FFMPEG, true);
    final boolean ffmpeg = this.mcav.hasCapability(Capability.FFMPEG);
    assertTrue(ffmpeg);
  }

  @Test
  void reportsWhetherFaceDetectionIsAvailable() throws Exception {
    this.mcav.install();
    this.awaitBackgroundInstallation();
    this.stubCapability(Capability.FACE_DETECTION, false);
    final boolean faceDetection = this.mcav.hasCapability(Capability.FACE_DETECTION);
    assertFalse(faceDetection);
  }

  @Test
  void answersCapabilitiesThatAreNotPreparedInTheBackgroundAtOnce() {
    this.stubCapability(Capability.FFMPEG, true);
    this.stubCapability(Capability.FACE_DETECTION, false);
    this.mcav.install();
    final CompletableFuture<Boolean> ffmpegReady = this.mcav.whenCapabilityReady(Capability.FFMPEG);
    final CompletableFuture<Boolean> faceDetectionReady = this.mcav.whenCapabilityReady(Capability.FACE_DETECTION);
    final boolean ffmpegDone = ffmpegReady.isDone();
    final boolean faceDetectionDone = faceDetectionReady.isDone();
    final Boolean ffmpeg = ffmpegReady.join();
    final Boolean faceDetection = faceDetectionReady.join();
    assertTrue(ffmpegDone, "FFmpeg is decided while install() runs");
    assertTrue(faceDetectionDone, "face detection is decided while install() runs");
    assertTrue(ffmpeg);
    assertFalse(faceDetection);
  }

  @Test
  void installReturnsWhileVlcIsStillBeingPrepared() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcInstallation(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, true);

    this.mcav.install(CountingModule.class);

    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    final int starts = module.starts.get();
    final boolean vlcWhilePreparing = this.mcav.hasCapability(Capability.VLC);
    final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
    final boolean doneWhilePreparing = vlcReady.isDone();
    final boolean preparing = this.guard.isPreparing(Capability.VLC);
    assertTrue(started);
    assertEquals(1, starts, "the modules are usable while VLC is still being prepared");
    assertFalse(vlcWhilePreparing, "VLC is not available before it is ready");
    assertFalse(doneWhilePreparing);
    assertTrue(preparing, "features that need VLC refuse while it is being prepared");

    finishVlc.countDown();

    final Boolean available = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final boolean vlcWhenReady = this.mcav.hasCapability(Capability.VLC);
    final boolean stillPreparing = this.guard.isPreparing(Capability.VLC);
    final CompletableFuture<Boolean> readyAgain = this.mcav.whenCapabilityReady(Capability.VLC);
    final boolean doneAgain = readyAgain.isDone();
    assertTrue(available);
    assertTrue(vlcWhenReady);
    assertFalse(stillPreparing);
    assertTrue(doneAgain, "a finished preparation is answered with a complete future");
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC));
  }

  @Test
  void reportsProgramsThatCouldNotBePreparedAsUnavailable() throws Exception {
    // the loader removes the capability of a program whose download failed or whose system is not supported
    this.stubCapability(Capability.VLC, false);
    this.stubCapability(Capability.YT_DLP, false);
    this.mcav.install();

    final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
    final CompletableFuture<Boolean> ytdlpReady = this.mcav.whenCapabilityReady(Capability.YT_DLP);
    final Boolean vlc = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final Boolean ytdlp = ytdlpReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final boolean vlcAvailable = this.mcav.hasCapability(Capability.VLC);
    final IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String refusalMessage = refusal.getMessage();
    assertFalse(vlc);
    assertFalse(ytdlp);
    assertFalse(vlcAvailable);
    assertEquals("VLC is not available on this system", refusalMessage);

    this.mcav.release();

    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC), "a released library forgets what it prepared");
  }

  @Test
  void releaseInterruptsAPreparationThatIsStillRunningAndReturnsPromptly() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch vlcInterrupted = new CountDownLatch(1);
    final AtomicReference<Thread> vlcThread = new AtomicReference<>();
    final Answer<Void> blockUntilInterrupted = _ -> {
      final Thread current = Thread.currentThread();
      vlcThread.set(current);
      vlcStarted.countDown();
      final CountDownLatch never = new CountDownLatch(1);
      try {
        never.await();
      } catch (final InterruptedException exception) {
        vlcInterrupted.countDown();
        current.interrupt();
      }
      return null;
    };
    final Stubber stubber = Mockito.doAnswer(blockUntilInterrupted);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVLC();
    // even a loader that reports VLC as available is not believed once the preparation was cancelled
    this.stubCapability(Capability.VLC, true);
    this.mcav.install(TrackedModule.class);
    final boolean started = vlcStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);
    final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);

    assertTimeout(PROMPT_RELEASE, this.mcav::release, "release() does not wait for VLC to be downloaded");

    final long interruptionsLeft = vlcInterrupted.getCount();
    final Thread thread = vlcThread.get();
    final boolean alive = thread.isAlive();
    final Boolean available = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final boolean preparing = this.guard.isPreparing(Capability.VLC);
    final int stops = TrackedModule.STOPS.get();
    assertEquals(0, interruptionsLeft, "release() interrupts the preparation");
    assertFalse(alive, "no installation thread outlives release()");
    assertFalse(available, "a cancelled preparation reports its program as unavailable");
    assertFalse(preparing);
    assertEquals(1, stops);
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC));
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));
  }

  @Test
  void installCanOnlyRunOncePerRelease() throws Exception {
    this.mcav.install(CountingModule.class);
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(CountingModule.class));
    final String message = exception.getMessage();
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    final int starts = module.starts.get();
    assertEquals("MCAV has already been installed", message);
    assertEquals(1, starts);
    this.awaitBackgroundInstallation();
    this.verifyEveryInstallationStep();
  }

  @Test
  void concurrentInstallsRunTheInstallationOnce() throws Exception {
    final CountDownLatch nativesStarted = new CountDownLatch(1);
    final CountDownLatch finishNatives = new CountDownLatch(1);
    this.blockNativeLoading(nativesStarted, finishNatives);
    final CompletableFuture<Void> first = CompletableFuture.runAsync(() -> this.mcav.install(CountingModule.class));
    final boolean started = nativesStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(CountingModule.class));
    finishNatives.countDown();
    first.get(30, TimeUnit.SECONDS);
    final String message = exception.getMessage();
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    final int starts = module.starts.get();
    assertEquals("MCAV has already been installed", message);
    assertEquals(1, starts);
    this.awaitBackgroundInstallation();
    final DependencyLoader verifiedDependencies = Mockito.verify(this.dependencies);
    verifiedDependencies.installVLC();
  }

  @Test
  void releaseWaitsForARunningInstallationAndThenStopsItsModules() throws Exception {
    final CountDownLatch nativesStarted = new CountDownLatch(1);
    final CountDownLatch finishNatives = new CountDownLatch(1);
    this.blockNativeLoading(nativesStarted, finishNatives);
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Future<?> install = executor.submit(() -> this.mcav.install(TrackedModule.class));
      final boolean started = nativesStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);
      final AtomicReference<Thread> releasingThread = new AtomicReference<>();
      final Future<?> release = executor.submit(() -> {
        final Thread currentThread = Thread.currentThread();
        releasingThread.set(currentThread);
        this.mcav.release();
      });
      final boolean releaseWaited = awaitBlocked(releasingThread);
      final boolean releasedEarly = release.isDone();
      finishNatives.countDown();
      install.get(30, TimeUnit.SECONDS);
      release.get(30, TimeUnit.SECONDS);
      assertTrue(releaseWaited, "release() waits for the running installation");
      assertFalse(releasedEarly);
    }
    final int starts = TrackedModule.STARTS.get();
    final int stops = TrackedModule.STOPS.get();
    assertEquals(1, starts);
    assertEquals(1, stops, "the modules started by the installation are stopped by the release");
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));
  }

  @Test
  void failedInstallStopsTheStartedModulesAndCanBeRetried() throws Exception {
    final IllegalStateException failure = new IllegalStateException("no natives");
    final Stubber failingNatives = Mockito.doThrow(failure);
    final DependencyLoader failingLoader = failingNatives.when(this.dependencies);
    failingLoader.loadModules();
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(TrackedModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> afterFailure = this.modules.getModules();
    final int startsAfterFailure = TrackedModule.STARTS.get();
    final int stopsAfterFailure = TrackedModule.STOPS.get();
    assertEquals("MCAV failed to install: no natives", message);
    assertSame(failure, cause);
    final boolean afterFailureEmpty = afterFailure.isEmpty();
    assertTrue(afterFailureEmpty);
    assertEquals(1, startsAfterFailure);
    assertEquals(1, stopsAfterFailure, "the module started before the failure is stopped again");
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));
    final VerificationMode never = Mockito.never();
    final DependencyLoader notPrepared = Mockito.verify(this.dependencies, never);
    notPrepared.installVLC();
    final Stubber workingNatives = Mockito.doNothing();
    final DependencyLoader workingLoader = workingNatives.when(this.dependencies);
    workingLoader.loadModules();
    this.stubCapability(Capability.VLC, true);
    this.mcav.install(TrackedModule.class);
    this.awaitBackgroundInstallation();
    final TrackedModule module = this.mcav.getModule(TrackedModule.class);
    final int startsAfterRetry = TrackedModule.STARTS.get();
    final boolean vlc = this.mcav.hasCapability(Capability.VLC);
    final String moduleName = module.getModuleName();
    assertEquals("tracked", moduleName);
    assertEquals(2, startsAfterRetry);
    assertTrue(vlc);
  }

  @Test
  void wrapsModuleFailures() {
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () ->
      this.mcav.install(CountingModule.class, BrokenModule.class)
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> started = this.modules.getModules();
    assertEquals("MCAV failed to install: Module broken failed to start: port in use", message);
    assertInstanceOf(ModuleException.class, cause);
    final boolean startedEmpty = started.isEmpty();
    assertTrue(startedEmpty);
  }

  @Test
  void reportsFailuresThatHaveNoCause() {
    final CompletionException failure = new CompletionException("lost cause", null);
    final Stubber failingNatives = Mockito.doThrow(failure);
    final DependencyLoader failingLoader = failingNatives.when(this.dependencies);
    failingLoader.loadModules();
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, this.mcav::install);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("MCAV failed to install: lost cause", message);
    assertSame(failure, cause);
  }

  @Test
  void releaseStopsTheModulesOnceAndAllowsAnotherInstall() {
    this.mcav.install(CountingModule.class);
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    this.mcav.release();
    this.mcav.release();
    final int stops = module.stops.get();
    assertEquals(1, stops);
    final ModuleLoader verifiedModules = Mockito.verify(this.modules);
    verifiedModules.shutdownModules();
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));
    assertThrows(MCAVLoadingException.class, () -> this.mcav.whenCapabilityReady(Capability.VLC));
    assertThrows(ModuleException.class, () -> this.mcav.getModule(CountingModule.class));
    this.mcav.install(CountingModule.class);
    final CountingModule reinstalled = this.mcav.getModule(CountingModule.class);
    final int starts = reinstalled.starts.get();
    assertNotSame(module, reinstalled);
    assertEquals(1, starts);
  }

  @Test
  void releaseBeforeInstallDoesNothing() {
    this.mcav.release();
    final VerificationMode never = Mockito.never();
    final ModuleLoader verifiedModules = Mockito.verify(this.modules, never);
    verifiedModules.shutdownModules();
  }

  @Test
  void rejectsNulls() throws Exception {
    assertThrows(NullPointerException.class, () -> this.mcav.hasCapability(null));
    assertThrows(NullPointerException.class, () -> this.mcav.whenCapabilityReady(null));
    assertThrows(NullPointerException.class, () -> this.mcav.install((Class<?>[]) null));
    assertThrows(NullPointerException.class, () -> this.mcav.getModule(null));
    this.mcav.install();
    this.awaitBackgroundInstallation();
    final DependencyLoader verifiedDependencies = Mockito.verify(this.dependencies);
    verifiedDependencies.installVLC();
  }

  @Test
  void createsLoadingExceptionsWithMessageAndCause() {
    final IllegalStateException cause = new IllegalStateException("cause");
    final MCAVLoadingException withMessage = new MCAVLoadingException("message");
    final MCAVLoadingException withCause = new MCAVLoadingException("wrapped", cause);
    final String message = withMessage.getMessage();
    final String wrappedMessage = withCause.getMessage();
    final Throwable wrappedCause = withCause.getCause();
    assertEquals("message", message);
    assertEquals("wrapped", wrappedMessage);
    assertSame(cause, wrappedCause);
    assertInstanceOf(RuntimeException.class, withMessage, "a failed installation is recoverable, so it is no Error");
  }

  private void stubCapability(final Capability capability, final boolean available) {
    final Stubber stubber = Mockito.doReturn(available);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.hasCapability(capability);
  }

  private void blockVlcInstallation(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> blockingInstallation = blockUntil(vlcStarted, finishVlc);
    final Stubber stubber = Mockito.doAnswer(blockingInstallation);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVLC();
  }

  private void blockNativeLoading(final CountDownLatch nativesStarted, final CountDownLatch finishNatives) {
    final Answer<Void> blockingNatives = blockUntil(nativesStarted, finishNatives);
    final Stubber stubber = Mockito.doAnswer(blockingNatives);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.loadModules();
  }

  private static Answer<Void> blockUntil(final CountDownLatch started, final CountDownLatch finish) {
    return _ -> {
      started.countDown();
      final boolean finished = finish.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(finished);
      return null;
    };
  }

  /**
   * Waits until VLC and yt-dlp have been prepared in the background.
   */
  private void awaitBackgroundInstallation() throws Exception {
    final CompletableFuture<Boolean> vlc = this.mcav.whenCapabilityReady(Capability.VLC);
    final CompletableFuture<Boolean> ytdlp = this.mcav.whenCapabilityReady(Capability.YT_DLP);
    vlc.get(WAIT_SECONDS, TimeUnit.SECONDS);
    ytdlp.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private void verifyEveryInstallationStep() {
    final DependencyLoader verifiedVlc = Mockito.verify(this.dependencies);
    verifiedVlc.installVLC();
    final DependencyLoader verifiedYtdlp = Mockito.verify(this.dependencies);
    verifiedYtdlp.installYTDLP();
    final DependencyLoader verifiedNatives = Mockito.verify(this.dependencies);
    verifiedNatives.loadModules();
  }

  /**
   * Waits until the thread in the reference blocks on a monitor. Nothing signals that state change, so the thread
   * state is checked in a spin loop until a deadline.
   */
  private static boolean awaitBlocked(final AtomicReference<Thread> threadReference) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Thread thread = threadReference.get();
      if (thread != null) {
        final Thread.State state = thread.getState();
        if (state == Thread.State.BLOCKED) {
          return true;
        }
      }
      Thread.onSpinWait();
    }
    return false;
  }
}

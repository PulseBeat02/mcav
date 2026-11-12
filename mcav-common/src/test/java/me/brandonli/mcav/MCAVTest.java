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
import me.brandonli.mcav.capability.Availability;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import me.brandonli.mcav.module.MCAVModule;
import me.brandonli.mcav.module.ModuleException;
import me.brandonli.mcav.module.ModuleLoader;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import org.mockito.verification.VerificationMode;

// Each MCAV records its background installations in a guard of its own, never the shared one that VLC players consult.
final class MCAVTest {

  private static final long WAIT_SECONDS = 10;
  private static final Duration PROMPT_RELEASE = Duration.ofSeconds(5);
  private static final long INSTALLATION_TIMEOUT_SECONDS = 30;

  private static final String TRACKED_MODULE_NAME = "tracked";

  private static final String ALREADY_INSTALLED_MESSAGE = "MCAV has already been installed!";

  private final DependencyLoader dependencies = Mockito.mock(DependencyLoader.class);
  private final ModuleLoader modules = createSpiedModuleLoader();
  private final CapabilityGuard guard = new CapabilityGuard();
  private final CountDownLatch paletteBuilt = new CountDownLatch(1);
  private final MCAV mcav = new MCAV(this.dependencies, this.modules, this.guard, this.paletteBuilt::countDown);

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
    this.mcav.release();
  }

  @Test
  void shouldCreateSeparateUninstalledInstances() {
    final MCAVApi first = MCAV.api();
    final MCAVApi second = MCAV.api();
    assertInstanceOf(MCAV.class, first);
    assertNotSame(first, second);

    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> first.hasCapability(Capability.FFMPEG));
    final String message = exception.getMessage();
    assertEquals("MCAV has not been installed yet, call install() first!", message);

    final MCAVLoadingException waitException = assertThrows(MCAVLoadingException.class, () -> first.whenCapabilityReady(Capability.VLC));
    final String waitMessage = waitException.getMessage();
    assertEquals("MCAV has not been installed yet, call install() first!", waitMessage);
    assertThrows(ModuleException.class, () -> first.getModule(CountingModule.class));

    first.release();
  }

  @Test
  void shouldRunEveryStepAndStartTheModulesOnInstall() throws Exception {
    final boolean previousCache = ImageIO.getUseCache();
    ImageIO.setUseCache(true);
    try {
      this.stubCapability(Capability.VLC, Availability.UNAVAILABLE);
      this.stubCapability(Capability.YT_DLP, Availability.AVAILABLE);
      this.mcav.install(CountingModule.class);
      final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
      final CompletableFuture<Boolean> ytdlpReady = this.mcav.whenCapabilityReady(Capability.YT_DLP);
      final Boolean vlcPrepared = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
      final Boolean ytdlpPrepared = ytdlpReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
      final CountingModule module = this.mcav.getModule(CountingModule.class);
      final int starts = module.starts.get();
      final boolean hasVlc = this.mcav.hasCapability(Capability.VLC);
      final boolean hasYtdlp = this.mcav.hasCapability(Capability.YT_DLP);
      final boolean imageCache = ImageIO.getUseCache();
      assertEquals(1, starts);
      assertFalse(vlcPrepared);
      assertTrue(ytdlpPrepared);
      assertFalse(hasVlc);
      assertTrue(hasYtdlp);
      assertFalse(imageCache);

      this.verifyEveryInstallationStep();
      final ModuleLoader verifiedModules = Mockito.verify(this.modules);
      verifiedModules.loadModules(CountingModule.class);
    } finally {
      ImageIO.setUseCache(previousCache);
    }
  }

  // glibc keeps freed memory in its arenas, so an installed MCAV hands it back to the system once a minute, on Linux.
  @Test
  void shouldTrimTheNativeHeapWhileInstalled() {
    final boolean linux = OsUtils.getOs() == Os.LINUX;
    final boolean beforeInstall = this.mcav.isTrimmingNativeHeap();
    this.mcav.install();
    final boolean installed = this.mcav.isTrimmingNativeHeap();
    this.mcav.release();
    final boolean released = this.mcav.isTrimmingNativeHeap();

    assertFalse(beforeInstall);
    assertEquals(linux, installed);
    assertFalse(released);
  }

  @Test
  void shouldBuildTheMapColorLookupTableBeforeInstallReturns() {
    this.mcav.install();
    final long pendingBuilds = this.paletteBuilt.getCount();
    assertEquals(0, pendingBuilds);
  }

  @Test
  void shouldInstallWithoutModules() throws Exception {
    this.mcav.install();
    // the mocked loader is stubbed below, which must not race with the installation threads using it
    this.awaitBackgroundInstallation();
    final Collection<MCAVModule> started = this.modules.getModules();
    final boolean startedEmpty = started.isEmpty();
    assertTrue(startedEmpty);

    this.stubCapability(Capability.FFMPEG, Availability.AVAILABLE);
    final boolean hasFfmpeg = this.mcav.hasCapability(Capability.FFMPEG);
    assertTrue(hasFfmpeg);
  }

  @Test
  void shouldReportWhetherFaceDetectionIsAvailable() throws Exception {
    this.mcav.install();
    this.awaitBackgroundInstallation();
    this.stubCapability(Capability.FACE_DETECTION, Availability.UNAVAILABLE);
    final boolean hasFaceDetection = this.mcav.hasCapability(Capability.FACE_DETECTION);
    assertFalse(hasFaceDetection);
  }

  @Test
  void shouldAnswerCapabilitiesThatAreNotPreparedInTheBackgroundAtOnce() {
    this.stubCapability(Capability.FFMPEG, Availability.AVAILABLE);
    this.stubCapability(Capability.FACE_DETECTION, Availability.UNAVAILABLE);
    this.mcav.install();
    final CompletableFuture<Boolean> ffmpegReady = this.mcav.whenCapabilityReady(Capability.FFMPEG);
    final CompletableFuture<Boolean> faceDetectionReady = this.mcav.whenCapabilityReady(Capability.FACE_DETECTION);
    final boolean ffmpegDone = ffmpegReady.isDone();
    final boolean faceDetectionDone = faceDetectionReady.isDone();
    final Boolean hasFfmpeg = ffmpegReady.join();
    final Boolean hasFaceDetection = faceDetectionReady.join();
    assertTrue(ffmpegDone, "FFmpeg is decided while install() runs");
    assertTrue(faceDetectionDone, "face detection is decided while install() runs");
    assertTrue(hasFfmpeg);
    assertFalse(hasFaceDetection);
  }

  @Test
  void shouldReturnFromInstallWhileVlcIsStillBeingPrepared() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch finishVlc = new CountDownLatch(1);
    this.blockVlcInstallation(vlcStarted, finishVlc);
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);

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
  void shouldReportProgramsThatCouldNotBePreparedAsUnavailable() throws Exception {
    this.stubCapability(Capability.VLC, Availability.UNAVAILABLE);
    this.stubCapability(Capability.YT_DLP, Availability.UNAVAILABLE);
    this.mcav.install();

    final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
    final CompletableFuture<Boolean> ytdlpReady = this.mcav.whenCapabilityReady(Capability.YT_DLP);
    final Boolean hasVlc = vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final Boolean hasYtdlp = ytdlpReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    final boolean vlcAvailable = this.mcav.hasCapability(Capability.VLC);
    final IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String refusalMessage = refusal.getMessage();
    assertFalse(hasVlc);
    assertFalse(hasYtdlp);
    assertFalse(vlcAvailable);
    assertEquals("VLC is not available on this system!", refusalMessage);

    this.mcav.release();

    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC), "a released library forgets what it prepared");
  }

  @Test
  void shouldInterruptAPreparationThatIsStillRunningAndReturnPromptlyOnRelease() throws Exception {
    final CountDownLatch vlcStarted = new CountDownLatch(1);
    final CountDownLatch vlcInterrupted = new CountDownLatch(1);
    final AtomicReference<Thread> vlcThread = new AtomicReference<>();
    final Answer<Void> blockUntilInterrupted = _ -> this.recordThreadAndAwaitInterrupt(vlcThread, vlcStarted, vlcInterrupted);
    final Stubber stubber = Mockito.doAnswer(blockUntilInterrupted);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVlc();
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
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

  private @Nullable Void recordThreadAndAwaitInterrupt(
    final AtomicReference<Thread> vlcThread,
    final CountDownLatch vlcStarted,
    final CountDownLatch vlcInterrupted
  ) {
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
  }

  @Test
  void shouldInstallOnlyOncePerRelease() throws Exception {
    this.mcav.install(CountingModule.class);
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(CountingModule.class));
    final String message = exception.getMessage();
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    final int starts = module.starts.get();
    assertEquals(ALREADY_INSTALLED_MESSAGE, message);
    assertEquals(1, starts);

    this.awaitBackgroundInstallation();
    this.verifyEveryInstallationStep();
  }

  @Test
  void shouldRunTheInstallationOnceForConcurrentInstalls() throws Exception {
    final CountDownLatch nativesStarted = new CountDownLatch(1);
    final CountDownLatch finishNatives = new CountDownLatch(1);
    this.blockNativeLoading(nativesStarted, finishNatives);
    final CompletableFuture<Void> first = CompletableFuture.runAsync(() -> this.mcav.install(CountingModule.class));
    final boolean started = nativesStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(started);

    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(CountingModule.class));
    finishNatives.countDown();
    first.get(INSTALLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final String message = exception.getMessage();
    final CountingModule module = this.mcav.getModule(CountingModule.class);
    final int starts = module.starts.get();
    assertEquals(ALREADY_INSTALLED_MESSAGE, message);
    assertEquals(1, starts);

    this.awaitBackgroundInstallation();
    final DependencyLoader verifiedDependencies = Mockito.verify(this.dependencies);
    verifiedDependencies.installVlc();
  }

  @Test
  void shouldWaitForARunningInstallationAndThenStopItsModulesOnRelease() throws Exception {
    final CountDownLatch nativesStarted = new CountDownLatch(1);
    final CountDownLatch finishNatives = new CountDownLatch(1);
    this.blockNativeLoading(nativesStarted, finishNatives);
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Future<?> install = executor.submit(() -> this.mcav.install(TrackedModule.class));
      final boolean started = nativesStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);

      final AtomicReference<Thread> releasingThread = new AtomicReference<>();
      final Future<?> release = executor.submit(() -> this.recordThreadAndRelease(releasingThread));
      final boolean releaseWaited = awaitBlocked(releasingThread);
      final boolean releasedEarly = release.isDone();
      finishNatives.countDown();
      install.get(INSTALLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      release.get(INSTALLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(releaseWaited, "release() waits for the running installation");
      assertFalse(releasedEarly);
    }

    final int starts = TrackedModule.STARTS.get();
    final int stops = TrackedModule.STOPS.get();
    assertEquals(1, starts);
    assertEquals(1, stops, "the modules started by the installation are stopped by the release");
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));
  }

  private void recordThreadAndRelease(final AtomicReference<Thread> releasingThread) {
    final Thread currentThread = Thread.currentThread();
    releasingThread.set(currentThread);
    this.mcav.release();
  }

  @Test
  void shouldStopTheStartedModulesAfterAFailedInstallAndAllowARetry() throws Exception {
    final IllegalStateException failure = new IllegalStateException("no natives!");
    final Stubber failingNatives = Mockito.doThrow(failure);
    final DependencyLoader failingLoader = failingNatives.when(this.dependencies);
    failingLoader.loadModules();
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () -> this.mcav.install(TrackedModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> afterFailure = this.modules.getModules();
    final int startsAfterFailure = TrackedModule.STARTS.get();
    final int stopsAfterFailure = TrackedModule.STOPS.get();
    assertEquals("Because of \"no natives!\", MCAV failed to install!", message);
    assertSame(failure, cause);

    final boolean afterFailureEmpty = afterFailure.isEmpty();
    assertTrue(afterFailureEmpty);
    assertEquals(1, startsAfterFailure);
    assertEquals(1, stopsAfterFailure, "the module started before the failure is stopped again");
    assertThrows(MCAVLoadingException.class, () -> this.mcav.hasCapability(Capability.VLC));

    final VerificationMode never = Mockito.never();
    final DependencyLoader notPrepared = Mockito.verify(this.dependencies, never);
    notPrepared.installVlc();
    final Stubber workingNatives = Mockito.doNothing();
    final DependencyLoader workingLoader = workingNatives.when(this.dependencies);
    workingLoader.loadModules();
    this.stubCapability(Capability.VLC, Availability.AVAILABLE);
    this.mcav.install(TrackedModule.class);
    this.awaitBackgroundInstallation();
    final TrackedModule module = this.mcav.getModule(TrackedModule.class);
    final int startsAfterRetry = TrackedModule.STARTS.get();
    final boolean hasVlc = this.mcav.hasCapability(Capability.VLC);
    final String moduleName = module.getModuleName();
    assertEquals(TRACKED_MODULE_NAME, moduleName);
    assertEquals(2, startsAfterRetry);
    assertTrue(hasVlc);
  }

  @Test
  void shouldWrapModuleFailures() {
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, () ->
      this.mcav.install(CountingModule.class, BrokenModule.class)
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> started = this.modules.getModules();
    assertEquals("Because of \"Module broken failed with \"port in use!\" while starting!\", MCAV failed to install!", message);
    assertInstanceOf(ModuleException.class, cause);

    final boolean startedEmpty = started.isEmpty();
    assertTrue(startedEmpty);
  }

  @Test
  void shouldReportFailuresThatHaveNoCause() {
    final CompletionException failure = new CompletionException("lost cause!", null);
    final Stubber failingNatives = Mockito.doThrow(failure);
    final DependencyLoader failingLoader = failingNatives.when(this.dependencies);
    failingLoader.loadModules();
    final MCAVLoadingException exception = assertThrows(MCAVLoadingException.class, this.mcav::install);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Because of \"lost cause!\", MCAV failed to install!", message);
    assertSame(failure, cause);
  }

  @Test
  void shouldStopTheModulesOnceAndAllowAnotherInstallOnRelease() {
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
  void shouldDoNothingOnReleaseBeforeInstall() {
    this.mcav.release();
    final VerificationMode never = Mockito.never();
    final ModuleLoader verifiedModules = Mockito.verify(this.modules, never);
    verifiedModules.shutdownModules();
  }

  @Test
  void shouldRejectNulls() throws Exception {
    assertThrows(NullPointerException.class, () -> this.mcav.hasCapability(null));
    assertThrows(NullPointerException.class, () -> this.mcav.whenCapabilityReady(null));
    assertThrows(NullPointerException.class, () -> this.mcav.install((Class<?>[]) null));
    assertThrows(NullPointerException.class, () -> this.mcav.getModule(null));

    this.mcav.install();
    this.awaitBackgroundInstallation();
    final DependencyLoader verifiedDependencies = Mockito.verify(this.dependencies);
    verifiedDependencies.installVlc();
  }

  @Test
  void shouldCreateLoadingExceptionsWithMessageAndCause() {
    final IllegalStateException cause = new IllegalStateException("cause!");
    final MCAVLoadingException withMessage = new MCAVLoadingException("message!");
    final MCAVLoadingException withCause = new MCAVLoadingException("wrapped!", cause);
    final String message = withMessage.getMessage();
    final String wrappedMessage = withCause.getMessage();
    final Throwable wrappedCause = withCause.getCause();
    assertEquals("message!", message);
    assertEquals("wrapped!", wrappedMessage);
    assertSame(cause, wrappedCause);
    assertInstanceOf(MCAVException.class, withMessage, "a failed installation is recoverable, so it is no Error");
  }

  private void stubCapability(final Capability capability, final Availability availability) {
    final boolean available = availability == Availability.AVAILABLE;
    final Stubber stubber = Mockito.doReturn(available);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.hasCapability(capability);
  }

  private void blockVlcInstallation(final CountDownLatch vlcStarted, final CountDownLatch finishVlc) {
    final Answer<Void> blockingInstallation = blockUntil(vlcStarted, finishVlc);
    final Stubber stubber = Mockito.doAnswer(blockingInstallation);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.installVlc();
  }

  private void blockNativeLoading(final CountDownLatch nativesStarted, final CountDownLatch finishNatives) {
    final Answer<Void> blockingNatives = blockUntil(nativesStarted, finishNatives);
    final Stubber stubber = Mockito.doAnswer(blockingNatives);
    final DependencyLoader stubbed = stubber.when(this.dependencies);
    stubbed.loadModules();
  }

  private static Answer<Void> blockUntil(final CountDownLatch started, final CountDownLatch finish) {
    return _ -> signalAndAwaitFinish(started, finish);
  }

  private static @Nullable Void signalAndAwaitFinish(final CountDownLatch started, final CountDownLatch finish)
    throws InterruptedException {
    started.countDown();
    final boolean finished = finish.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(finished);

    return null;
  }

  private void awaitBackgroundInstallation() throws Exception {
    final CompletableFuture<Boolean> vlcReady = this.mcav.whenCapabilityReady(Capability.VLC);
    final CompletableFuture<Boolean> ytdlpReady = this.mcav.whenCapabilityReady(Capability.YT_DLP);
    vlcReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
    ytdlpReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private void verifyEveryInstallationStep() {
    final DependencyLoader verifiedVlc = Mockito.verify(this.dependencies);
    verifiedVlc.installVlc();
    final DependencyLoader verifiedYtdlp = Mockito.verify(this.dependencies);
    verifiedYtdlp.installYtdlp();
    final DependencyLoader verifiedNatives = Mockito.verify(this.dependencies);
    verifiedNatives.loadModules();
  }

  // nothing signals that a thread blocks on a monitor, so its state is checked in a spin loop until a deadline
  private static boolean awaitBlocked(final AtomicReference<Thread> threadReference) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Thread thread = threadReference.get();
      final boolean blocked = isBlocked(thread);
      if (blocked) {
        return true;
      }

      Thread.onSpinWait();
    }

    return false;
  }

  private static boolean isBlocked(final @Nullable Thread thread) {
    if (thread == null) {
      return false;
    }

    final Thread.State state = thread.getState();
    return state == Thread.State.BLOCKED;
  }

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

  static final class TrackedModule implements MCAVModule {

    private static final AtomicInteger STARTS = new AtomicInteger();
    private static final AtomicInteger STOPS = new AtomicInteger();

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
      return TRACKED_MODULE_NAME;
    }
  }

  static final class BrokenModule implements MCAVModule {

    @Override
    public void start() {
      final String message = "port in use!";
      throw new IllegalStateException(message);
    }

    @Override
    public void stop() {}

    @Override
    public String getModuleName() {
      return "broken";
    }
  }
}

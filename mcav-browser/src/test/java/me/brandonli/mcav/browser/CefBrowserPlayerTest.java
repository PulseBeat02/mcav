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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

class CefBrowserPlayerTest {

  private static final String HELPER_EXIT_REASON = "The helper exited with code 1!";
  private static final String CRASH_REASON = "The helper crashed!";
  private static final String INPUT_QUEUE_FULL_MESSAGE = "Browser input queue is full. The browser input backlog is full!";
  private static final String RELEASED_START_FAILURE_MESSAGE = "the start of a released player fails";
  private static final String RELEASED_RESTART_FAILURE_MESSAGE = "a released player never starts";
  private static final String CALLER_INTERRUPT_MESSAGE = "an interrupt of the caller's own is not the player's to take";
  private static final String HELPER_EXITED_MESSAGE = "The browser helper exited";
  private static final String ALREADY_PLAYING_MESSAGE = "a playing player does not start twice";
  private static final String START_FAILURE_MESSAGE = "No browser here!";
  private static final String RELEASE_INTERRUPT_MESSAGE = "the release's interrupt is the player's own";

  private static final String INPUT_SEPARATOR = " ";

  private static final int DROPPED_INPUT_COUNT = 500;

  private static final String MISSING_NATIVES_PREFIX = "mcav-no-natives-";

  private static final String HELPER_FACTORY_TEXT = "the helper factory";
  private static final String INPUT_KEY = "x";

  private static final String UNREACHABLE_REPOSITORY = "https://unreachable.test/";

  private static final String INSTALLATION_PREFIX = "jcef-";
  private static final String NAME_SEPARATOR = "-";

  private static final long HELPER_START_TIMEOUT_MILLISECONDS = 60_000L;

  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";

  private static final BrowserSource SOURCE = BrowserSource.uri(URI.create("https://example.com/"), 100, 50, 1);

  private static final String END_REASON = "gone";

  private static final String INTERRUPTED_START_MESSAGE = "Interrupted while starting the browser!";
  private static final String OFFLINE_MESSAGE = "The helper is offline!";

  private final List<FakeSession> sessions = new ArrayList<>();
  private final List<String> reports = new ArrayList<>();
  private final List<ImageBuffer> processed = new ArrayList<>();
  private final AtomicLong now = new AtomicLong();

  private final CefBrowserPlayer player = new CefBrowserPlayer(
    BrowserOptions.DEFAULT,
    (_, _, sessionListener) -> this.openRecordedSession(sessionListener),
    this.now::get
  );

  private BrowserSession.Listener listener;
  private RuntimeException startFailure;

  CefBrowserPlayerTest() {
    this.player.setExceptionHandler((message, failure) -> this.reports.add(message + INPUT_SEPARATOR + failure.getMessage()));
    this.attachRecorder(this.player);
  }

  private BrowserSession openRecordedSession(final BrowserSession.Listener sessionListener) {
    if (this.startFailure != null) {
      throw this.startFailure;
    }

    this.listener = sessionListener;
    final FakeSession session = new FakeSession();
    this.sessions.add(session);

    return session;
  }

  private void attachRecorder(final CefBrowserPlayer target) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((samples, _) -> this.recordFrame(samples));
    final VideoPipelineStep pipeline = builder.build();
    target.getVideoAttachableCallback().attach(pipeline);
  }

  private boolean recordFrame(final ImageBuffer samples) {
    this.processed.add(samples);

    return false;
  }

  private ImageBuffer frame() {
    return ImageBuffer.buffer(new int[100 * 50], 100, 50);
  }

  private List<byte[]> attachSoundRecorder() {
    final List<byte[]> heard = new CopyOnWriteArrayList<>();
    this.player.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, _) -> this.recordSound(heard, samples)));

    return heard;
  }

  private boolean recordSound(final List<byte[]> heard, final ByteBuffer samples) {
    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    heard.add(copy);

    return true;
  }

  @Test
  void shouldPassTheSoundOfTheCurrentSessionToTheAudioPipelineAndNoOtherSound() throws InterruptedException {
    final List<byte[]> heard = this.attachSoundRecorder();
    final AudioAttachableCallback audioAttachableCallback = this.player.getAudioAttachableCallback();
    final AudioAttachableCallback currentAudioAttachableCallback = this.player.getAudioAttachableCallback();
    assertSame(currentAudioAttachableCallback, audioAttachableCallback);

    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final BrowserSession.Listener first = this.listener;
    first.onAudio(new byte[] { 1, 0, 1, 0 });
    Await.until("the sound of the page", () -> heard.size() == 1);
    final byte[] firstSound = heard.getFirst();
    final byte[] expectedFirstSound = new byte[] { 1, 0, 1, 0 };
    assertArrayEquals(expectedFirstSound, firstSound);

    final boolean released = this.player.release();
    assertTrue(released);

    first.onAudio(new byte[] { 2, 0, 2, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 3, 0, 3, 0 });
    Thread.sleep(CefBrowserPlayer.MAXIMUM_QUEUED_AUDIO_MILLISECONDS * 2L);
    final int soundCount = heard.size();
    assertEquals(1, soundCount);
  }

  @Test
  void shouldDropSoundBeforeTheSessionIsThePlayersOrOfAnOldSession() throws InterruptedException {
    final List<byte[]> heard = this.attachSoundRecorder();
    final CefBrowserPlayer.SessionFactory factory = (_, _, sessionListener) -> this.openSessionAfterEarlySound(sessionListener);
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    final List<byte[]> earlyHeard = new CopyOnWriteArrayList<>();
    early.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, metadata) -> earlyHeard.add(new byte[0])));
    final boolean earlyStarted = early.start(SOURCE);
    assertTrue(earlyStarted);

    early.release();
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final FakeSession old = new FakeSession();
    this.player.deliverAudio(old, new byte[] { 4, 0, 4, 0 });
    this.listener.onAudio(new byte[] { 5, 0, 5, 0 });
    Await.until("the sound of the current session", () -> heard.size() == 1);
    Thread.sleep(CefBrowserPlayer.MAXIMUM_QUEUED_AUDIO_MILLISECONDS * 2L);
    final int soundCount = heard.size();
    assertEquals(1, soundCount);

    final byte firstSoundByte = heard.getFirst()[0];
    assertEquals(5, firstSoundByte);
    assertEquals(List.of(), earlyHeard);
  }

  private BrowserSession openSessionAfterEarlySound(final BrowserSession.Listener sessionListener) {
    sessionListener.onAudio(new byte[] { 9, 0, 9, 0 });
    this.listener = sessionListener;
    final FakeSession session = new FakeSession();
    this.sessions.add(session);

    return session;
  }

  @Test
  void shouldPlayAndAskForTheFirstPictureAgainOnceStarted() {
    final boolean playingBeforeStart = this.player.isPlaying();
    assertFalse(playingBeforeStart);

    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final boolean playingAfterStart = this.player.isPlaying();
    assertTrue(playingAfterStart);

    final FakeSession session = this.sessions.getFirst();
    assertEquals(1, session.requested);

    final boolean startedAgain = this.player.start(SOURCE);
    assertFalse(startedAgain, "a playing player does not start again");

    final int sessionCount = this.sessions.size();
    assertEquals(1, sessionCount);
  }

  @Test
  void shouldRunFramesThroughThePipelineAndCloseThem() {
    this.player.start(SOURCE);
    final ImageBuffer frame = this.frame();
    this.listener.onFrame(frame);
    final int processedCount = this.processed.size();
    assertEquals(1, processedCount);

    final ImageBuffer processedFrame = this.processed.getFirst();
    assertSame(frame, processedFrame);
  }

  @Test
  void shouldDropFramesOfTheStartBeforeTheSessionIsKnown() {
    final ImageBuffer early = this.frame();
    final CefBrowserPlayer.SessionFactory factory = (_, _, sessionListener) -> this.openSessionAfterAFrame(sessionListener, early);
    final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    this.attachRecorder(player);
    final boolean started = player.start(SOURCE);
    assertTrue(started);

    final boolean playing = player.isPlaying();
    assertTrue(playing);
    assertEquals(List.of(), this.processed);
  }

  private BrowserSession openSessionAfterAFrame(final BrowserSession.Listener sessionListener, final ImageBuffer frame) {
    sessionListener.onFrame(frame);

    return new FakeSession();
  }

  @Test
  void shouldStopAStartInProgressOnReleaseInsteadOfWaitingForIt() throws Exception {
    final CountDownLatch opening = new CountDownLatch(1);
    final CefBrowserPlayer slow = new CefBrowserPlayer(BrowserOptions.DEFAULT, (_, _, _) -> this.openAfterWaitingForever(opening));
    final ExecutorService starter = Executors.newSingleThreadExecutor();
    try {
      // read on the thread that called start, in the same task: the executor clears a worker's interrupt between tasks
      final AtomicBoolean leftInterrupted = new AtomicBoolean(true);
      final Future<Boolean> started = starter.submit(() -> this.startAndRecordTheInterrupt(slow, leftInterrupted));
      opening.await();
      final Boolean released = assertTimeoutPreemptively(Duration.ofSeconds(5), slow::release);
      assertTrue(released, "the release does not wait for the start");

      final Boolean startSucceeded = started.get(5, TimeUnit.SECONDS);
      assertFalse(startSucceeded, RELEASED_START_FAILURE_MESSAGE);

      final boolean playing = slow.isPlaying();
      assertFalse(playing);

      final boolean callerLeftInterrupted = leftInterrupted.get();
      assertFalse(callerLeftInterrupted, "the release's interrupt is the player's own, not left to the thread that called start");

      final Thread current = Thread.currentThread();
      current.interrupt();
      try {
        final boolean restarted = slow.start(SOURCE);
        assertFalse(restarted, RELEASED_RESTART_FAILURE_MESSAGE);

        final boolean interrupted = current.isInterrupted();
        assertTrue(interrupted, CALLER_INTERRUPT_MESSAGE);
      } finally {
        Thread.interrupted();
      }
    } finally {
      starter.shutdownNow();
    }
  }

  private BrowserSession openAfterWaitingForever(final CountDownLatch opening) {
    opening.countDown();
    try {
      new CountDownLatch(1).await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      throw new BrowserException(INTERRUPTED_START_MESSAGE, exception);
    }

    return new FakeSession();
  }

  private boolean startAndRecordTheInterrupt(final CefBrowserPlayer slow, final AtomicBoolean leftInterrupted) {
    final boolean result = slow.start(SOURCE);
    leftInterrupted.set(Thread.currentThread().isInterrupted());

    return result;
  }

  @Test
  void shouldFailTheStartAndReportAnEndOfTheHelperWhileTheSessionStarts() {
    final List<FakeSession> opened = new ArrayList<>();
    final List<String> reports = new ArrayList<>();
    final CefBrowserPlayer.SessionFactory factory = (_, _, sessionListener) -> this.openSessionThatEndsFirst(sessionListener, opened);
    final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    player.setExceptionHandler((message, failure) -> reports.add(message));
    final boolean firstStarted = player.start(SOURCE);
    assertFalse(firstStarted);

    final boolean playingAfterFailure = player.isPlaying();
    assertFalse(playingAfterFailure);
    final List<String> expectedReportsBeforeRestart = List.of("Because of \"The browser helper exited\", the browser session ended.");
    assertEquals(expectedReportsBeforeRestart, reports);

    final boolean startedAgain = player.start(SOURCE);
    assertTrue(startedAgain, "a failed player starts again");

    final boolean playingAfterRestart = player.isPlaying();
    assertTrue(playingAfterRestart);

    final FakeSession deadSession = opened.getFirst();
    assertEquals(1, deadSession.closed, "the dead session is closed");
    final List<String> expectedReportsAfterRestart = List.of("Because of \"The browser helper exited\", the browser session ended.");
    assertEquals(expectedReportsAfterRestart, reports);
  }

  private BrowserSession openSessionThatEndsFirst(final BrowserSession.Listener sessionListener, final List<FakeSession> opened) {
    final FakeSession session = new FakeSession();
    if (opened.isEmpty()) {
      sessionListener.onEnded(HELPER_EXITED_MESSAGE, new IllegalStateException(HELPER_EXIT_REASON));
    }

    opened.add(session);

    return session;
  }

  private Set<Thread> audioThreads() {
    final Set<Thread> threads = new HashSet<>();
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().equals(DelayedAudioOutput.THREAD_NAME)) {
        threads.add(thread);
      }
    }

    return threads;
  }

  @Test
  void shouldEndTheAudioThreadOnReleaseAndStartNeitherAPlayingNorAReleasedPlayer() {
    final Set<Thread> before = this.audioThreads();
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final boolean noNewAudioThread = before.containsAll(this.audioThreads());
    assertFalse(noNewAudioThread, "a started player hands its sound over on a thread of its own");

    final boolean startedWhilePlaying = this.player.start(SOURCE);
    assertFalse(startedWhilePlaying, ALREADY_PLAYING_MESSAGE);

    final boolean released = this.player.release();
    assertTrue(released);

    Await.until("the audio thread of the released player ended", () -> before.containsAll(this.audioThreads()));
    final boolean startedAfterRelease = this.player.start(SOURCE);
    assertFalse(startedAfterRelease, "a released player does not start again");

    final int sessionCount = this.sessions.size();
    assertEquals(1, sessionCount);
  }

  @Test
  void shouldTakeTheSoundOfTheSessionAlongAndLeaveNoThreadWhenTheHelperEnds() {
    final Set<Thread> before = this.audioThreads();
    final List<byte[]> heard = this.attachSoundRecorder();
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final BrowserSession.Listener ended = this.listener;
    ended.onEnded(END_REASON, new IllegalStateException(CRASH_REASON));
    Await.until("the audio thread of the failed session ended", () -> before.containsAll(this.audioThreads()));
    ended.onAudio(new byte[] { 6, 0, 6, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 7, 0, 7, 0 });
    assertEquals(List.of(), heard);

    final CefBrowserPlayer.SessionFactory factory = (_, _, sessionListener) -> this.openSessionThatEnds(sessionListener);
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    early.setExceptionHandler((message, failure) -> {});
    final boolean earlyStarted = early.start(SOURCE);
    assertFalse(earlyStarted);

    Await.until("the audio thread of the failed start ended", () -> before.containsAll(this.audioThreads()));
    early.release();
  }

  private BrowserSession openSessionThatEnds(final BrowserSession.Listener sessionListener) {
    sessionListener.onEnded(HELPER_EXITED_MESSAGE, new IllegalStateException(HELPER_EXIT_REASON));

    return new FakeSession();
  }

  @Test
  void shouldReportAFailingPipelineAndStillCloseTheFrame() {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((_, _) -> this.breakTheFilter());
    this.player.getVideoAttachableCallback().attach(builder.build());
    this.player.start(SOURCE);
    final ClosingProbe probe = new ClosingProbe();
    try (final ImageBuffer original = this.frame()) {
      this.listener.onFrame(probe.wrap(original));
      final boolean closed = probe.isClosed();
      assertTrue(closed, "a failed pipeline still returns the frame's ownership");
    }

    final List<String> expectedReports = List.of("Failed to process a browser frame. The filter broke!");
    assertEquals(expectedReports, this.reports);
  }

  private boolean breakTheFilter() {
    final String message = "The filter broke!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldThrowAFatalErrorOfTheVirtualMachine() {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((_, _) -> this.runOutOfMemory());
    this.player.getVideoAttachableCallback().attach(builder.build());
    this.player.start(SOURCE);
    final ClosingProbe probe = new ClosingProbe();
    try (final ImageBuffer original = this.frame()) {
      assertThrows(OutOfMemoryError.class, () -> this.listener.onFrame(probe.wrap(original)));

      final boolean closed = probe.isClosed();
      assertTrue(closed, "a fatal pipeline failure still returns the frame's ownership");
    }
  }

  private boolean runOutOfMemory() {
    final String message = "The memory is full!";
    throw new OutOfMemoryError(message);
  }

  @Test
  void shouldIgnoreFramesAndEndsOfAReplacedSession() {
    this.player.start(SOURCE);
    final BrowserSession.Listener first = this.listener;
    first.onEnded(END_REASON, new IllegalStateException(CRASH_REASON));
    final boolean playingAfterEnd = this.player.isPlaying();
    assertFalse(playingAfterEnd);
    final List<String> expectedReports = List.of("Because of \"gone\", the browser session ended. The helper crashed!");
    assertEquals(expectedReports, this.reports);

    final boolean startedAgain = this.player.start(SOURCE);
    assertTrue(startedAgain);

    final FakeSession session = this.sessions.getFirst();
    assertEquals(1, session.closed);

    first.onFrame(this.frame());
    first.onEnded("late", new IllegalStateException("The failure arrived late!"));
    final int processedCount = this.processed.size();
    assertEquals(0, processedCount);

    final int reportCount = this.reports.size();
    assertEquals(1, reportCount);

    final boolean playingAfterLateEvents = this.player.isPlaying();
    assertTrue(playingAfterLateEvents);
  }

  @Test
  void shouldDropAFrameOfTheCurrentSessionAfterItFailed() {
    this.player.start(SOURCE);
    this.listener.onEnded(END_REASON, new IllegalStateException(CRASH_REASON));
    final ImageBuffer late = this.frame();
    this.listener.onFrame(late);
    final int processedCount = this.processed.size();
    assertEquals(0, processedCount, "a failed player shows nothing more");
  }

  @Test
  void shouldReportAnEndOnce() {
    this.player.start(SOURCE);
    this.listener.onEnded(END_REASON, new IllegalStateException("The first failure!"));
    this.listener.onEnded("gone again", new IllegalStateException("The second failure!"));
    final List<String> expectedReports = List.of("Because of \"gone\", the browser session ended. The first failure!");
    assertEquals(expectedReports, this.reports);
  }

  @Test
  void shouldLeaveThePlayerIdleAfterAFailedStart() {
    this.startFailure = new BrowserException(START_FAILURE_MESSAGE);
    assertThrows(BrowserException.class, () -> this.player.start(SOURCE));

    final boolean playing = this.player.isPlaying();
    assertFalse(playing);

    this.startFailure = null;
    final boolean startedAfterFailure = this.player.start(SOURCE);
    assertTrue(startedAfterFailure);
  }

  @Test
  void shouldKeepTheInterruptOfTheCallerWhenTheStartIsRefused() {
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      final boolean startedAgain = this.player.start(SOURCE);
      assertFalse(startedAgain, ALREADY_PLAYING_MESSAGE);

      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, CALLER_INTERRUPT_MESSAGE);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldKeepTheInterruptOfTheCallerWhenTheStartFails() {
    this.startFailure = new BrowserException(START_FAILURE_MESSAGE);
    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      assertThrows(BrowserException.class, () -> this.player.start(SOURCE));

      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, CALLER_INTERRUPT_MESSAGE);
    } finally {
      Thread.interrupted();
      this.startFailure = null;
    }
  }

  // the helper notices the interrupt of the release only once it is done
  private boolean leftInterruptedByAStartThat(final Supplier<BrowserSession> ending) throws Exception {
    final CountDownLatch opening = new CountDownLatch(1);
    final CefBrowserPlayer late = new CefBrowserPlayer(BrowserOptions.DEFAULT, (_, _, _) ->
      this.waitForTheInterruptAndEnd(opening, ending)
    );
    final ExecutorService starter = Executors.newSingleThreadExecutor();
    try {
      // read on the thread that called start, in the same task: the executor clears a worker's interrupt between tasks
      final Future<Boolean> leftInterrupted = starter.submit(() -> this.startAndReadTheInterrupt(late));
      final boolean factoryEntered = opening.await(5, TimeUnit.SECONDS);
      assertTrue(factoryEntered, "the helper factory must be entered before release");

      final Boolean released = assertTimeoutPreemptively(Duration.ofSeconds(5), late::release);
      assertTrue(released);

      final boolean left = leftInterrupted.get(5, TimeUnit.SECONDS);
      final boolean playing = late.isPlaying();
      assertFalse(playing, "a released player plays nothing");

      return left;
    } finally {
      starter.shutdownNow();
      try {
        final boolean starterStopped = starter.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(starterStopped, "the fixture must stop its startup worker");
      } finally {
        assertTimeoutPreemptively(Duration.ofSeconds(5), late::release);
      }
    }
  }

  private BrowserSession waitForTheInterruptAndEnd(final CountDownLatch opening, final Supplier<BrowserSession> ending) {
    opening.countDown();
    while (!Thread.currentThread().isInterrupted()) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }

    return ending.get();
  }

  private boolean startAndReadTheInterrupt(final CefBrowserPlayer late) {
    try {
      late.start(SOURCE);
    } catch (final IllegalStateException exception) {
      // the helper's own failure, which the start passes on
    }

    return Thread.currentThread().isInterrupted();
  }

  // no wall-clock limit, which a loaded host would overrun: every wait of the fixture is bounded
  @Test
  void shouldStillEndTheInterruptFixtureWhenTheStartNeverReachesItsFactory() {
    try (final MockedConstruction<CefBrowserPlayer> players = Mockito.mockConstruction(CefBrowserPlayer.class)) {
      final AssertionError failure = assertThrows(AssertionError.class, () -> this.leftInterruptedByAStartThat(FakeSession::new));
      final boolean namesTheHelperFactory = failure.getMessage().contains(HELPER_FACTORY_TEXT);
      assertTrue(namesTheHelperFactory);

      final int playerCount = players.constructed().size();
      assertEquals(1, playerCount);
    }
  }

  @Test
  void shouldUseTheResultOfTheBoundedWaitInTheInterruptFixture() throws Exception {
    final MockedConstruction.MockInitializer<CountDownLatch> failingAwaits = (latch, _) -> this.failEveryAwait(latch);
    try (
      final MockedConstruction<CountDownLatch> latches = Mockito.mockConstruction(CountDownLatch.class, failingAwaits);
      final MockedConstruction<CefBrowserPlayer> players = Mockito.mockConstruction(CefBrowserPlayer.class)
    ) {
      final AssertionError failure = assertThrows(AssertionError.class, () -> this.leftInterruptedByAStartThat(FakeSession::new));
      final boolean namesTheHelperFactory = failure.getMessage().contains(HELPER_FACTORY_TEXT);
      assertTrue(namesTheHelperFactory, failure.getMessage());

      final int latchCount = latches.constructed().size();
      assertEquals(1, latchCount);

      final int playerCount = players.constructed().size();
      assertEquals(1, playerCount);
    }
  }

  private void failEveryAwait(final CountDownLatch latch) throws InterruptedException {
    Mockito.doThrow(new AssertionError("Unbounded fixture wait!")).when(latch).await();
  }

  @Test
  void shouldTakeTheInterruptBackWhenTheStartEndsWellDespiteTheRelease() throws Exception {
    final boolean leftInterrupted = this.leftInterruptedByAStartThat(FakeSession::new);
    assertFalse(leftInterrupted, RELEASE_INTERRUPT_MESSAGE);
  }

  @Test
  void shouldTakeTheInterruptBackWhenTheStartFailsAnotherWayAfterTheRelease() throws Exception {
    final boolean leftInterrupted = this.leftInterruptedByAStartThat(this::breakTheHelper);
    assertFalse(leftInterrupted, RELEASE_INTERRUPT_MESSAGE);
  }

  private BrowserSession breakTheHelper() {
    final String message = "The helper broke!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldKeepTheInterruptOfTheCallerWhenStartingAfterTheRelease() {
    final boolean released = this.player.release();
    assertTrue(released);

    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      final boolean startedAfterRelease = this.player.start(SOURCE);
      assertFalse(startedAfterRelease, RELEASED_RESTART_FAILURE_MESSAGE);

      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, "the release interrupted no start, so the interrupt is the caller's");
    } finally {
      Thread.interrupted();
    }

    final boolean empty = this.sessions.isEmpty();
    assertTrue(empty, "no helper was started");
  }

  @Test
  void shouldKeepTheInterruptOfACallerInterruptedBeforeAReleaseStopsItsStart() {
    final AtomicReference<CefBrowserPlayer> self = new AtomicReference<>();
    final CefBrowserPlayer releasing = new CefBrowserPlayer(BrowserOptions.DEFAULT, (_, _, _) -> this.releaseAndFailTheStart(self));
    self.set(releasing);
    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      final boolean startedAfterRelease = releasing.start(SOURCE);
      assertFalse(startedAfterRelease, RELEASED_START_FAILURE_MESSAGE);

      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, "the caller was interrupted before the release, so the interrupt is its own");
    } finally {
      Thread.interrupted();
    }
  }

  private BrowserSession releaseAndFailTheStart(final AtomicReference<CefBrowserPlayer> self) {
    final boolean released = self.get().release();
    assertTrue(released);

    throw new BrowserException(INTERRUPTED_START_MESSAGE);
  }

  @Test
  void shouldCloseTheSessionOnReleaseAndNeverStartAgain() {
    this.player.start(SOURCE);
    final boolean released = this.player.release();
    assertTrue(released);

    final boolean playing = this.player.isPlaying();
    assertFalse(playing);

    final FakeSession session = this.sessions.getFirst();
    assertEquals(1, session.closed);

    final boolean releasedAgain = this.player.release();
    assertFalse(releasedAgain);

    final boolean startedAfterRelease = this.player.start(SOURCE);
    assertFalse(startedAfterRelease);

    this.listener.onFrame(this.frame());
    final int processedCount = this.processed.size();
    assertEquals(0, processedCount);
  }

  @Test
  void shouldTurnClicksIntoMovesPressesAndReleases() {
    this.player.start(SOURCE);
    this.player.sendMouseEvent(MouseClick.LEFT, 10, 20);
    this.player.sendMouseEvent(MouseClick.RIGHT, 1, 2);
    this.player.sendMouseEvent(MouseClick.DOUBLE, 3, 4);
    this.player.sendMouseEvent(MouseClick.HOLD, 5, 6);
    this.player.sendMouseEvent(MouseClick.RELEASE, 7, 8);
    final FakeSession session = this.sessions.getFirst();
    final List<String> expectedMouseEvents = List.of(
      "mouse 0 10,20 b0 c0 d0,0",
      "mouse 1 10,20 b0 c1 d0,0",
      "mouse 2 10,20 b0 c1 d0,0",
      "mouse 0 1,2 b0 c0 d0,0",
      "mouse 1 1,2 b2 c1 d0,0",
      "mouse 2 1,2 b2 c1 d0,0",
      "mouse 0 3,4 b0 c0 d0,0",
      "mouse 1 3,4 b0 c1 d0,0",
      "mouse 2 3,4 b0 c1 d0,0",
      "mouse 1 3,4 b0 c2 d0,0",
      "mouse 2 3,4 b0 c2 d0,0",
      "mouse 0 5,6 b0 c0 d0,0",
      "mouse 1 5,6 b0 c1 d0,0",
      "mouse 0 7,8 b0 c0 d0,0",
      "mouse 2 7,8 b0 c1 d0,0"
    );
    assertEquals(expectedMouseEvents, session.sent);
  }

  @Test
  void shouldClampPositionsToThePageAndWheelDistancesToTheProtocol() {
    this.player.start(SOURCE);
    this.player.moveMouse(-5, 500);
    this.player.scroll(1000, -1, 100_000, -100_000);
    final FakeSession session = this.sessions.getFirst();
    final List<String> expectedMouseEvents = List.of("mouse 0 0,49 b0 c0 d0,0", "mouse 3 99,0 b0 c0 d32767,-32768");
    assertEquals(expectedMouseEvents, session.sent);

    final int clampedX = this.player.clamp(0, 0)[0];
    assertEquals(0, clampedX);
  }

  @Test
  void shouldClampToTheFirstPixelWithoutAPage() {
    final int[] clamped = this.player.clamp(-3, 7);
    final int[] expectedClampedPosition = new int[] { 0, 0 };
    assertArrayEquals(expectedClampedPosition, clamped);
  }

  @Test
  void shouldPressKeyNamesAndTypeOtherText() {
    this.player.start(SOURCE);
    this.player.sendKeyEvent("Enter");
    this.player.sendKeyEvent("PageDown");
    this.player.sendKeyEvent("hello");
    final FakeSession session = this.sessions.getFirst();
    final List<String> expectedKeyEvents = List.of("key 0 Enter", "key 0 PageDown", "key 1 hello");
    assertEquals(expectedKeyEvents, session.sent);
  }

  @Test
  void shouldIgnoreInputWhileThePlayerIsNotPlaying() {
    this.player.moveMouse(1, 1);
    this.player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    this.player.scroll(1, 1, 0, 10);
    this.player.sendKeyEvent(INPUT_KEY);
    this.player.start(SOURCE);
    this.listener.onEnded(END_REASON, new IllegalStateException(CRASH_REASON));
    this.player.sendKeyEvent(INPUT_KEY);
    final FakeSession session = this.sessions.getFirst();
    assertEquals(List.of(), session.sent);
  }

  @Test
  void shouldReportDroppedInput() {
    this.player.start(SOURCE);
    this.sessions.getFirst().accepting = false;
    this.player.sendKeyEvent(INPUT_KEY);
    this.player.scroll(1, 1, 0, 1);
    final List<String> expectedReports = List.of(INPUT_QUEUE_FULL_MESSAGE, INPUT_QUEUE_FULL_MESSAGE);
    assertEquals(expectedReports, this.reports);

    this.player.sendMouseEvent(MouseClick.HOLD, 1, 1);
    final int reportCount = this.reports.size();
    assertEquals(4, reportCount, this.reports.toString());
  }

  @Test
  void shouldReportDroppedInputWithinTheBudgetOfTheLog() {
    this.player.start(SOURCE);
    this.sessions.getFirst().accepting = false;
    for (int index = 0; index < DROPPED_INPUT_COUNT; index++) {
      this.player.sendKeyEvent(INPUT_KEY);
    }

    final int reportCount = this.reports.size();
    assertEquals(LogBudget.BURST, reportCount, "a burst of reports, then none");

    this.now.addAndGet(LogBudget.REFILL_NANOSECONDS);
    this.player.sendKeyEvent(INPUT_KEY);
    final List<String> later = this.reports.subList(LogBudget.BURST, this.reports.size());
    final List<String> expectedLaterReports = List.of(
      "Browser input queue is full. The browser dropped " + (DROPPED_INPUT_COUNT - LogBudget.BURST) + " more inputs!",
      INPUT_QUEUE_FULL_MESSAGE
    );
    assertEquals(expectedLaterReports, later);
  }

  @Test
  void shouldCheckArguments() {
    assertThrows(NullPointerException.class, () -> this.player.start(null));
    assertThrows(NullPointerException.class, () -> this.player.sendMouseEvent(null, 1, 1));
    assertThrows(NullPointerException.class, () -> this.player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> this.player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> BrowserPlayer.create(null));
  }

  @Test
  void shouldReadTheExceptionHandlerBack() {
    final boolean handlerPresent = this.player.getExceptionHandler() != null;
    assertTrue(handlerPresent);

    final VideoAttachableCallback callback = this.player.getVideoAttachableCallback();
    final VideoAttachableCallback secondCallback = this.player.getVideoAttachableCallback();
    assertSame(callback, secondCallback);
  }

  @Test
  void shouldUseTheExecutorWhenStartingAsynchronously() throws Exception {
    final ExecutorService executor = Mockito.mock(ExecutorService.class);
    final List<Runnable> queued = new ArrayList<>();
    Mockito.doAnswer(invocation -> this.queueTheTask(queued, invocation))
      .when(executor)
      .execute(Mockito.any());
    try {
      final CompletableFuture<Boolean> started = this.player.startAsync(SOURCE, executor);
      final boolean done = started.isDone();
      assertFalse(done, "startup must wait for its supplied executor");

      final int queuedCount = queued.size();
      assertEquals(1, queuedCount);

      final boolean empty = this.sessions.isEmpty();
      assertTrue(empty, "no session may start before the executor runs");

      queued.getFirst().run();
      final Boolean startSucceeded = started.get(10, TimeUnit.SECONDS);
      assertTrue(startSucceeded);
    } finally {
      this.player.release();
    }

    final CefBrowserPlayer other = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> new FakeSession());
    final Boolean otherStarted = other.startAsync(SOURCE).get(10, TimeUnit.SECONDS);
    assertTrue(otherStarted);
    assertThrows(NullPointerException.class, () -> other.startAsync(null));
    assertThrows(NullPointerException.class, () -> other.startAsync(SOURCE, null));
  }

  private @Nullable Object queueTheTask(final List<Runnable> queued, final InvocationOnMock invocation) {
    queued.add(invocation.getArgument(0));

    return null;
  }

  @Test
  void shouldCloseAPictureOnceThePipelineHasIt() {
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final ClosingProbe probe = new ClosingProbe();
    this.listener.onFrame(probe.wrap(this.frame()));
    final int processedCount = this.processed.size();
    assertEquals(1, processedCount);

    final boolean closed = probe.isClosed();
    assertTrue(closed);
  }

  @Test
  void shouldCloseAPictureOfTheStartWhenItIsDropped() {
    final ClosingProbe probe = new ClosingProbe();
    final CefBrowserPlayer.SessionFactory factory = (_, _, listener) -> this.openSessionAfterAFrame(listener, probe.wrap(this.frame()));
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    final boolean started = early.start(SOURCE);
    assertTrue(started);

    final boolean closed = probe.isClosed();
    assertTrue(closed);
  }

  @Test
  void shouldUseAPlayerReleasedOnAnotherThreadOnThisOne() {
    final boolean started = this.player.start(SOURCE);
    assertTrue(started);

    final CompletableFuture<Boolean> releasing = CompletableFuture.supplyAsync(this.player::release);
    final boolean released = releasing.join();
    assertTrue(released);

    final boolean restarted = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> this.player.start(SOURCE));
    assertFalse(restarted);
  }

  @Test
  void shouldDownloadNothingWhileTheModuleIsStopped() {
    final List<URI> downloads = new CopyOnWriteArrayList<>();
    final JcefNatives recording = new JcefNatives(
      Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY)).resolve(MISSING_NATIVES_PREFIX + System.nanoTime()),
      (uri, _, _, _) -> this.recordAndFailDownload(downloads, uri),
      UNREACHABLE_REPOSITORY,
      new ArchiveExtractor()
    );
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(recording, List.of());
    HelperProcesses.closeAll();
    try {
      final BrowserException failure = assertThrows(BrowserException.class, () -> this.openWithARecordingListener(factory));
      final String message = failure.getMessage();
      assertEquals("The browser module is stopped!", message);
    } finally {
      HelperProcesses.open();
    }

    assertEquals(List.of(), downloads);
  }

  private void recordAndFailDownload(final List<URI> downloads, final URI uri) throws IOException {
    downloads.add(uri);
    throw new IOException(OFFLINE_MESSAGE);
  }

  private BrowserSession openWithARecordingListener(final CefBrowserPlayer.HelperSessionFactory factory) {
    return factory.open(SOURCE, BrowserOptions.DEFAULT, new HelperSessionTest.RecordingListener());
  }

  @Test
  void shouldMakeTheDefaultPlayerAJcefPlayer() {
    final BrowserPlayer defaultPlayer = BrowserPlayer.create();
    assertInstanceOf(CefBrowserPlayer.class, defaultPlayer);

    final BrowserPlayer configuredPlayer = BrowserPlayer.create(BrowserOptions.builder().frameRate(10).build());
    assertInstanceOf(CefBrowserPlayer.class, configuredPlayer);
  }

  @Test
  void shouldFailTheDefaultSessionsWhenTheNativesCannotBeInstalled() {
    final JcefNatives broken = new JcefNatives(
      Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY)).resolve(MISSING_NATIVES_PREFIX + System.nanoTime()),
      (_, _, _, _) -> this.failDownload(),
      UNREACHABLE_REPOSITORY,
      new ArchiveExtractor()
    );
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(broken, List.of());
    final CefBrowserPlayer offline = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    final BrowserException failure = assertThrows(BrowserException.class, () -> offline.start(SOURCE));
    final boolean namesTheInstallFailure = failure.getMessage().startsWith("The browser cannot be installed");
    assertTrue(namesTheInstallFailure, failure.getMessage());

    final boolean playing = offline.isPlaying();
    assertFalse(playing);

    final Throwable cause = failure.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  private void failDownload() throws IOException {
    throw new IOException(OFFLINE_MESSAGE);
  }

  @Test
  void shouldOpenAHelperSessionThroughTheLauncherOfTheFactory(@TempDir final Path folder) throws IOException {
    final boolean supported = BrowserPlayer.isSupported();
    assumeTrue(supported, "This machine has no CEF build!");

    final JcefNatives.NativePlatform platform = JcefNatives.detectCurrent();
    final String identifier = platform.getIdentifier();
    final Path installation = folder.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + NAME_SEPARATOR + identifier);
    Files.createDirectories(installation);
    Files.createFile(installation.resolve(JcefNatives.INSTALL_MARKER));

    final JcefNatives installed = new JcefNatives(folder, (_, _, _, _) -> this.failDownload(), UNREACHABLE_REPOSITORY, new ArchiveExtractor());
    final LinuxLibraries libraries = new LinuxLibraries(folder, (_, _, _, _) -> this.failDownload(), List.of(), List.of());
    final HelperLauncher launcher = HelperSessionTest.launcher(ScriptedEngine.class.getName(), HELPER_START_TIMEOUT_MILLISECONDS);
    final CefBrowserPlayer.HelperSessionFactory factory = new CefBrowserPlayer.HelperSessionFactory(installed, libraries, launcher);
    try (final BrowserSession session = this.openWithARecordingListener(factory)) {
      assertInstanceOf(HelperSession.class, session);
    }
  }

  @Test
  void shouldTellWhetherTheBrowserStartedAfterAnAsynchronousStart() {
    final boolean released = this.player.release();
    assertTrue(released);

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Boolean startedAfterRelease = this.player.startAsync(SOURCE, executor).join();
      assertFalse(startedAfterRelease);
    } finally {
      executor.shutdownNow();
    }
  }

  private static final class FakeSession implements BrowserSession {

    private final List<String> sent = new ArrayList<>();

    private boolean accepting = true;
    private int requested;
    private int closed;

    @Override
    public boolean sendMouse(final MouseInput mouse) {
      this.sent.add(
        "mouse " +
          mouse.getAction() +
          INPUT_SEPARATOR +
          mouse.getX() +
          "," +
          mouse.getY() +
          " b" +
          mouse.getButton() +
          " c" +
          mouse.getClickCount() +
          " d" +
          mouse.getDeltaX() +
          "," +
          mouse.getDeltaY()
      );

      return this.accepting;
    }

    @Override
    public boolean sendKey(final int action, final String value) {
      this.sent.add("key " + action + INPUT_SEPARATOR + value);

      return this.accepting;
    }

    @Override
    public void requestFrame() {
      this.requested++;
    }

    @Override
    public void close() {
      this.closed++;
    }
  }

  private static final class ClosingProbe {

    private final AtomicBoolean closed = new AtomicBoolean();

    ImageBuffer wrap(final ImageBuffer real) {
      return (ImageBuffer) Proxy.newProxyInstance(
        ImageBuffer.class.getClassLoader(),
        new Class<?>[] { ImageBuffer.class },
        (_, method, arguments) -> this.recordCloseAndInvoke(real, method, arguments)
      );
    }

    private @Nullable Object recordCloseAndInvoke(final ImageBuffer real, final Method method, final @Nullable Object[] arguments)
      throws Throwable {
      if (method.getName().equals("close") && method.getParameterCount() == 0) {
        this.closed.set(true);
      }

      try {
        return method.invoke(real, arguments);
      } catch (final InvocationTargetException exception) {
        throw exception.getCause();
      }
    }

    private boolean isClosed() {
      return this.closed.get();
    }
  }
}

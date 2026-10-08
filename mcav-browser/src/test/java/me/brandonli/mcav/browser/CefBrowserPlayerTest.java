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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
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
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

class CefBrowserPlayerTest {

  private static final BrowserSource SOURCE = BrowserSource.uri(URI.create("https://example.com/"), 100, 50, 1);

  private final List<FakeSession> sessions = new ArrayList<>();
  private final List<String> reports = new ArrayList<>();
  private final List<ImageBuffer> processed = new ArrayList<>();
  private BrowserSession.Listener listener;
  private RuntimeException startFailure;
  private final AtomicLong now = new AtomicLong();

  private final CefBrowserPlayer player = new CefBrowserPlayer(
    BrowserOptions.DEFAULT,
    (source, options, sessionListener) -> {
      if (this.startFailure != null) {
        throw this.startFailure;
      }
      this.listener = sessionListener;
      final FakeSession session = new FakeSession();
      this.sessions.add(session);
      return session;
    },
    this.now::get
  );

  CefBrowserPlayerTest() {
    this.player.setExceptionHandler((message, error) -> this.reports.add(message + ": " + error.getMessage()));
    this.attachRecorder(this.player);
  }

  private void attachRecorder(final CefBrowserPlayer target) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((samples, metadata) -> {
      this.processed.add(samples);
      return false;
    });
    final VideoPipelineStep pipeline = builder.build();
    target.getVideoAttachableCallback().attach(pipeline);
  }

  /**
   * A session that records what the player sends it.
   */
  private static final class FakeSession implements BrowserSession {

    final List<String> sent = new ArrayList<>();
    boolean accepting = true;
    int requested;
    int closed;

    @Override
    public boolean sendMouse(final MouseInput mouse) {
      this.sent.add(
        "mouse " +
          mouse.getAction() +
          " " +
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
      this.sent.add("key " + action + " " + value);
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

  private static ImageBuffer frame() {
    return ImageBuffer.buffer(new int[100 * 50], 100, 50);
  }

  private List<byte[]> attachSoundRecorder() {
    final List<byte[]> heard = new CopyOnWriteArrayList<>();
    this.player.getAudioAttachableCallback().attach(
      AudioPipelineStep.of((samples, metadata) -> {
        final byte[] copy = new byte[samples.remaining()];
        samples.get(copy);
        heard.add(copy);
        return true;
      })
    );
    return heard;
  }

  @Test
  void theSoundOfTheCurrentSessionReachesTheAudioPipelineAndNoOtherSound() throws InterruptedException {
    final List<byte[]> heard = this.attachSoundRecorder();
    assertSame(this.player.getAudioAttachableCallback(), this.player.getAudioAttachableCallback());
    assertTrue(this.player.start(SOURCE));
    final BrowserSession.Listener first = this.listener;
    first.onAudio(new byte[] { 1, 0, 1, 0 });
    Await.until("the sound of the page", () -> heard.size() == 1);
    assertArrayEquals(new byte[] { 1, 0, 1, 0 }, heard.getFirst());
    assertTrue(this.player.release());
    first.onAudio(new byte[] { 2, 0, 2, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 3, 0, 3, 0 });
    Thread.sleep(CefBrowserPlayer.MAX_QUEUED_AUDIO_MILLIS * 2L);
    assertEquals(1, heard.size());
  }

  @Test
  void soundBeforeTheSessionIsThePlayersOrOfAnOldSessionIsDropped() throws InterruptedException {
    final List<byte[]> heard = this.attachSoundRecorder();
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      sessionListener.onAudio(new byte[] { 9, 0, 9, 0 });
      this.listener = sessionListener;
      final FakeSession session = new FakeSession();
      this.sessions.add(session);
      return session;
    });
    final List<byte[]> earlyHeard = new CopyOnWriteArrayList<>();
    early.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, metadata) -> earlyHeard.add(new byte[0])));
    assertTrue(early.start(SOURCE));
    early.release();
    assertTrue(this.player.start(SOURCE));
    final FakeSession old = new FakeSession();
    this.player.deliverAudio(old, new byte[] { 4, 0, 4, 0 });
    this.listener.onAudio(new byte[] { 5, 0, 5, 0 });
    Await.until("the sound of the current session", () -> heard.size() == 1);
    Thread.sleep(CefBrowserPlayer.MAX_QUEUED_AUDIO_MILLIS * 2L);
    assertEquals(1, heard.size());
    assertEquals(5, heard.getFirst()[0]);
    assertEquals(List.of(), earlyHeard);
  }

  @Test
  void aStartedPlayerPlaysAndAsksForTheFirstPictureAgain() {
    assertFalse(this.player.isPlaying());
    assertTrue(this.player.start(SOURCE));
    assertTrue(this.player.isPlaying());
    assertEquals(1, this.sessions.getFirst().requested);
    assertFalse(this.player.start(SOURCE), "a playing player does not start again");
    assertEquals(1, this.sessions.size());
  }

  @Test
  void framesRunThroughThePipelineAndAreClosed() {
    this.player.start(SOURCE);
    final ImageBuffer frame = frame();
    this.listener.onFrame(frame);
    assertEquals(1, this.processed.size());
    assertSame(frame, this.processed.getFirst());
  }

  @Test
  void framesOfTheStartBeforeTheSessionIsKnownAreDropped() {
    final ImageBuffer early = frame();
    final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      sessionListener.onFrame(early);
      return new FakeSession();
    });
    this.attachRecorder(player);
    assertTrue(player.start(SOURCE));
    assertTrue(player.isPlaying());
    assertEquals(List.of(), this.processed);
  }

  @Test
  void aReleaseStopsAStartInProgressInsteadOfWaitingForIt() throws Exception {
    final CountDownLatch opening = new CountDownLatch(1);
    final CefBrowserPlayer slow = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      opening.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new PlayerException("Interrupted while starting the browser", exception);
      }
      return new FakeSession();
    });
    final ExecutorService starter = Executors.newSingleThreadExecutor();
    try {
      // read on the thread that called start, in the same task: the executor clears a worker's interrupt between tasks
      final AtomicBoolean leftInterrupted = new AtomicBoolean(true);
      final Future<Boolean> started = starter.submit(() -> {
        final boolean result = slow.start(SOURCE);
        leftInterrupted.set(Thread.currentThread().isInterrupted());
        return result;
      });
      opening.await();
      assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(5), slow::release), "the release does not wait for the start");
      assertFalse(started.get(5, TimeUnit.SECONDS), "the start of a released player fails");
      assertFalse(slow.isPlaying());
      assertFalse(leftInterrupted.get(), "the release's interrupt is the player's own, not left to the thread that called start");
      Thread.currentThread().interrupt();
      try {
        assertFalse(slow.start(SOURCE), "a released player never starts");
        assertTrue(Thread.currentThread().isInterrupted(), "an interrupt of the caller's own is not the player's to take");
      } finally {
        Thread.interrupted();
      }
    } finally {
      starter.shutdownNow();
    }
  }

  @Test
  void anEndOfTheHelperWhileTheSessionStartsFailsTheStartAndIsReported() {
    final List<FakeSession> opened = new ArrayList<>();
    final List<String> reports = new ArrayList<>();
    final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      final FakeSession session = new FakeSession();
      if (opened.isEmpty()) {
        sessionListener.onEnded("The browser helper exited", new IllegalStateException("exit 1"));
      }
      opened.add(session);
      return session;
    });
    player.setExceptionHandler((message, error) -> reports.add(message));
    assertFalse(player.start(SOURCE));
    assertFalse(player.isPlaying());
    assertEquals(List.of("The browser helper exited"), reports);
    assertTrue(player.start(SOURCE), "a failed player starts again");
    assertTrue(player.isPlaying());
    assertEquals(1, opened.getFirst().closed, "the dead session is closed");
    assertEquals(List.of("The browser helper exited"), reports);
  }

  private static Set<Thread> audioThreads() {
    final Set<Thread> threads = new HashSet<>();
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().equals(DelayedAudioOutput.THREAD_NAME)) {
        threads.add(thread);
      }
    }
    return threads;
  }

  @Test
  void aReleaseEndsTheAudioThreadAndNeitherAPlayingNorAReleasedPlayerStarts() {
    final Set<Thread> before = audioThreads();
    assertTrue(this.player.start(SOURCE));
    assertFalse(before.containsAll(audioThreads()), "a started player hands its sound over on a thread of its own");
    assertFalse(this.player.start(SOURCE), "a playing player does not start twice");
    assertTrue(this.player.release());
    Await.until("the audio thread of the released player ended", () -> before.containsAll(audioThreads()));
    assertFalse(this.player.start(SOURCE), "a released player does not start again");
    assertEquals(1, this.sessions.size());
  }

  @Test
  void aHelperThatEndsTakesTheSoundOfItsSessionAlongAndLeavesNoThread() {
    final Set<Thread> before = audioThreads();
    final List<byte[]> heard = this.attachSoundRecorder();
    assertTrue(this.player.start(SOURCE));
    final BrowserSession.Listener ended = this.listener;
    ended.onEnded("gone", new IllegalStateException("crash"));
    Await.until("the audio thread of the failed session ended", () -> before.containsAll(audioThreads()));
    ended.onAudio(new byte[] { 6, 0, 6, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 7, 0, 7, 0 });
    assertEquals(List.of(), heard);
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      sessionListener.onEnded("The browser helper exited", new IllegalStateException("exit 1"));
      return new FakeSession();
    });
    early.setExceptionHandler((message, error) -> {});
    assertFalse(early.start(SOURCE));
    Await.until("the audio thread of the failed start ended", () -> before.containsAll(audioThreads()));
    early.release();
  }

  @Test
  void aFailingPipelineIsReportedAndTheFrameStillClosed() {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((samples, metadata) -> {
      throw new IllegalStateException("filter broke");
    });
    this.player.getVideoAttachableCallback().attach(builder.build());
    this.player.start(SOURCE);
    final ClosingProbe probe = new ClosingProbe();
    try (final ImageBuffer original = frame()) {
      this.listener.onFrame(probe.wrap(original));
      assertTrue(probe.isClosed(), "a failed pipeline still returns the frame's ownership");
    }
    assertEquals(List.of("Failed to process a browser frame: filter broke"), this.reports);
  }

  @Test
  void aFatalErrorOfTheVirtualMachineIsThrown() {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((samples, metadata) -> {
      throw new OutOfMemoryError("full");
    });
    this.player.getVideoAttachableCallback().attach(builder.build());
    this.player.start(SOURCE);
    final ClosingProbe probe = new ClosingProbe();
    try (final ImageBuffer original = frame()) {
      assertThrows(OutOfMemoryError.class, () -> this.listener.onFrame(probe.wrap(original)));
      assertTrue(probe.isClosed(), "a fatal pipeline failure still returns the frame's ownership");
    }
  }

  @Test
  void framesAndEndsOfAReplacedSessionAreIgnored() {
    this.player.start(SOURCE);
    final BrowserSession.Listener first = this.listener;
    first.onEnded("gone", new IllegalStateException("crash"));
    assertFalse(this.player.isPlaying());
    assertEquals(List.of("gone: crash"), this.reports);
    assertTrue(this.player.start(SOURCE));
    assertEquals(1, this.sessions.getFirst().closed);
    first.onFrame(frame());
    first.onEnded("late", new IllegalStateException("late"));
    assertEquals(0, this.processed.size());
    assertEquals(1, this.reports.size());
    assertTrue(this.player.isPlaying());
  }

  @Test
  void aFrameOfTheCurrentSessionAfterItFailedIsDropped() {
    this.player.start(SOURCE);
    this.listener.onEnded("gone", new IllegalStateException("crash"));
    final ImageBuffer late = frame();
    this.listener.onFrame(late);
    assertEquals(0, this.processed.size(), "a failed player shows nothing more");
  }

  @Test
  void anEndIsReportedOnce() {
    this.player.start(SOURCE);
    this.listener.onEnded("gone", new IllegalStateException("one"));
    this.listener.onEnded("gone again", new IllegalStateException("two"));
    assertEquals(List.of("gone: one"), this.reports);
  }

  @Test
  void aFailedStartLeavesThePlayerIdle() {
    this.startFailure = new PlayerException("no browser here");
    assertThrows(PlayerException.class, () -> this.player.start(SOURCE));
    assertFalse(this.player.isPlaying());
    this.startFailure = null;
    assertTrue(this.player.start(SOURCE));
  }

  @Test
  void aStartThatIsRefusedKeepsTheInterruptOfItsCaller() {
    assertTrue(this.player.start(SOURCE));
    Thread.currentThread().interrupt();
    try {
      assertFalse(this.player.start(SOURCE), "a playing player does not start twice");
      assertTrue(Thread.currentThread().isInterrupted(), "an interrupt of the caller's own is not the player's to take");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aStartThatFailsKeepsTheInterruptOfItsCaller() {
    this.startFailure = new PlayerException("no browser here");
    Thread.currentThread().interrupt();
    try {
      assertThrows(PlayerException.class, () -> this.player.start(SOURCE));
      assertTrue(Thread.currentThread().isInterrupted(), "an interrupt of the caller's own is not the player's to take");
    } finally {
      Thread.interrupted();
      this.startFailure = null;
    }
  }

  /**
   * Starts a player whose helper is reached by a release while it starts, and ends as the given ending does, then
   * releases the player. The helper notices the release's interrupt only once it is done.
   *
   * @return whether the starting thread was left interrupted once the start ended
   */
  private static boolean leftInterruptedByAStartThat(final Supplier<BrowserSession> ending) throws Exception {
    final CountDownLatch opening = new CountDownLatch(1);
    final CefBrowserPlayer late = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      opening.countDown();
      while (!Thread.currentThread().isInterrupted()) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      return ending.get();
    });
    final ExecutorService starter = Executors.newSingleThreadExecutor();
    try {
      // read on the thread that called start, in the same task: the executor clears a worker's interrupt between tasks
      final Future<Boolean> leftInterrupted = starter.submit(() -> {
        try {
          late.start(SOURCE);
        } catch (final IllegalStateException failure) {
          // the helper's own failure, which the start passes on
        }
        return Thread.currentThread().isInterrupted();
      });
      assertTrue(opening.await(5, TimeUnit.SECONDS), "the helper factory must be entered before release");
      assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(5), late::release));
      final boolean left = leftInterrupted.get(5, TimeUnit.SECONDS);
      assertFalse(late.isPlaying(), "a released player plays nothing");
      return left;
    } finally {
      starter.shutdownNow();
      try {
        assertTrue(starter.awaitTermination(5, TimeUnit.SECONDS), "the fixture must stop its startup worker");
      } finally {
        assertTimeoutPreemptively(Duration.ofSeconds(5), late::release);
      }
    }
  }

  @Test
  void aStartThatNeverReachesItsFactoryStillEndsTheInterruptFixture() {
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
      try (final MockedConstruction<CefBrowserPlayer> players = Mockito.mockConstruction(CefBrowserPlayer.class)) {
        final AssertionError failure = assertThrows(AssertionError.class, () -> leftInterruptedByAStartThat(FakeSession::new));
        assertTrue(failure.getMessage().contains("the helper factory"));
        assertEquals(1, players.constructed().size());
      }
    });
  }

  @Test
  void theInterruptFixtureUsesTheResultOfItsBoundedWait() throws Exception {
    try (
      final MockedConstruction<CountDownLatch> latches = Mockito.mockConstruction(CountDownLatch.class, (latch, _) ->
        Mockito.doThrow(new AssertionError("unbounded fixture wait")).when(latch).await()
      );
      final MockedConstruction<CefBrowserPlayer> players = Mockito.mockConstruction(CefBrowserPlayer.class)
    ) {
      final AssertionError failure = assertThrows(AssertionError.class, () -> leftInterruptedByAStartThat(FakeSession::new));
      assertTrue(failure.getMessage().contains("the helper factory"), failure.getMessage());
      assertEquals(1, latches.constructed().size());
      assertEquals(1, players.constructed().size());
    }
  }

  @Test
  void aStartThatEndsWellDespiteTheReleaseTakesItsInterruptBack() throws Exception {
    assertFalse(leftInterruptedByAStartThat(FakeSession::new), "the release's interrupt is the player's own");
  }

  @Test
  void aStartThatFailsAnotherWayAfterTheReleaseTakesItsInterruptBack() throws Exception {
    assertFalse(
      leftInterruptedByAStartThat(() -> {
        throw new IllegalStateException("the helper broke");
      }),
      "the release's interrupt is the player's own"
    );
  }

  @Test
  void aStartAfterTheReleaseKeepsTheInterruptOfItsCaller() {
    assertTrue(this.player.release());
    Thread.currentThread().interrupt();
    try {
      assertFalse(this.player.start(SOURCE), "a released player never starts");
      assertTrue(Thread.currentThread().isInterrupted(), "the release interrupted no start, so the interrupt is the caller's");
    } finally {
      Thread.interrupted();
    }
    assertTrue(this.sessions.isEmpty(), "no helper was started");
  }

  @Test
  void aCallerInterruptedBeforeAReleaseStopsItsStartKeepsItsInterrupt() {
    final AtomicReference<CefBrowserPlayer> self = new AtomicReference<>();
    final CefBrowserPlayer releasing = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      assertTrue(self.get().release());
      throw new PlayerException("Interrupted while starting the browser");
    });
    self.set(releasing);
    Thread.currentThread().interrupt();
    try {
      assertFalse(releasing.start(SOURCE), "the start of a released player fails");
      assertTrue(Thread.currentThread().isInterrupted(), "the caller was interrupted before the release, so the interrupt is its own");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aReleasedPlayerClosesItsSessionAndNeverStartsAgain() {
    this.player.start(SOURCE);
    assertTrue(this.player.release());
    assertFalse(this.player.isPlaying());
    assertEquals(1, this.sessions.getFirst().closed);
    assertFalse(this.player.release());
    assertFalse(this.player.start(SOURCE));
    this.listener.onFrame(frame());
    assertEquals(0, this.processed.size());
  }

  @Test
  void clicksBecomeMovesPressesAndReleases() {
    this.player.start(SOURCE);
    this.player.sendMouseEvent(MouseClick.LEFT, 10, 20);
    this.player.sendMouseEvent(MouseClick.RIGHT, 1, 2);
    this.player.sendMouseEvent(MouseClick.DOUBLE, 3, 4);
    this.player.sendMouseEvent(MouseClick.HOLD, 5, 6);
    this.player.sendMouseEvent(MouseClick.RELEASE, 7, 8);
    assertEquals(
      List.of(
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
      ),
      this.sessions.getFirst().sent
    );
  }

  @Test
  void positionsAreClampedToThePageAndWheelDistancesToTheProtocol() {
    this.player.start(SOURCE);
    this.player.moveMouse(-5, 500);
    this.player.scroll(1000, -1, 100_000, -100_000);
    assertEquals(List.of("mouse 0 0,49 b0 c0 d0,0", "mouse 3 99,0 b0 c0 d32767,-32768"), this.sessions.getFirst().sent);
    assertEquals(0, this.player.clamp(0, 0)[0]);
  }

  @Test
  void aPlayerWithoutAPageClampsToItsFirstPixel() {
    assertArrayEquals(new int[] { 0, 0 }, this.player.clamp(-3, 7));
  }

  @Test
  void keyNamesArePressedAndOtherTextIsTyped() {
    this.player.start(SOURCE);
    this.player.sendKeyEvent("Enter");
    this.player.sendKeyEvent("PageDown");
    this.player.sendKeyEvent("hello");
    assertEquals(List.of("key 0 Enter", "key 0 PageDown", "key 1 hello"), this.sessions.getFirst().sent);
  }

  @Test
  void inputIsIgnoredWhileThePlayerIsNotPlaying() {
    this.player.moveMouse(1, 1);
    this.player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    this.player.scroll(1, 1, 0, 10);
    this.player.sendKeyEvent("x");
    this.player.start(SOURCE);
    this.listener.onEnded("gone", new IllegalStateException("crash"));
    this.player.sendKeyEvent("x");
    assertEquals(List.of(), this.sessions.getFirst().sent);
  }

  @Test
  void droppedInputIsReported() {
    this.player.start(SOURCE);
    this.sessions.getFirst().accepting = false;
    this.player.sendKeyEvent("x");
    this.player.scroll(1, 1, 0, 1);
    assertEquals(
      List.of(
        "Browser input queue is full: The browser input backlog is full",
        "Browser input queue is full: The browser input backlog is full"
      ),
      this.reports
    );
    this.player.sendMouseEvent(MouseClick.HOLD, 1, 1);
    assertEquals(4, this.reports.size(), this.reports.toString());
  }

  @Test
  void droppedInputIsReportedWithinTheBudgetOfTheLog() {
    this.player.start(SOURCE);
    this.sessions.getFirst().accepting = false;
    for (int index = 0; index < 500; index++) {
      this.player.sendKeyEvent("x");
    }
    assertEquals(LogBudget.BURST, this.reports.size(), "a burst of reports, then none");
    this.now.addAndGet(LogBudget.REFILL_NANOS);
    this.player.sendKeyEvent("x");
    final List<String> later = this.reports.subList(LogBudget.BURST, this.reports.size());
    assertEquals(
      List.of(
        "Browser input queue is full: " + (500 - LogBudget.BURST) + " more inputs were dropped",
        "Browser input queue is full: The browser input backlog is full"
      ),
      later
    );
  }

  @Test
  void argumentsAreChecked() {
    assertThrows(NullPointerException.class, () -> this.player.start(null));
    assertThrows(NullPointerException.class, () -> this.player.sendMouseEvent(null, 1, 1));
    assertThrows(NullPointerException.class, () -> this.player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> this.player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> BrowserPlayer.create(null));
  }

  @Test
  void theExceptionHandlerCanBeReadBack() {
    assertTrue(this.player.getExceptionHandler() != null);
    final VideoAttachableCallback callback = this.player.getVideoAttachableCallback();
    assertSame(callback, this.player.getVideoAttachableCallback());
  }

  @Test
  void startingAsynchronouslyUsesTheExecutor() throws Exception {
    final ExecutorService executor = Mockito.mock(ExecutorService.class);
    final List<Runnable> queued = new ArrayList<>();
    Mockito.doAnswer(invocation -> {
      queued.add(invocation.getArgument(0));
      return null;
    })
      .when(executor)
      .execute(Mockito.any());
    try {
      final CompletableFuture<Boolean> started = this.player.startAsync(SOURCE, executor);
      assertFalse(started.isDone(), "startup must wait for its supplied executor");
      assertEquals(1, queued.size());
      assertTrue(this.sessions.isEmpty(), "no session may start before the executor runs");
      queued.getFirst().run();
      assertTrue(started.get(10, TimeUnit.SECONDS));
    } finally {
      this.player.release();
    }
    final CefBrowserPlayer other = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> new FakeSession());
    assertTrue(other.startAsync(SOURCE).get(10, TimeUnit.SECONDS));
    assertThrows(NullPointerException.class, () -> other.startAsync(null));
    assertThrows(NullPointerException.class, () -> other.startAsync(SOURCE, null));
  }

  /**
   * Wraps a picture and remembers whether it was closed.
   */
  private static final class ClosingProbe {

    private final AtomicBoolean closed = new AtomicBoolean();

    ImageBuffer wrap(final ImageBuffer real) {
      return (ImageBuffer) Proxy.newProxyInstance(
        ImageBuffer.class.getClassLoader(),
        new Class<?>[] { ImageBuffer.class },
        (proxy, method, arguments) -> {
          if (method.getName().equals("close") && method.getParameterCount() == 0) {
            this.closed.set(true);
          }
          try {
            return method.invoke(real, arguments);
          } catch (final InvocationTargetException exception) {
            throw exception.getCause();
          }
        }
      );
    }

    private boolean isClosed() {
      return this.closed.get();
    }
  }

  @Test
  void aPictureIsClosedOnceThePipelineHasIt() {
    assertTrue(this.player.start(SOURCE));
    final ClosingProbe probe = new ClosingProbe();
    this.listener.onFrame(probe.wrap(frame()));
    assertEquals(1, this.processed.size());
    assertTrue(probe.isClosed());
  }

  @Test
  void aPictureOfTheStartIsClosedWhenItIsDropped() {
    final ClosingProbe probe = new ClosingProbe();
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      sessionListener.onFrame(probe.wrap(frame()));
      return new FakeSession();
    });
    assertTrue(early.start(SOURCE));
    assertTrue(probe.isClosed());
  }

  @Test
  void aPlayerReleasedOnAnotherThreadCanBeUsedOnThisOne() {
    assertTrue(this.player.start(SOURCE));
    CompletableFuture.runAsync(() -> assertTrue(this.player.release())).join();
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertFalse(this.player.start(SOURCE)));
  }

  @Test
  void aStoppedModuleDownloadsNothing() {
    final List<URI> downloads = new CopyOnWriteArrayList<>();
    final JcefNatives recording = new JcefNatives(
      Path.of(System.getProperty("java.io.tmpdir")).resolve("mcav-no-natives-" + System.nanoTime()),
      (uri, destination, sha256, size) -> {
        downloads.add(uri);
        throw new IOException("offline");
      },
      "https://unreachable.test/",
      new ArchiveExtractor()
    );
    final CefBrowserPlayer.DefaultSessionFactory factory = new CefBrowserPlayer.DefaultSessionFactory(recording, List.of());
    HelperProcesses.closeAll();
    try {
      final PlayerException failure = assertThrows(PlayerException.class, () ->
        factory.open(SOURCE, BrowserOptions.DEFAULT, new HelperSessionTest.RecordingListener())
      );
      assertEquals("The browser module is stopped", failure.getMessage());
    } finally {
      HelperProcesses.open();
    }
    assertEquals(List.of(), downloads);
  }

  @Test
  void theDefaultPlayerIsAJcefPlayer() {
    assertInstanceOf(CefBrowserPlayer.class, BrowserPlayer.create());
    assertInstanceOf(CefBrowserPlayer.class, BrowserPlayer.create(BrowserOptions.builder().frameRate(10).build()));
  }

  @Test
  void theDefaultSessionsFailWhenTheNativesCannotBeInstalled() {
    final JcefNatives broken = new JcefNatives(
      Path.of(System.getProperty("java.io.tmpdir")).resolve("mcav-no-natives-" + System.nanoTime()),
      (uri, destination, sha256, size) -> {
        throw new IOException("offline");
      },
      "https://unreachable.test/",
      new ArchiveExtractor()
    );
    final CefBrowserPlayer.DefaultSessionFactory factory = new CefBrowserPlayer.DefaultSessionFactory(broken, List.of());
    final CefBrowserPlayer offline = new CefBrowserPlayer(BrowserOptions.DEFAULT, factory);
    final PlayerException failure = assertThrows(PlayerException.class, () -> offline.start(SOURCE));
    assertTrue(failure.getMessage().startsWith("The browser cannot be installed"), failure.getMessage());
    assertFalse(offline.isPlaying());
    assertInstanceOf(IOException.class, failure.getCause());
  }

  @Test
  void anAsynchronousStartTellsWhetherTheBrowserStarted() {
    assertTrue(this.player.release());
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertFalse(this.player.startAsync(SOURCE, executor).join());
    } finally {
      executor.shutdownNow();
    }
  }
}

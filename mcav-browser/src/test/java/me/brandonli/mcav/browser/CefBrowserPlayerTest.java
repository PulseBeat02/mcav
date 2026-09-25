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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.Test;

class CefBrowserPlayerTest {

  private static final BrowserSource SOURCE = BrowserSource.uri(URI.create("https://example.com/"), 100, 50, 1);

  private final List<FakeSession> sessions = new ArrayList<>();
  private final List<String> reports = new ArrayList<>();
  private final List<ImageBuffer> processed = new ArrayList<>();
  private BrowserSession.Listener listener;
  private RuntimeException startFailure;

  private final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
    if (this.startFailure != null) {
      throw this.startFailure;
    }
    this.listener = sessionListener;
    final FakeSession session = new FakeSession();
    this.sessions.add(session);
    return session;
  });

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
  static final class FakeSession implements BrowserSession {

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
    final List<byte[]> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
    this.player.getAudioAttachableCallback()
      .attach(
        me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep.of((samples, metadata) -> {
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
    // a released player has no output, and sound of a session that is over is dropped
    first.onAudio(new byte[] { 2, 0, 2, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 3, 0, 3, 0 });
    Thread.sleep(CefBrowserPlayer.MAX_QUEUED_AUDIO_MILLIS * 2L);
    assertEquals(1, heard.size());
  }

  @Test
  void soundBeforeTheSessionIsThePlayersOrOfAnOldSessionIsDropped() throws InterruptedException {
    final List<byte[]> heard = this.attachSoundRecorder();
    final CefBrowserPlayer early = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      // the helper plays sound during the start, before the session is the player's
      sessionListener.onAudio(new byte[] { 9, 0, 9, 0 });
      this.listener = sessionListener;
      final FakeSession session = new FakeSession();
      this.sessions.add(session);
      return session;
    });
    final List<byte[]> earlyHeard = new java.util.concurrent.CopyOnWriteArrayList<>();
    early
      .getAudioAttachableCallback()
      .attach(me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep.of((samples, metadata) -> earlyHeard.add(new byte[0])));
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
  void anEndOfTheHelperWhileTheSessionStartsFailsTheStartAndIsReported() {
    final List<FakeSession> opened = new ArrayList<>();
    final List<String> reports = new ArrayList<>();
    final CefBrowserPlayer player = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> {
      final FakeSession session = new FakeSession();
      if (opened.isEmpty()) {
        // the helper ends after it showed the page, before the player took the session
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

  private static java.util.Set<Thread> audioThreads() {
    final java.util.Set<Thread> threads = new java.util.HashSet<>();
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().equals(me.brandonli.mcav.utils.audio.DelayedAudioOutput.THREAD_NAME)) {
        threads.add(thread);
      }
    }
    return threads;
  }

  @Test
  void aHelperThatEndsTakesTheSoundOfItsSessionAlongAndLeavesNoThread() {
    final java.util.Set<Thread> before = audioThreads();
    final List<byte[]> heard = this.attachSoundRecorder();
    assertTrue(this.player.start(SOURCE));
    final BrowserSession.Listener ended = this.listener;
    ended.onEnded("gone", new IllegalStateException("crash"));
    Await.until("the audio thread of the failed session ended", () -> before.containsAll(audioThreads()));
    // sound the helper sent before it went is not played after the failure was reported
    ended.onAudio(new byte[] { 6, 0, 6, 0 });
    this.player.deliverAudio(this.sessions.getFirst(), new byte[] { 7, 0, 7, 0 });
    assertEquals(List.of(), heard);
    // an end while the session starts fails the start and leaves no thread either
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
    this.listener.onFrame(frame());
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
    assertThrows(OutOfMemoryError.class, () -> this.listener.onFrame(frame()));
  }

  @Test
  void framesAndEndsOfAReplacedSessionAreIgnored() {
    this.player.start(SOURCE);
    final BrowserSession.Listener first = this.listener;
    first.onEnded("gone", new IllegalStateException("crash"));
    assertFalse(this.player.isPlaying());
    assertEquals(List.of("gone: crash"), this.reports);
    // a failed player starts again with a new session, closing the failed one
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
    ); // a hold moves the pointer first and then presses: two more drops
    this.player.sendMouseEvent(MouseClick.HOLD, 1, 1);
    assertEquals(4, this.reports.size(), this.reports.toString());
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
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final CompletableFuture<Boolean> started = this.player.startAsync(SOURCE, executor);
      assertTrue(started.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    this.player.release();
    final CefBrowserPlayer other = new CefBrowserPlayer(BrowserOptions.DEFAULT, (source, options, sessionListener) -> new FakeSession());
    assertTrue(other.startAsync(SOURCE).get(10, TimeUnit.SECONDS));
    assertThrows(NullPointerException.class, () -> other.startAsync(null));
    assertThrows(NullPointerException.class, () -> other.startAsync(SOURCE, null));
  }

  /**
   * Wraps a picture and remembers whether it was closed.
   */
  private static final class ClosingProbe {

    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    ImageBuffer wrap(final ImageBuffer real) {
      return (ImageBuffer) java.lang.reflect.Proxy.newProxyInstance(
        ImageBuffer.class.getClassLoader(),
        new Class<?>[] { ImageBuffer.class },
        (proxy, method, arguments) -> {
          if (method.getName().equals("close") && method.getParameterCount() == 0) {
            this.closed.set(true);
          }
          try {
            return method.invoke(real, arguments);
          } catch (final java.lang.reflect.InvocationTargetException exception) {
            throw exception.getCause();
          }
        }
      );
    }

    boolean isClosed() {
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
    // the release gave its lock back, so a start elsewhere answers at once
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertFalse(this.player.start(SOURCE)));
  }

  @Test
  void aStoppedModuleDownloadsNothing() {
    final List<URI> downloads = new java.util.concurrent.CopyOnWriteArrayList<>();
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

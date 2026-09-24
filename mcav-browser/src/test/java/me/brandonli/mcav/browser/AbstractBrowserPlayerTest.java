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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AbstractBrowserPlayer} and the default methods of {@link BrowserPlayer} through a player that opens
 * no browser.
 */
final class AbstractBrowserPlayerTest {

  private static final URI PAGE = URI.create("https://example.org");
  private static final BrowserSource SOURCE = BrowserSource.uri(PAGE, 80, 200, 100, 1);
  private static final int GREEN = 0x00FF00;

  private final List<String> errorMessages = new CopyOnWriteArrayList<>();
  private final List<Throwable> errors = new CopyOnWriteArrayList<>();

  private TestPlayer player() {
    final TestPlayer player = new TestPlayer();
    final BiConsumer<String, Throwable> handler = (message, error) -> {
      this.errorMessages.add(message);
      this.errors.add(error);
    };
    player.setExceptionHandler(handler);
    return player;
  }

  private static void attachFilter(final AbstractBrowserPlayer player, final VideoFilter filter) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  @Test
  void opensTheBrowserOnStartAndClosesItOnRelease() {
    final TestPlayer player = this.player();
    final boolean playingBefore = player.isPlaying();
    final boolean started = player.start(SOURCE);
    final boolean playing = player.isPlaying();
    final BrowserSource source = player.getSource();
    assertFalse(playingBefore);
    assertTrue(started);
    assertTrue(playing);
    assertSame(SOURCE, source);
    final List<BrowserSource> expectedOpened = List.of(SOURCE);
    assertEquals(expectedOpened, player.opened);

    final boolean released = player.release();
    final boolean playingAfter = player.isPlaying();
    final int closeCount = player.closeCount.get();
    assertTrue(released);
    assertFalse(playingAfter);
    assertEquals(1, closeCount);
  }

  @Test
  void refusesToStartTwiceOrAfterRelease() {
    final TestPlayer player = this.player();
    player.start(SOURCE);
    final boolean startedAgain = player.start(SOURCE);
    player.release();
    final boolean releasedAgain = player.release();
    final boolean startedAfterRelease = player.start(SOURCE);
    assertFalse(startedAgain);
    assertFalse(releasedAgain);
    assertFalse(startedAfterRelease);
    final int openedCount = player.opened.size();
    final int closeCount = player.closeCount.get();
    assertEquals(1, openedCount);
    assertEquals(1, closeCount);
  }

  @Test
  void staysStoppedWhenTheBrowserFailsToOpen() {
    final TestPlayer player = this.player();
    final PlayerException failure = new PlayerException("no browser");
    player.failure = failure;
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(SOURCE));
    final boolean playing = player.isPlaying();
    final int closed = player.closeCount.get();
    assertSame(failure, exception);
    assertFalse(playing);
    assertEquals(1, closed, "resources acquired before opening failed must be closed");
    player.failure = null;
    final boolean started = player.start(SOURCE);
    assertTrue(started);
  }

  @Test
  void ignoresLateFramesAndFailuresAfterOpeningFails() {
    final TestPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final PlayerException failure = new PlayerException("opening failed");
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    player.failure = failure;
    assertThrows(PlayerException.class, () -> player.start(SOURCE));
    player.deliverFrame(jpeg, 40, 20);
    player.fail("late browser callback", failure);
    final int count = frames.count();
    final boolean noReports = this.errors.isEmpty();
    assertEquals(0, count);
    assertTrue(noReports);
  }

  @Test
  void preservesOpeningFailureWhenCleanupAlsoFails() {
    final TestPlayer player = this.player();
    final PlayerException failure = new PlayerException("opening failed");
    final AssertionError cleanup = new AssertionError("cleanup failed");
    player.failure = failure;
    player.whileClosing = () -> {
      throw cleanup;
    };
    final PlayerException thrown = assertThrows(PlayerException.class, () -> player.start(SOURCE));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertArrayEquals(new Throwable[] { cleanup }, suppressed);
    player.fail("late callback", cleanup);
    final boolean noReports = this.errors.isEmpty();
    assertTrue(noReports, "failed cleanup must not leave the player opening");
    player.failure = null;
    player.whileClosing = () -> {};
    final boolean restarted = player.start(SOURCE);
    assertTrue(restarted);
    player.release();
  }

  @Test
  void doesNotSuppressAnOpeningFailureOntoItself() {
    final TestPlayer player = this.player();
    final PlayerException failure = new PlayerException("shared failure");
    player.failure = failure;
    player.whileClosing = () -> {
      throw failure;
    };
    final PlayerException thrown = assertThrows(PlayerException.class, () -> player.start(SOURCE));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(0, suppressed.length);
  }

  @Test
  void propagatesFatalCleanupWithoutLeavingThePlayerOpening() {
    final TestPlayer player = this.player();
    final PlayerException failure = new PlayerException("opening failed");
    final InternalError fatal = new InternalError("fatal cleanup");
    player.failure = failure;
    player.whileClosing = () -> {
      throw fatal;
    };
    final InternalError thrown = assertThrows(InternalError.class, () -> player.start(SOURCE));
    assertSame(fatal, thrown);
    player.fail("late callback", failure);
    final boolean noReports = this.errors.isEmpty();
    assertTrue(noReports);
  }

  @Test
  void preservesFatalOpeningFailureWhenCleanupAlsoFails() {
    final TestPlayer player = this.player();
    final InternalError fatal = new InternalError("fatal opening");
    player.whileOpening = _ -> {
      throw fatal;
    };
    player.whileClosing = () -> {
      throw new InternalError("fatal cleanup must not replace the original fatal error");
    };
    final InternalError thrown = assertThrows(InternalError.class, () -> player.start(SOURCE));
    final int closed = player.closeCount.get();
    assertSame(fatal, thrown);
    assertEquals(1, closed);
  }

  @Test
  void streamsFramesThatArriveWhileThePageOpensButIgnoresInputUntilItIsOpen() {
    final TestPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    final List<Boolean> inputWhileOpening = new CopyOnWriteArrayList<>();
    // a static page sends a single frame, often before the browser reports that the page has loaded
    player.whileOpening = opening -> {
      opening.deliverFrame(jpeg, 40, 20);
      final boolean input = opening.canForwardInput();
      final boolean playing = opening.isPlaying();
      inputWhileOpening.add(input);
      inputWhileOpening.add(playing);
    };
    player.start(SOURCE);

    final int count = frames.count();
    final boolean green = frames.lastShows(GREEN);
    final boolean inputAfterOpening = player.canForwardInput();
    final List<Boolean> expectedWhileOpening = List.of(false, false);
    assertEquals(1, count);
    assertTrue(green);
    assertEquals(expectedWhileOpening, inputWhileOpening);
    assertTrue(inputAfterOpening);
  }

  @Test
  void stopsPlayingWhenTheBrowserIsLostAndCanStartAgain() {
    final TestPlayer player = this.player();
    player.start(SOURCE);
    final IllegalStateException crash = new IllegalStateException("browser crashed");
    player.fail("The browser was lost", crash);
    player.fail("The browser was lost", crash);
    final boolean playing = player.isPlaying();
    final boolean input = player.canForwardInput();
    final List<String> expectedMessages = List.of("The browser was lost");
    final List<Throwable> expectedErrors = List.of(crash);
    assertFalse(playing);
    assertFalse(input);
    assertEquals(expectedMessages, this.errorMessages);
    assertEquals(expectedErrors, this.errors);

    final int closedBeforeRestart = player.closeCount.get();
    final boolean restarted = player.start(SOURCE);
    final boolean playingAgain = player.isPlaying();
    final int openedCount = player.opened.size();
    final int closedAfterRestart = player.closeCount.get();
    assertEquals(0, closedBeforeRestart);
    assertTrue(restarted);
    assertTrue(playingAgain);
    assertEquals(1, closedAfterRestart, "the lost browser is closed before the new one opens");
    assertEquals(2, openedCount);
  }

  @Test
  void staysFailedWhenTheBrowserIsLostWhileThePageOpens() {
    final TestPlayer player = this.player();
    final IllegalStateException crash = new IllegalStateException("crashed while loading");
    player.whileOpening = opening -> opening.fail("The browser was lost", crash);
    final boolean started = player.start(SOURCE);
    final boolean playing = player.isPlaying();
    assertFalse(started);
    assertFalse(playing);
    final List<Throwable> expectedErrors = List.of(crash);
    assertEquals(expectedErrors, this.errors);
  }

  @Test
  void doesNotReportFailuresWhileStopped() {
    final TestPlayer player = this.player();
    final IllegalStateException crash = new IllegalStateException("closing");
    player.fail("The browser was lost", crash);
    player.start(SOURCE);
    player.release();
    player.fail("The browser was lost", crash);
    final boolean noReports = this.errorMessages.isEmpty();
    final boolean playing = player.isPlaying();
    assertTrue(noReports, this.errorMessages::toString);
    assertFalse(playing);
  }

  @Test
  void hasNoSourceBeforeStarting() {
    final TestPlayer player = this.player();
    assertThrows(IllegalStateException.class, player::getSource);
    assertThrows(IllegalStateException.class, () -> player.translateCoordinates(1, 1));
  }

  @Test
  void onlyForwardsInputWhilePlaying() {
    final TestPlayer player = this.player();
    final boolean beforeStart = player.canForwardInput();
    player.start(SOURCE);
    final boolean whilePlaying = player.canForwardInput();
    player.release();
    final boolean afterRelease = player.canForwardInput();
    assertFalse(beforeStart);
    assertTrue(whilePlaying);
    assertFalse(afterRelease);
  }

  @Test
  void mapsFrameCoordinatesOntoThePage() {
    final TestPlayer player = this.player();
    player.start(SOURCE);
    final int[] identity = player.translateCoordinates(50, 25);
    player.setPageSize(400, 300);
    final int[] scaled = player.translateCoordinates(50, 25);
    final int[] clampedHigh = player.translateCoordinates(500, 500);
    final int[] clampedLow = player.translateCoordinates(-5, -5);
    final int[] extremeHigh = player.translateCoordinates(Integer.MAX_VALUE, Integer.MAX_VALUE);
    final int[] extremeLow = player.translateCoordinates(Integer.MIN_VALUE, Integer.MIN_VALUE);
    assertArrayEquals(new int[] { 50, 25 }, identity);
    assertArrayEquals(new int[] { 100, 75 }, scaled);
    assertArrayEquals(new int[] { 399, 299 }, clampedHigh);
    assertArrayEquals(new int[] { 0, 0 }, clampedLow);
    assertArrayEquals(new int[] { 399, 299 }, extremeHigh);
    assertArrayEquals(new int[] { 0, 0 }, extremeLow);
  }

  @Test
  void ignoresUnknownPageSizes() {
    final TestPlayer player = this.player();
    player.start(SOURCE);
    player.setPageSize(400, 200);
    player.setPageSize(0, 300);
    player.setPageSize(300, 0);
    player.setPageSize(-1, -1);
    final int[] scaled = player.translateCoordinates(100, 50);
    assertArrayEquals(new int[] { 200, 100 }, scaled);
  }

  @Test
  void deliversFramesScaledToTheSourceSize() {
    final TestPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    player.start(SOURCE);
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    player.deliverFrame(jpeg, 800, 400);

    final Frames.Frame frame = frames.requireLast();
    final int width = frame.getWidth();
    final int height = frame.getHeight();
    final int metadataWidth = frame.getMetadataWidth();
    final int metadataHeight = frame.getMetadataHeight();
    final int center = frame.getCenter();
    final boolean green = Frames.isNear(GREEN, center);
    assertEquals(200, width);
    assertEquals(100, height);
    assertEquals(200, metadataWidth);
    assertEquals(100, metadataHeight);
    assertTrue(green);

    // the page size that came with the frame is used for input
    final int[] translated = player.translateCoordinates(100, 50);
    assertArrayEquals(new int[] { 400, 200 }, translated);
  }

  @Test
  void dropsFramesWhileNotPlaying() {
    final TestPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    final IllegalStateException crash = new IllegalStateException("crashed");
    player.deliverFrame(jpeg, 40, 20);
    player.start(SOURCE);
    player.fail("The browser was lost", crash);
    player.deliverFrame(jpeg, 40, 20);
    player.release();
    player.deliverFrame(jpeg, 40, 20);
    final int count = frames.count();
    assertEquals(0, count);
  }

  @Test
  void reportsFramesThatCannotBeDecoded() {
    final TestPlayer player = this.player();
    player.start(SOURCE);
    final byte[] garbage = { 1, 2, 3, 4 };
    player.deliverFrame(garbage, 0, 0);
    final String message = this.errorMessages.getFirst();
    assertEquals("Failed to process a browser frame", message);
  }

  @Test
  void reportsPipelineFailuresAndErrors() {
    final TestPlayer player = this.player();
    final IllegalStateException failure = new IllegalStateException("filter failed");
    final AssertionError error = new AssertionError("filter broken");
    final List<Throwable> thrown = List.of(failure, error);
    final AtomicInteger calls = new AtomicInteger();
    attachFilter(player, (_, _) -> {
      final int call = calls.getAndIncrement();
      final Throwable next = thrown.get(call);
      if (next instanceof final RuntimeException runtime) {
        throw runtime;
      }
      throw (Error) next;
    });
    player.start(SOURCE);
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    player.deliverFrame(jpeg, 40, 20);
    player.deliverFrame(jpeg, 40, 20);
    final List<String> expectedMessages = List.of("Failed to process a browser frame", "Failed to process a browser frame");
    assertEquals(expectedMessages, this.errorMessages);
    assertEquals(thrown, this.errors);
  }

  @Test
  void throwsErrorsOfTheVirtualMachine() {
    final TestPlayer player = this.player();
    final InternalError fatal = new InternalError("out of native memory");
    attachFilter(player, (_, _) -> {
      throw fatal;
    });
    player.start(SOURCE);
    final byte[] jpeg = Frames.jpeg(40, 20, GREEN);
    final InternalError thrown = assertThrows(InternalError.class, () -> player.deliverFrame(jpeg, 40, 20));
    final boolean noReports = this.errorMessages.isEmpty();
    assertSame(fatal, thrown);
    assertTrue(noReports, this.errorMessages::toString);
  }

  @Test
  void replacesTheExceptionHandler() {
    final TestPlayer player = new TestPlayer();
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
  }

  @Test
  void rejectsNullArguments() {
    final TestPlayer player = this.player();
    final IllegalStateException failure = new IllegalStateException("failure");
    assertThrows(NullPointerException.class, () -> player.start(null));
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> AbstractBrowserPlayer.checkMouseClick(null));
    assertThrows(NullPointerException.class, () -> player.deliverFrame(null, 0, 0));
    assertThrows(NullPointerException.class, () -> player.fail(null, failure));
    assertThrows(NullPointerException.class, () -> player.fail("message", null));
    assertThrows(NullPointerException.class, () -> player.report(null, failure));
    assertThrows(NullPointerException.class, () -> player.report("message", null));
    AbstractBrowserPlayer.checkMouseClick(MouseClick.LEFT);
  }

  @Test
  void startsAsynchronously() throws Exception {
    final TestPlayer player = this.player();
    final CompletableFuture<Boolean> first = player.startAsync(SOURCE);
    final boolean started = first.get(10, TimeUnit.SECONDS);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> second = player.startAsync(SOURCE, executor);
      final boolean startedAgain = second.get(10, TimeUnit.SECONDS);
      assertTrue(started);
      assertFalse(startedAgain);
      assertThrows(NullPointerException.class, () -> player.startAsync(null, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(SOURCE, null));
      assertThrows(NullPointerException.class, () -> player.startAsync(null));
    }
  }

  /**
   * A player that records what the base class asks of it instead of opening a browser.
   */
  private static final class TestPlayer extends AbstractBrowserPlayer {

    private final List<BrowserSource> opened = new CopyOnWriteArrayList<>();
    private final AtomicInteger closeCount = new AtomicInteger();
    private volatile PlayerException failure;
    private volatile Consumer<TestPlayer> whileOpening = _ -> {};
    private volatile Runnable whileClosing = () -> {};

    @Override
    protected void open(final BrowserSource source) {
      final PlayerException current = this.failure;
      if (current != null) {
        throw current;
      }
      this.opened.add(source);
      this.whileOpening.accept(this);
    }

    @Override
    protected void close() {
      this.closeCount.incrementAndGet();
      this.whileClosing.run();
    }

    @Override
    public void moveMouse(final int x, final int y) {
      // input is tested with the real players
    }

    @Override
    public void sendMouseEvent(final MouseClick type, final int x, final int y) {
      // input is tested with the real players
    }

    @Override
    public void sendKeyEvent(final String text) {
      // input is tested with the real players
    }
  }
}

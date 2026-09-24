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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.browser.testing.TestPages;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opentest4j.AssertionFailedError;

/**
 * Tests {@link PlaywrightPlayer}.
 *
 * <p>Fast tests use controlled transports and prepared events. The focused real-browser test classes compose this
 * fixture to exercise browser input and process lifecycle against pages on the loopback interface.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SharedBrowserCache.class)
final class PlaywrightPlayerTest {

  private static final int WIDTH = 480;
  private static final int HEIGHT = 270;
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration INSTALL_TIMEOUT = Duration.ofSeconds(30);

  private final List<PlaywrightPlayer> players = new CopyOnWriteArrayList<>();
  private final List<String> errorMessages = new CopyOnWriteArrayList<>();
  private final List<Throwable> errors = new CopyOnWriteArrayList<>();
  private TestPages pages;
  private @Nullable SharedPlaywright sharedBrowser;

  @BeforeEach
  void startPages() {
    this.players.clear();
    this.errorMessages.clear();
    this.errors.clear();
    this.pages = TestPages.start();
  }

  @AfterEach
  void releasePlayers() {
    for (final PlaywrightPlayer player : this.players) {
      player.release();
    }
    this.pages.close();
  }

  private PlaywrightPlayer track(final PlaywrightPlayer player) {
    final BiConsumer<String, Throwable> handler = (message, error) -> {
      this.errorMessages.add(message);
      this.errors.add(error);
    };
    player.setExceptionHandler(handler);
    this.players.add(player);
    return player;
  }

  private PlaywrightPlayer player() {
    final SharedPlaywright shared = this.sharedBrowser;
    if (shared != null) {
      final PlaywrightPlayer player = new PlaywrightPlayer(shared::lease);
      return this.track(player);
    }
    ExternalBrowsers.assumePlaywrightBrowser();
    return this.unstartedPlayer();
  }

  private PlaywrightPlayer unstartedPlayer() {
    final PlaywrightPlayer player = new PlaywrightPlayer();
    return this.track(player);
  }

  private PlaywrightPlayer playerWithInstaller(final Runnable installer, final Duration timeout) {
    final PlaywrightPlayer player = new PlaywrightPlayer(installer, timeout, STOP_TIMEOUT);
    return this.track(player);
  }

  private BrowserSource page(final String path) {
    final URI uri = this.pages.uri(path);
    return BrowserSource.uri(uri, 80, WIDTH, HEIGHT, 1);
  }

  private void awaitEvent(final String description, final String page, final String type, final String key) {
    this.awaitOrDump(description, () -> this.hasEvent(page, type, key));
  }

  private void awaitOrDump(final String description, final BooleanSupplier condition) {
    try {
      Await.until(description, condition);
    } catch (final AssertionFailedError failure) {
      final List<TestPages.PageEvent> events = this.pages.getEvents();
      final String message = failure.getMessage();
      final String traces = this.describeErrors();
      throw new AssertionError(message + "; events " + events + "; errors " + traces, failure);
    }
  }

  private String describeErrors() {
    final StringBuilder traces = new StringBuilder();
    for (final Throwable error : this.errors) {
      final StringWriter writer = new StringWriter();
      final PrintWriter printer = new PrintWriter(writer);
      error.printStackTrace(printer);
      printer.flush();
      traces.append(writer);
    }
    return traces.toString();
  }

  private boolean hasEvent(final String page, final String type, final String key) {
    final List<TestPages.PageEvent> events = this.pages.getEvents(type);
    for (final TestPages.PageEvent event : events) {
      final String eventPage = event.getPage();
      final String eventKey = event.getKey();
      if (eventPage.equals(page) && eventKey.equals(key)) {
        return true;
      }
    }
    return false;
  }

  private TestPages.PageEvent awaitMouseAt(final String type, final int x, final int y) {
    this.awaitOrDump("a " + type + " event at " + x + ", " + y + " arrives", () -> this.findMouse(type, x, y) != null);
    final TestPages.PageEvent event = this.findMouse(type, x, y);
    return Objects.requireNonNull(event, "the event was found a moment ago");
  }

  private TestPages.@Nullable PageEvent findMouse(final String type, final int x, final int y) {
    final List<TestPages.PageEvent> events = this.pages.getEvents(type);
    for (final TestPages.PageEvent event : events) {
      final int eventX = event.getX();
      final int eventY = event.getY();
      if (eventX == x && eventY == y) {
        return event;
      }
    }
    return null;
  }

  /**
   * Clicks at a position of the frame and waits until the page reports the event at the matching page position.
   *
   * @param player the player
   * @param click  the kind of click
   * @param x      the x coordinate in the frame
   * @param y      the y coordinate in the frame
   * @param type   the DOM event the click causes
   * @return the event the page reported
   */
  private TestPages.PageEvent clickAndAwait(
    final BrowserPlayer player,
    final MouseClick click,
    final int x,
    final int y,
    final String type
  ) {
    final int pageX = this.pages.toPage(x, WIDTH, true);
    final int pageY = this.pages.toPage(y, HEIGHT, false);
    player.sendMouseEvent(click, x, y);
    return this.awaitMouseAt(type, pageX, pageY);
  }

  private static JsonObject frameJson(final boolean withSession, final boolean withData) {
    final JsonObject frame = new JsonObject();
    if (withSession) {
      frame.addProperty("sessionId", 4);
    }
    final JsonObject metadata = new JsonObject();
    metadata.addProperty("deviceWidth", 960);
    metadata.addProperty("deviceHeight", 540);
    frame.add("metadata", metadata);
    if (withData) {
      final byte[] jpeg = Frames.jpeg(8, 8, 0x00FF00);
      final Base64.Encoder encoder = Base64.getEncoder();
      final String data = encoder.encodeToString(jpeg);
      frame.addProperty("data", data);
    }
    return frame;
  }

  private static List<ProcessHandle> browserProcesses(final String... names) {
    final ProcessHandle current = ProcessHandle.current();
    final Stream<ProcessHandle> descendants = current.descendants();
    final List<ProcessHandle> all = descendants.toList();
    final List<ProcessHandle> matching = new ArrayList<>();
    for (final ProcessHandle handle : all) {
      final ProcessHandle.Info info = handle.info();
      final Optional<String> command = info.command();
      final String name = command.orElse("");
      final String lower = name.toLowerCase(Locale.ROOT);
      for (final String processName : names) {
        final boolean matches = lower.contains(processName);
        final boolean alive = handle.isAlive();
        if (matches && alive) {
          matching.add(handle);
        }
      }
    }
    return matching;
  }

  private static boolean noHeadlessBrowserRuns() {
    final List<ProcessHandle> browsers = browserProcesses("headless");
    return browsers.isEmpty();
  }

  private static void killProcesses(final String... names) {
    final List<ProcessHandle> matching = browserProcesses(names);
    assertFalse(matching.isEmpty(), "the crash fixture must find live owned browser processes");
    for (final ProcessHandle handle : matching) {
      handle.destroyForcibly();
    }
    Await.until("the targeted browser processes exit", () -> matching.stream().noneMatch(ProcessHandle::isAlive));
  }

  private static List<ProcessHandle> processesStartedAfter(final Instant start) {
    final ProcessHandle current = ProcessHandle.current();
    final Stream<ProcessHandle> descendants = current.descendants();
    final List<ProcessHandle> all = descendants.toList();
    final List<ProcessHandle> started = new ArrayList<>();
    for (final ProcessHandle handle : all) {
      final ProcessHandle.Info info = handle.info();
      final Optional<Instant> startInstant = info.startInstant();
      final Instant processStart = startInstant.orElse(Instant.MIN);
      final boolean alive = handle.isAlive();
      if (alive && processStart.isAfter(start)) {
        started.add(handle);
      }
    }
    return started;
  }

  private static Runnable failingInstaller(final RuntimeException failure) {
    return () -> {
      throw failure;
    };
  }

  /**
   * Creates an installer that waits until it is interrupted, as a slow download does.
   *
   * @param interrupted counted down when the installer is interrupted
   * @return the installer
   */
  private static Runnable interruptibleInstaller(final CountDownLatch interrupted) {
    return () -> {
      try {
        Thread.sleep(30_000L);
      } catch (final InterruptedException exception) {
        interrupted.countDown();
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
      throw new IllegalStateException("gave up installing");
    };
  }

  /**
   * Creates an installer that ignores interrupts until it is allowed to finish the first time, and fails at once
   * every later time, which shows that a later start was allowed to install.
   *
   * @param finishInstalling counted down to let the first installation finish
   * @param abandoned        receives the thread of the first installation
   * @param installs         counts the installations
   * @return the installer
   */
  private static Runnable blockingInstaller(
    final CountDownLatch finishInstalling,
    final AtomicReference<Thread> abandoned,
    final AtomicInteger installs
  ) {
    return () -> {
      final int call = installs.incrementAndGet();
      if (call > 1) {
        throw new IllegalStateException("installing again");
      }
      final Thread current = Thread.currentThread();
      abandoned.set(current);
      // like a download that ignores interrupts
      Uninterruptibles.awaitUninterruptibly(finishInstalling);
    };
  }

  void streamsThePageAndFollowsPopupsThePageOpensAndCloses() {
    final PlaywrightPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final BrowserSource source = this.page("/main");
    final boolean started = player.start(source);
    final boolean playing = player.isPlaying();
    assertTrue(started);
    assertTrue(playing);

    Await.until("the red main page is streamed", () -> frames.lastShows(TestPages.MAIN_COLOR));
    final Frames.Frame first = frames.requireLast();
    final int width = first.getWidth();
    final int height = first.getHeight();
    assertEquals(WIDTH, width);
    assertEquals(HEIGHT, height);

    player.sendKeyEvent("o");
    this.awaitOrDump("the blue popup is streamed", () -> frames.lastShows(TestPages.POPUP_COLOR));
    player.sendKeyEvent("x");
    this.awaitEvent("the popup receives the key", "popup", "keydown", "x");
    this.awaitOrDump("the stream returns to the main page", () -> frames.lastShows(TestPages.MAIN_COLOR));
    player.sendKeyEvent("Enter");
    this.awaitEvent("the main page receives input again", "main", "keydown", "Enter");
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errorMessages::toString);
  }

  void forwardsEveryKindOfMouseInput() {
    final PlaywrightPlayer player = this.player();
    final BrowserSource source = this.page("/main");
    player.start(source);
    this.awaitOrDump("the page reports its size", () -> this.pages.count("size") > 0);

    // every event reaches the test server in its own request, and Chromium may report a pointer at 0, 0 on its
    // own, so events are found by position rather than by order
    final int moveX = this.pages.toPage(100, WIDTH, true);
    final int moveY = this.pages.toPage(50, HEIGHT, false);
    player.moveMouse(100, 50);
    this.awaitMouseAt("mousemove", moveX, moveY);
    final TestPages.PageEvent click = this.clickAndAwait(player, MouseClick.LEFT, 200, 100, "click");
    final TestPages.PageEvent menu = this.clickAndAwait(player, MouseClick.RIGHT, 150, 60, "contextmenu");
    this.clickAndAwait(player, MouseClick.DOUBLE, 120, 80, "dblclick");
    final TestPages.PageEvent hold = this.clickAndAwait(player, MouseClick.HOLD, 60, 40, "mousedown");
    final TestPages.PageEvent release = this.clickAndAwait(player, MouseClick.RELEASE, 70, 45, "mouseup");

    final int clickButton = click.getButton();
    final int menuButton = menu.getButton();
    final int holdButton = hold.getButton();
    final int releaseButton = release.getButton();
    assertEquals(0, clickButton);
    assertEquals(2, menuButton);
    assertEquals(0, holdButton);
    assertEquals(0, releaseButton);
  }

  void pressesNamedKeysAndTypesText() {
    final PlaywrightPlayer player = this.player();
    final BrowserSource source = this.page("/main");
    player.start(source);
    player.sendKeyEvent("Enter");
    player.sendKeyEvent("ab");
    player.sendKeyEvent("ArrowLeft");
    this.awaitEvent("the arrow key arrives", "main", "keydown", "ArrowLeft");
    final List<TestPages.PageEvent> keys = this.pages.getEvents("keydown");
    final List<String> names = new ArrayList<>();
    for (final TestPages.PageEvent key : keys) {
      final String name = key.getKey();
      names.add(name);
    }
    final List<String> expectedNames = List.of("Enter", "a", "b", "ArrowLeft");
    assertEquals(expectedNames, names);
  }

  void ignoresInputWhileThePageIsStillLoading() {
    final PlaywrightPlayer player = this.player();
    final CountDownLatch hookRan = new CountDownLatch(1);
    this.pages.setLoadHook(() -> {
        player.moveMouse(5, 5);
        player.sendMouseEvent(MouseClick.LEFT, 5, 5);
        player.sendKeyEvent("q");
        hookRan.countDown();
      });
    final BrowserSource source = this.page("/hooked");
    player.start(source);
    final long remaining = hookRan.getCount();
    assertEquals(0, remaining);

    player.sendKeyEvent("z");
    this.awaitEvent("input after the start arrives", "main", "keydown", "z");
    final boolean earlyKey = this.hasEvent("main", "keydown", "q");
    final int clicks = this.pages.count("click");
    assertFalse(earlyKey);
    assertEquals(0, clicks);
  }

  void staysOnTheFollowedPageWhenABackgroundPageClosesAndReturnsWhenItClosesItself() {
    final PlaywrightPlayer player = this.player();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final BrowserSource source = this.page("/main");
    player.start(source);
    player.sendKeyEvent("o");
    this.awaitOrDump("the blue popup is streamed", () -> frames.lastShows(TestPages.POPUP_COLOR));
    player.sendKeyEvent("n");
    this.awaitOrDump("the green second page is streamed", () -> frames.lastShows(TestPages.SECOND_COLOR));
    this.awaitEvent("the popup closes itself in the background", "popup", "closing", "");
    player.sendKeyEvent("k");
    this.awaitEvent("the second page still receives input", "second", "keydown", "k");
    final boolean green = frames.lastShows(TestPages.SECOND_COLOR);
    assertTrue(green);

    player.sendKeyEvent("w");
    this.awaitEvent("the second page closes itself", "second", "closing", "");
    this.awaitOrDump("the stream returns to the main page", () -> frames.lastShows(TestPages.MAIN_COLOR));
    player.sendKeyEvent("m");
    this.awaitEvent("the main page receives input again", "main", "keydown", "m");
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errors::toString);
  }

  @Test
  void runsInputOnlyOnOpenPages() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false);
    final List<Page> received = new CopyOnWriteArrayList<>();
    player.runAction(page, received::add);
    when(page.isClosed()).thenReturn(true);
    player.runAction(page, received::add);
    final List<Page> expectedReceived = List.of(page);
    assertEquals(expectedReceived, received);
  }

  @Test
  void reportsInputThatFailsOnAnOpenPage() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false);
    final PlaywrightException failure = new PlaywrightException("Unknown key");
    player.runAction(page, _ -> {
      throw failure;
    });
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("Failed to forward input to the browser", message);
    assertSame(failure, error);
  }

  @Test
  void doesNotReportInputThatClosesItsPage() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false, true);
    player.runAction(page, _ -> {
      throw new PlaywrightException("Target page, context or browser has been closed");
    });
    final boolean noReports = this.errorMessages.isEmpty();
    assertTrue(noReports, this.errorMessages::toString);
  }

  void failsWhenThePageCannotBeOpened() throws IOException {
    final int port;
    final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
    try (final ServerSocket socket = new ServerSocket(0, 1, loopback)) {
      port = socket.getLocalPort();
    }
    final PlaywrightPlayer player = this.player();
    final URI unreachable = URI.create("http://127.0.0.1:" + port + "/");
    final BrowserSource source = BrowserSource.uri(unreachable, 80, WIDTH, HEIGHT, 1);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final boolean playing = player.isPlaying();
    final boolean explains = message.startsWith("Failed to start Playwright: ") && message.contains("ERR_CONNECTION_REFUSED");
    assertTrue(explains, message);
    assertFalse(playing);
  }

  void reportsTheLossOfThePlaywrightDriver() {
    final PlaywrightPlayer player = this.player();
    final BrowserSource source = this.page("/main");
    final boolean started = player.start(source);
    assertTrue(started, "the crash fixture must first start a working browser: " + this.errorMessages);
    killProcesses("node", "headless");
    Await.until("the failure is reported", () -> this.errorMessages.contains("The Playwright browser failed"));
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  void stopsPlayingWhenTheBrowserCrashesAndCanStartAgain() {
    ExternalBrowsers.assumePlaywrightBrowser();
    final OwnedPlaywright browser = new OwnedPlaywright();
    final PlaywrightPlayer created = new PlaywrightPlayer(browser::create);
    final PlaywrightPlayer player = this.track(created);
    final BrowserSource source = this.page("/main");
    String phase = "initial start";
    try {
      final boolean started = player.start(source);
      assertTrue(started, "the crash fixture must first start a working browser");
      phase = "input before the crash";
      player.sendKeyEvent("q");
      this.awaitEvent("the original browser receives input", "main", "keydown", "q");

      phase = "terminate the exact owned browser process";
      final ProcessHandle process = browser.process();
      final boolean alive = process.isAlive();
      assertTrue(alive, "the browser selected by CDP must still be alive before the crash");
      final boolean terminationRequested = process.destroyForcibly();
      assertTrue(terminationRequested, "the operating system must accept the crash request");
      this.awaitOrDump("the owned browser process exits", () -> !process.isAlive());
      phase = "failure notification";
      this.awaitOrDump("the failure is reported", () -> this.errorMessages.contains("The Playwright browser failed"));
      final boolean playingAfterCrash = player.isPlaying();
      player.sendKeyEvent("a");
      player.moveMouse(1, 1);
      player.sendMouseEvent(MouseClick.LEFT, 1, 1);
      final boolean inputFailures = this.errorMessages.contains("Failed to forward input to the browser");
      assertFalse(playingAfterCrash);
      assertFalse(inputFailures, this.errorMessages::toString);

      phase = "restart after the crash";
      final boolean restarted = player.start(source);
      final boolean playing = player.isPlaying();
      assertTrue(restarted);
      assertTrue(playing);
      phase = "input after restart";
      player.sendKeyEvent("r");
      this.awaitEvent("the new browser receives input", "main", "keydown", "r");
      phase = "release after restart";
      final boolean released = player.release();
      assertTrue(released);
    } catch (final RuntimeException | AssertionError failure) {
      final String processes = browser.describeProcess();
      final String threads = describeBrowserThreads();
      final String traces = this.describeErrors();
      final String diagnostics =
        "Playwright crash fixture failed during " +
        phase +
        "; " +
        processes +
        "; messages " +
        this.errorMessages +
        "; errors " +
        traces +
        "; browser threads " +
        threads;
      System.err.println(diagnostics);
      throw new AssertionError(diagnostics, failure);
    }
  }

  private static String describeBrowserThreads() {
    final StringBuilder result = new StringBuilder();
    final Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
    for (final Map.Entry<Thread, StackTraceElement[]> entry : threads.entrySet()) {
      final Thread thread = entry.getKey();
      final String name = thread.getName();
      if (!name.startsWith("mcav-playwright")) {
        continue;
      }
      final Thread.State state = thread.getState();
      result.append(name).append(' ').append(state).append('\n');
      final StackTraceElement[] stack = entry.getValue();
      for (final StackTraceElement frame : stack) {
        result.append("  ").append(frame).append('\n');
      }
    }
    return result.toString();
  }

  void closesTheBrowserOnReleaseAndDropsLaterInput() {
    final PlaywrightPlayer player = this.player();
    final BrowserSource source = this.page("/main");
    player.start(source);
    player.sendKeyEvent("a");
    // the positive control: input reaches the page while the player plays
    this.awaitEvent("the key arrives", "main", "keydown", "a");
    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    final boolean playing = player.isPlaying();
    final boolean startedAgain = player.start(source);
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(playing);
    assertFalse(startedAgain);

    // with the browser gone, input after the release has nowhere to go and must be dropped without errors
    Await.until("the browser of the player exits", PlaywrightPlayerTest::noHeadlessBrowserRuns);
    player.sendKeyEvent("b");
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errorMessages::toString);
  }

  @Test
  void failsWhenTheBrowserCannotBeInstalled() {
    final IllegalStateException failure = new IllegalStateException("the download was blocked");
    final Runnable installer = failingInstaller(failure);
    final PlaywrightPlayer player = this.playerWithInstaller(installer, INSTALL_TIMEOUT);
    final BrowserSource source = this.page("/main");
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean playing = player.isPlaying();
    assertEquals("Failed to start Playwright: the download was blocked", message);
    assertSame(failure, cause);
    assertFalse(playing);
  }

  @Test
  void failsTheStartAtOnceAndThrowsErrorsOfTheVirtualMachineOnTheBrowserThread() throws InterruptedException {
    final InternalError fatal = new InternalError("out of native memory");
    final Runnable installer = () -> {
      throw fatal;
    };
    final PlaywrightPlayer player = this.playerWithInstaller(installer, INSTALL_TIMEOUT);
    final BrowserSource source = this.page("/main");
    final AtomicReference<Throwable> startFailure = new AtomicReference<>();
    final RecordingThreadGroup group = new RecordingThreadGroup();
    // the threads of the player join the group of the thread that starts it, so the group sees their uncaught errors
    final Runnable start = () -> {
      final PlayerException failure = assertThrows(PlayerException.class, () -> player.start(source));
      startFailure.set(failure);
    };
    final Thread starter = new Thread(group, start, "playwright-starter");
    starter.start();
    starter.join();

    final Throwable failure = startFailure.get();
    final Throwable cause = failure.getCause();
    final boolean died = group.died.await(5, TimeUnit.SECONDS);
    final Throwable thrownOnBrowserThread = group.uncaught.get();
    final String threadName = group.threadName.get();
    assertSame(fatal, cause, "the start fails at once instead of at its timeout");
    assertTrue(died, "the browser thread ends with the error");
    assertSame(fatal, thrownOnBrowserThread, "the error of the virtual machine is not hidden");
    assertEquals("mcav-playwright", threadName);
  }

  /**
   * A thread group that records the first failure one of its threads did not catch, and the name of that thread.
   */
  private static final class RecordingThreadGroup extends ThreadGroup {

    private final AtomicReference<Throwable> uncaught;
    private final AtomicReference<String> threadName;
    private final CountDownLatch died;

    RecordingThreadGroup() {
      super("playwright-test");
      this.uncaught = new AtomicReference<>();
      this.threadName = new AtomicReference<>();
      this.died = new CountDownLatch(1);
    }

    @Override
    public void uncaughtException(final Thread thread, final Throwable failure) {
      final String name = thread.getName();
      this.threadName.compareAndSet(null, name);
      this.uncaught.compareAndSet(null, failure);
      this.died.countDown();
    }
  }

  @Test
  void interruptsAnInstallationThatTakesTooLong() throws InterruptedException {
    final CountDownLatch interrupted = new CountDownLatch(1);
    final Runnable installer = interruptibleInstaller(interrupted);
    final Duration startTimeout = Duration.ofMillis(200);
    final PlaywrightPlayer player = this.playerWithInstaller(installer, startTimeout);
    final BrowserSource source = this.page("/main");
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final boolean playing = player.isPlaying();
    final boolean installerInterrupted = interrupted.await(5, TimeUnit.SECONDS);
    final boolean noReports = this.errorMessages.isEmpty();
    assertEquals("Playwright did not start within 200 ms", message);
    assertFalse(playing);
    assertTrue(installerInterrupted, "the installation must be interrupted when the start is given up");
    assertTrue(noReports, this.errorMessages::toString);
  }

  @Test
  void refusesToStartWhileAnAbandonedStartIsAliveAndNeverLaunchesItsBrowser() throws InterruptedException {
    ExternalBrowsers.assumePlaywrightBrowser();
    final CountDownLatch finishInstalling = new CountDownLatch(1);
    final AtomicReference<Thread> abandoned = new AtomicReference<>();
    final AtomicInteger installs = new AtomicInteger();
    final Runnable installer = blockingInstaller(finishInstalling, abandoned, installs);
    final Duration startTimeout = Duration.ofMillis(300);
    final Duration stopTimeout = Duration.ofMillis(100);
    final PlaywrightPlayer created = new PlaywrightPlayer(installer, startTimeout, stopTimeout);
    final PlaywrightPlayer player = this.track(created);
    final BrowserSource source = this.page("/main");
    assertStartTimesOutThenIsRefused(player, source);
    finishAbandonedStart(finishInstalling, abandoned);

    // once the abandoned thread is gone, a start is allowed again and runs the installation
    final PlayerException retried = assertThrows(PlayerException.class, () -> player.start(source));
    final String retriedMessage = retried.getMessage();
    final int installCalls = installs.get();
    assertEquals("Failed to start Playwright: installing again", retriedMessage);
    assertEquals(2, installCalls);
  }

  private static void assertStartTimesOutThenIsRefused(final PlaywrightPlayer player, final BrowserSource source) {
    final PlayerException timeout = assertThrows(PlayerException.class, () -> player.start(source));
    final PlayerException refused = assertThrows(PlayerException.class, () -> player.start(source));
    final String timeoutMessage = timeout.getMessage();
    final String refusedMessage = refused.getMessage();
    assertEquals("Playwright did not start within 300 ms", timeoutMessage);
    assertEquals("The browser of the previous start is still shutting down", refusedMessage);
  }

  private static void finishAbandonedStart(final CountDownLatch finishInstalling, final AtomicReference<Thread> abandoned)
    throws InterruptedException {
    final Instant finished = Instant.now();
    finishInstalling.countDown();
    final Thread stale = abandoned.get();
    stale.join(10_000L);
    final boolean staleAlive = stale.isAlive();
    final List<ProcessHandle> orphans = processesStartedAfter(finished);
    final boolean noOrphans = orphans.isEmpty();
    assertFalse(staleAlive);
    assertTrue(noOrphans, () -> "the abandoned start launched " + orphans);
  }

  @Test
  void keepsTheInterruptWhenInterruptedWhileStarting() {
    // Keep the future incomplete: CompletableFuture.get may report an already completed failure
    // before checking the caller's interrupt. The test must actually reach its waiting path.
    final Runnable installer = () -> {
      final CountDownLatch waiting = new CountDownLatch(1);
      try {
        waiting.await();
      } catch (final InterruptedException exception) {
        final Thread installing = Thread.currentThread();
        installing.interrupt();
      }
    };
    final PlaywrightPlayer player = this.playerWithInstaller(installer, INSTALL_TIMEOUT);
    final BrowserSource source = this.page("/main");
    final Thread current = Thread.currentThread();
    current.interrupt();
    final PlayerException exception;
    try {
      exception = assertThrows(PlayerException.class, () -> player.start(source));
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Interrupted while starting Playwright", message);
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void acknowledgesScreencastFrames() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final CDPSession devToolsSession = mock(CDPSession.class);
    final JsonObject frame = frameJson(true, true);
    player.onFrame(devToolsSession, frame);
    final JsonObject expected = new JsonObject();
    expected.addProperty("sessionId", 4);
    verify(devToolsSession).send("Page.screencastFrameAck", expected);
  }

  @Test
  void acknowledgesNothingWithoutASessionAndSkipsFramesWithoutAnImage() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final CDPSession devToolsSession = mock(CDPSession.class);
    final JsonObject frame = frameJson(false, false);
    player.onFrame(devToolsSession, frame);
    verify(devToolsSession, never()).send(anyString(), any(JsonObject.class));
    final byte[] image = PlaywrightPlayer.readImage(frame);
    assertNull(image);
  }

  @Test
  void reportsFramesThatCannotBeReadInsteadOfThrowingIntoTheBrowser() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final CDPSession devToolsSession = mock(CDPSession.class);
    final JsonObject notBase64 = frameJson(true, false);
    notBase64.addProperty("data", "not base64!");
    final JsonObject wideSize = frameJson(false, true);
    final JsonObject metadata = wideSize.getAsJsonObject("metadata");
    metadata.addProperty("deviceWidth", "wide");
    player.onFrame(devToolsSession, notBase64);
    player.onFrame(devToolsSession, wideSize);
    final List<String> expectedMessages = List.of("Failed to decode a browser frame", "Failed to decode a browser frame");
    final Throwable first = this.errors.get(0);
    final Throwable second = this.errors.get(1);
    assertEquals(expectedMessages, this.errorMessages);
    assertInstanceOf(IllegalArgumentException.class, first);
    assertInstanceOf(NumberFormatException.class, second);
  }

  @Test
  void readsThePageSizeOfFrames() {
    final JsonObject complete = frameJson(true, false);
    final JsonObject missing = new JsonObject();
    final JsonObject primitive = new JsonObject();
    primitive.addProperty("metadata", 3);
    final JsonObject nested = new JsonObject();
    final JsonObject metadata = new JsonObject();
    final JsonArray notANumber = new JsonArray();
    metadata.add("deviceWidth", notANumber);
    nested.add("metadata", metadata);
    final int[] completeSize = PlaywrightPlayer.readPageSize(complete);
    final int[] missingSize = PlaywrightPlayer.readPageSize(missing);
    final int[] primitiveSize = PlaywrightPlayer.readPageSize(primitive);
    final int[] nestedSize = PlaywrightPlayer.readPageSize(nested);
    assertArrayEquals(new int[] { 960, 540 }, completeSize);
    assertArrayEquals(new int[] { 0, 0 }, missingSize);
    assertArrayEquals(new int[] { 0, 0 }, primitiveSize);
    assertArrayEquals(new int[] { 0, 0 }, nestedSize);
  }

  @Test
  void readsTheImageOfFrames() {
    final JsonObject frame = new JsonObject();
    frame.addProperty("data", "AAEC");
    final byte[] image = PlaywrightPlayer.readImage(frame);
    assertArrayEquals(new byte[] { 0, 1, 2 }, image);
  }

  @Test
  void reportsObjectsThatFailToClose() {
    final PlaywrightPlayer player = this.unstartedPlayer();
    final IllegalStateException failure = new IllegalStateException("connection lost");
    player.closeQuietly(() -> {
      throw failure;
    });
    player.closeQuietly(() -> {});
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    final int count = this.errorMessages.size();
    assertEquals("Failed to close Playwright", message);
    assertSame(failure, error);
    assertEquals(1, count);
  }

  void openSharedBrowser() {
    this.sharedBrowser = SharedBrowserCache.playwright();
  }

  void closeSharedBrowser() {
    final SharedPlaywright shared = this.sharedBrowser;
    this.sharedBrowser = null;
    if (shared != null) {
      SharedBrowserCache.finishedPlaywright(shared);
    }
  }
}

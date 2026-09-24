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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.browser.testing.TestPages;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonInput;
import org.opentest4j.AssertionFailedError;

/**
 * Tests {@link SeleniumPlayer}.
 *
 * <p>Fast tests use controlled transports and prepared events. The focused real-browser test classes compose this
 * fixture to exercise browser input and process lifecycle against pages on the loopback interface.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SharedBrowserCache.class)
final class SeleniumPlayerTest {

  // Chrome keeps windows at least 500 pixels wide, so the page and the frames have the same size only above that
  private static final int WIDTH = 640;
  private static final int HEIGHT = 360;
  private static final int GREEN = 0x00FF00;
  private static final int BLUE = 0x0000FF;
  private static final URI MOCK_PAGE = URI.create("http://127.0.0.1/mock");
  private static final long SPIN_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
  private static final long SPIN_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(10);

  private final List<SeleniumPlayer> players = new CopyOnWriteArrayList<>();
  private final List<String> errorMessages = new CopyOnWriteArrayList<>();
  private final List<Throwable> errors = new CopyOnWriteArrayList<>();
  private final AtomicInteger handleQueries = new AtomicInteger();
  private TestPages pages;
  private @Nullable SharedChrome sharedBrowser;

  private ChromeDriver chrome;
  private DevTools tools;
  private WebDriver.Window window;
  private WebDriver.TargetLocator locator;
  private AtomicReference<Set<String>> handles;
  private List<ChromeOptions> createdOptions;

  @BeforeEach
  void startPages() {
    this.players.clear();
    this.errorMessages.clear();
    this.errors.clear();
    this.pages = TestPages.start();
  }

  @AfterEach
  void releasePlayers() {
    for (final SeleniumPlayer player : this.players) {
      player.release();
    }
    this.pages.close();
  }

  private SeleniumPlayer track(final SeleniumPlayer player) {
    final BiConsumer<String, Throwable> handler = (message, error) -> {
      this.errorMessages.add(message);
      this.errors.add(error);
    };
    player.setExceptionHandler(handler);
    this.players.add(player);
    return player;
  }

  private SeleniumPlayer chromePlayer() {
    final SharedChrome shared = this.sharedBrowser;
    if (shared != null) {
      final SeleniumPlayer player = shared.player();
      return this.track(player);
    }
    ExternalBrowsers.assumeChrome();
    final String[] defaultArguments = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS.toArray(String[]::new);
    final SeleniumPlayer player = new SeleniumPlayer(defaultArguments);
    return this.track(player);
  }

  private BrowserSource page(final String path) {
    final URI uri = this.pages.uri(path);
    return BrowserSource.uri(uri, 80, WIDTH, HEIGHT, 1);
  }

  private void awaitKey(final String description, final String page, final String key) {
    this.awaitOrDump(description, () -> this.hasKey(page, key));
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

  private boolean hasKey(final String page, final String key) {
    final List<TestPages.PageEvent> events = this.pages.getEvents("keydown");
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

  private SeleniumPlayer mockedPlayer() {
    this.chrome = mock(ChromeDriver.class);
    final WebDriver.Options options = mock(WebDriver.Options.class);
    this.window = mock(WebDriver.Window.class);
    this.tools = mock(DevTools.class);
    this.locator = mock(WebDriver.TargetLocator.class);
    this.handles = new AtomicReference<>(Set.of("main"));
    this.createdOptions = new CopyOnWriteArrayList<>();
    when(this.chrome.manage()).thenReturn(options);
    when(options.window()).thenReturn(this.window);
    when(this.chrome.getWindowHandle()).thenReturn("main");
    when(this.chrome.getWindowHandles()).thenAnswer(_ -> {
      this.handleQueries.incrementAndGet();
      return this.handles.get();
    });
    when(this.chrome.getDevTools()).thenReturn(this.tools);
    when(this.chrome.switchTo()).thenReturn(this.locator);
    final SeleniumPlayer.DriverFactory factory = chromeOptions -> {
      this.createdOptions.add(chromeOptions);
      return this.chrome;
    };
    final SeleniumPlayer player = new SeleniumPlayer(factory, "--headless=new", "--mute-audio");
    return this.track(player);
  }

  private static BrowserSource mockSource() {
    return BrowserSource.uri(MOCK_PAGE, 70, WIDTH, HEIGHT, 2);
  }

  private static boolean startMocked(final SeleniumPlayer player) {
    final BrowserSource source = mockSource();
    return player.start(source);
  }

  private Consumer<SeleniumPlayer.ScreencastFrame> frameListener() {
    final ArgumentCaptor<Consumer<SeleniumPlayer.ScreencastFrame>> captor = ArgumentCaptor.captor();
    verify(this.tools, atLeast(1)).addListener(any(), captor.capture());
    return captor.getValue();
  }

  private List<Command<?>> sentCommands() {
    final ArgumentCaptor<Command<?>> captor = ArgumentCaptor.captor();
    verify(this.tools, atLeast(1)).send(captor.capture());
    return captor.getAllValues();
  }

  private static SeleniumPlayer.ScreencastFrame frame(final int rgb, final int sessionId) {
    final byte[] jpeg = Frames.jpeg(64, 36, rgb);
    final Base64.Encoder encoder = Base64.getEncoder();
    final String data = encoder.encodeToString(jpeg);
    return new SeleniumPlayer.ScreencastFrame(data, sessionId, WIDTH, HEIGHT);
  }

  private static SeleniumPlayer.ScreencastFrame readFrame(final String json) {
    final Json parser = new Json();
    final StringReader reader = new StringReader(json);
    try (final JsonInput input = parser.newInput(reader)) {
      return SeleniumPlayer.readFrame(input);
    }
  }

  /**
   * Creates a filter that records the center color of each frame and holds the first frame until it may proceed.
   *
   * @param entered     counted down when a frame reaches the filter
   * @param proceed     counted down to let the filter finish
   * @param colors      receives the center color of every frame
   * @param waitResults receives whether each wait ended because the filter was allowed to proceed
   * @return the filter
   */
  private static VideoFilter slowFilter(
    final CountDownLatch entered,
    final CountDownLatch proceed,
    final List<Integer> colors,
    final List<Boolean> waitResults
  ) {
    return (image, _) -> {
      final int[] pixels = image.getPixels();
      final int center = pixels[pixels.length / 2] & 0xFFFFFF;
      colors.add(center);
      entered.countDown();
      try {
        final boolean proceeded = proceed.await(10, TimeUnit.SECONDS);
        waitResults.add(proceeded);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
      return true;
    };
  }

  private static void attachFilter(final SeleniumPlayer player, final VideoFilter filter) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  void streamsThePageAndFollowsTabsThePageOpensAndCloses() {
    final SeleniumPlayer player = this.chromePlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final BrowserSource source = this.page("/main");
    final boolean started = player.start(source);
    assertTrue(started);

    Await.until("the red main page is streamed", () -> frames.lastShows(TestPages.MAIN_COLOR));
    final Frames.Frame first = frames.requireLast();
    final int width = first.getWidth();
    final int height = first.getHeight();
    assertEquals(WIDTH, width);
    assertEquals(HEIGHT, height);

    player.sendKeyEvent("o");
    this.awaitOrDump("the blue popup is streamed", () -> frames.lastShows(TestPages.POPUP_COLOR));
    player.sendKeyEvent("x");
    this.awaitKey("the popup receives the key", "popup", "x");
    this.awaitOrDump("the stream returns to the main page", () -> frames.lastShows(TestPages.MAIN_COLOR));
    player.sendKeyEvent("Enter");
    this.awaitKey("the main page receives input again", "main", "Enter");
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errorMessages::toString);
  }

  void forwardsEveryKindOfMouseInput() {
    final SeleniumPlayer player = this.chromePlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final BrowserSource source = this.page("/main");
    player.start(source);
    // the first frame reports the page size, which the coordinates are mapped onto
    Await.until("the first frame arrives", () -> frames.count() > 0);
    this.awaitOrDump("the page reports its size", () -> this.pages.count("size") > 0);

    // every event reaches the test server in its own request, so events are found by position rather than by order
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
    final SeleniumPlayer player = this.chromePlayer();
    final BrowserSource source = this.page("/main");
    player.start(source);
    player.sendKeyEvent("Enter");
    player.sendKeyEvent("ab");
    player.sendKeyEvent("ArrowLeft");
    this.awaitKey("the arrow key arrives", "main", "ArrowLeft");
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
    final SeleniumPlayer player = this.chromePlayer();
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
    this.awaitKey("input after the start arrives", "main", "z");
    final boolean earlyKey = this.hasKey("main", "q");
    final int clicks = this.pages.count("click");
    assertFalse(earlyKey);
    assertEquals(0, clicks);
  }

  void failsAndClosesChromeWhenThePageCannotBeOpened() throws IOException {
    final int port;
    final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
    try (final ServerSocket socket = new ServerSocket(0, 1, loopback)) {
      port = socket.getLocalPort();
    }
    final SeleniumPlayer player = this.chromePlayer();
    final URI unreachable = URI.create("http://127.0.0.1:" + port + "/");
    final BrowserSource source = BrowserSource.uri(unreachable, 80, WIDTH, HEIGHT, 1);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final boolean playing = player.isPlaying();
    final boolean explains = message.startsWith("Failed to open BrowserSource[" + unreachable);
    assertTrue(explains, message);
    assertFalse(playing);
  }

  @Test
  void stopsForwardingInputAfterRelease() {
    final SeleniumPlayer player = this.mockedPlayer();
    final BrowserSource source = mockSource();
    player.start(source);
    player.sendKeyEvent("a");
    // the positive control: input reaches Chrome while the player plays
    verify(this.chrome, timeout(5_000)).perform(anyCollection());

    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    final boolean playing = player.isPlaying();
    final boolean startedAgain = player.start(source);
    player.sendKeyEvent("b");
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(playing);
    assertFalse(startedAgain);
    verify(this.chrome, after(500).times(1)).perform(anyCollection());
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errorMessages::toString);
  }

  @Test
  void startsChromeWithTheArgumentsAndTheScreencastSettings() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    this.assertChromeArguments();
    final Dimension size = new Dimension(WIDTH, HEIGHT);
    verify(this.window).setSize(size);
    verify(this.chrome).get("http://127.0.0.1/mock");
    verify(this.tools).createSession("main");
    this.assertScreencastCommands();
  }

  private void assertChromeArguments() {
    final ChromeOptions options = this.createdOptions.getFirst();
    final Map<String, Object> capabilities = options.asMap();
    final Object chromeOptions = capabilities.get("goog:chromeOptions");
    final Map<?, ?> chromeMap = (Map<?, ?>) chromeOptions;
    final Object arguments = chromeMap.get("args");
    // Linux adds the arguments Chrome needs there, which are tested with ChromeArguments
    final List<String> platform = ChromeArguments.detectPlatformArguments();
    final List<String> requested = List.of("--headless=new", "--mute-audio");
    final List<String> expectedArguments = new ArrayList<>(requested);
    expectedArguments.addAll(platform);
    assertEquals(expectedArguments, arguments);
  }

  private void assertScreencastCommands() {
    final List<Command<?>> commands = this.sentCommands();
    final Command<?> front = commands.get(0);
    final Command<?> start = commands.get(1);
    final String frontMethod = front.getMethod();
    final String method = start.getMethod();
    final Map<String, Object> parameters = start.getParams();
    final Map<String, Object> expected = Map.of(
      "format",
      "jpeg",
      "quality",
      70,
      "maxWidth",
      WIDTH,
      "maxHeight",
      HEIGHT,
      "everyNthFrame",
      2
    );
    assertEquals("Page.bringToFront", frontMethod);
    assertEquals("Page.startScreencast", method);
    assertEquals(expected, parameters);
  }

  @Test
  void acknowledgesAndDeliversScreencastFrames() {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    startMocked(player);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame green = frame(GREEN, 7);
    listener.accept(green);
    Await.until("the green frame is delivered", () -> frames.lastShows(GREEN));
    final Frames.Frame delivered = frames.requireLast();
    final int width = delivered.getWidth();
    assertEquals(WIDTH, width);

    final List<Command<?>> commands = this.sentCommands();
    final Command<?> acknowledge = commands.getLast();
    final String method = acknowledge.getMethod();
    final Map<String, Object> parameters = acknowledge.getParams();
    final Map<String, Integer> expectedParameters = Map.of("sessionId", 7);
    assertEquals("Page.screencastFrameAck", method);
    assertEquals(expectedParameters, parameters);
  }

  @Test
  void dropsFramesWhoseAcknowledgementFails() {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    startMocked(player);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final WebDriverException closed = new WebDriverException("session closed");
    doThrow(closed).when(this.tools).send(any());
    final SeleniumPlayer.ScreencastFrame dropped = frame(BLUE, 1);
    listener.accept(dropped);
    doAnswer(_ -> null).when(this.tools).send(any());
    final SeleniumPlayer.ScreencastFrame kept = frame(GREEN, 2);
    listener.accept(kept);
    Await.until("the second frame is delivered", () -> frames.lastShows(GREEN));
    final int count = frames.count();
    assertEquals(1, count);
  }

  @Test
  void skipsFramesWithoutAnImage() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame empty = new SeleniumPlayer.ScreencastFrame("", 1, 0, 0);
    listener.accept(empty);
    final Runnable emptyNotification = capture.take();
    emptyNotification.run();
    assertEquals(0, frames.count(), "the empty notification is consumed before a later image can replace it");
    assertTrue(this.errorMessages.isEmpty(), this.errorMessages::toString);

    final SeleniumPlayer.ScreencastFrame green = frame(GREEN, 2);
    listener.accept(green);
    final Runnable imageNotification = capture.take();
    imageNotification.run();
    final int count = frames.count();
    assertEquals(1, count);
    assertTrue(frames.lastShows(GREEN));
  }

  @Test
  void reportsFramesThatAreNotBase64AndKeepsDecoding() {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    startMocked(player);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame broken = new SeleniumPlayer.ScreencastFrame("not base64!", 1, 0, 0);
    listener.accept(broken);
    Await.until("the broken frame is reported", () -> !this.errorMessages.isEmpty());
    final SeleniumPlayer.ScreencastFrame green = frame(GREEN, 2);
    listener.accept(green);
    Await.until("a later frame is still delivered", () -> frames.lastShows(GREEN));

    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    final int reports = this.errorMessages.size();
    assertEquals("Failed to decode a browser frame", message);
    assertInstanceOf(IllegalArgumentException.class, error);
    assertEquals(1, reports);
  }

  @Test
  void decodesOnlyTheNewestFrameWhenThePipelineFallsBehind() throws InterruptedException {
    final SeleniumPlayer player = this.mockedPlayer();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Integer> colors = new CopyOnWriteArrayList<>();
    final List<Boolean> waitResults = new CopyOnWriteArrayList<>();
    final VideoFilter slow = slowFilter(entered, proceed, colors, waitResults);
    attachFilter(player, slow);
    startMocked(player);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame first = frame(GREEN, 1);
    listener.accept(first);
    final boolean filtering = entered.await(10, TimeUnit.SECONDS);
    assertTrue(filtering);

    final SeleniumPlayer.ScreencastFrame skipped = frame(BLUE, 2);
    final SeleniumPlayer.ScreencastFrame newest = frame(TestPages.MAIN_COLOR, 3);
    listener.accept(skipped);
    listener.accept(newest);
    proceed.countDown();
    Await.until("the newest frame is delivered", () -> colors.size() >= 2);
    player.release();

    final int count = colors.size();
    final int last = colors.get(1);
    final boolean red = Frames.isNear(TestPages.MAIN_COLOR, last);
    final List<Boolean> expectedWaits = List.of(true, true);
    assertEquals(2, count);
    assertTrue(red);
    assertEquals(expectedWaits, waitResults, "the filter must be released by the test, not by its timeout");
  }

  @Test
  void dropsAnAlreadyDequeuedCaptureWhenItsConnectionEnds() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame green = frame(GREEN, 1);
    listener.accept(green);
    capture.take().run();
    assertEquals(1, frames.count(), "positive control: a current capture is delivered");

    listener.accept(green);
    final Runnable dequeued = capture.take();
    final Field connectionField = SeleniumPlayer.class.getDeclaredField("connection");
    connectionField.setAccessible(true);
    final Object connection = Objects.requireNonNull(connectionField.get(player));
    final Class<?> connectionClass = connection.getClass();
    final Field latestField = connectionClass.getDeclaredField("latestFrame");
    latestField.setAccessible(true);
    final Object latestValue = latestField.get(connection);
    final AtomicReference<?> latest = assertInstanceOf(AtomicReference.class, latestValue);
    assertSame(green, latest.get(), "the held notification retains the pending frame before release");
    player.release();
    assertNull(latest.get(), "a retained transport callback must not keep the last image alive after release");
    dequeued.run();
    assertEquals(1, frames.count(), "a capture already taken by its worker cannot outlive its connection");
  }

  @Test
  void ignoresADecodeNotificationWhoseFrameWasAlreadyConsumed() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    final SeleniumPlayer.ScreencastFrame green = frame(GREEN, 1);
    final SeleniumPlayer.ScreencastFrame blue = frame(BLUE, 2);
    listener.accept(green);
    final Runnable first = capture.take();
    // The worker has taken the first notification but the next frame is published before it reads the slot.
    listener.accept(blue);
    final Runnable second = capture.take();
    first.run();
    second.run();
    assertEquals(1, frames.count(), "the second notification must not duplicate an already-consumed frame");
    assertTrue(frames.lastShows(BLUE));
    assertTrue(this.errorMessages.isEmpty(), this.errorMessages::toString);
  }

  @Test
  void reportsExecutorRejectionOnlyWhileTheOwningBrowserIsPlaying() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    final RejectedExecutionException rejection = new RejectedExecutionException("saturated input executor");
    input.reject = rejection;
    player.sendKeyEvent("overflow");
    assertEquals(List.of("Browser input queue is full"), this.errorMessages);
    assertEquals(List.of(rejection), this.errors);

    // The browser fails after sendKeyEvent's initial check but before execute rejects the same owner's task.
    input.beforeReject = () -> player.fail("browser failed", new WebDriverException("disconnected"));
    player.sendKeyEvent("racing failure");
    assertEquals(List.of("Browser input queue is full", "browser failed"), this.errorMessages);
    verify(this.chrome, never()).perform(anyCollection());
  }

  @Test
  void dropsInputQueuedBeforeTheOwningBrowserFails() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    player.sendKeyEvent("accepted");
    input.take().run();
    verify(this.chrome).perform(anyCollection());
    player.sendKeyEvent("late");
    final Runnable queued = input.take();
    player.fail("browser failed", new WebDriverException("disconnected"));
    queued.run();
    verify(this.chrome, times(1)).perform(anyCollection());
    assertEquals(List.of("browser failed"), this.errorMessages);
  }

  @Test
  void doesNotDuplicateInputFailureAfterTheSameConnectionFails() throws Exception {
    final SeleniumPlayer player = this.mockedPlayer();
    final HeldExecutor capture = new HeldExecutor();
    final HeldExecutor input = new HeldExecutor();
    startWithHeldExecutors(player, capture, input);
    final WebDriverException failure = new WebDriverException("disconnected during input");
    doAnswer(_ -> {
      player.fail("browser failed", failure);
      throw failure;
    })
      .when(this.chrome)
      .perform(anyCollection());
    player.sendKeyEvent("racing failure");
    input.take().run();
    assertEquals(List.of("browser failed"), this.errorMessages);
    assertEquals(List.of(failure), this.errors);
  }

  private static void startWithHeldExecutors(final SeleniumPlayer player, final HeldExecutor capture, final HeldExecutor input) {
    // Factory substitution is scoped to this synchronous start. Existing executor tests prove real capacity/FIFO.
    try (final MockedStatic<SeleniumPlayer> factories = mockStatic(SeleniumPlayer.class, CALLS_REAL_METHODS)) {
      factories.when(SeleniumPlayer::createCaptureExecutor).thenReturn(capture.executor);
      factories.when(SeleniumPlayer::createInputExecutor).thenReturn(input.executor);
      startMocked(player);
    }
  }

  /** A deterministic executor boundary; tasks can be held after dequeue to model worker preemption. */
  private static final class HeldExecutor {

    private final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private @Nullable RejectedExecutionException reject;
    private Runnable beforeReject = () -> {};

    HeldExecutor() throws InterruptedException {
      doAnswer(invocation -> {
        final RejectedExecutionException rejected = this.reject;
        if (rejected != null) {
          this.beforeReject.run();
          throw rejected;
        }
        final Runnable task = invocation.getArgument(0);
        this.tasks.addLast(task);
        return null;
      })
        .when(this.executor)
        .execute(any(Runnable.class));
      // Teardown of the fake executor does not wait for the test-controlled tasks.
      when(this.executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    Runnable take() {
      return this.tasks.removeFirst();
    }
  }

  @Test
  void ignoresFramesAfterRelease() {
    final SeleniumPlayer player = this.mockedPlayer();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final Frames frames = Frames.attach(callback);
    startMocked(player);
    final Consumer<SeleniumPlayer.ScreencastFrame> listener = this.frameListener();
    player.release();
    final SeleniumPlayer.ScreencastFrame late = frame(GREEN, 1);
    listener.accept(late);
    final int count = frames.count();
    assertEquals(0, count);
    verify(this.tools).clearListeners();
    verify(this.tools).close();
    verify(this.chrome).quit();
  }

  @Test
  void closesChromeWhenNavigationFails() {
    final SeleniumPlayer player = this.mockedPlayer();
    final WebDriverException failure = new WebDriverException("net::ERR_NAME_NOT_RESOLVED");
    doThrow(failure).when(this.chrome).get(anyString());
    final BrowserSource source = mockSource();
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    final boolean playing = player.isPlaying();
    assertSame(failure, cause);
    assertFalse(playing);
    verify(this.chrome).quit();
  }

  @Test
  void failedNavigationKeepsItsCauseWhenCleanupReportingThrows() {
    final SeleniumPlayer player = this.mockedPlayer();
    final WebDriverException navigation = new WebDriverException("navigation failed");
    final WebDriverException cleanup = new WebDriverException("quit failed");
    final IllegalStateException handler = new IllegalStateException("handler failed");
    doThrow(navigation).when(this.chrome).get(anyString());
    doThrow(cleanup).when(this.chrome).quit();
    player.setExceptionHandler((_, _) -> {
      throw handler;
    });
    final BrowserSource source = mockSource();
    final PlayerException failure = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = failure.getCause();
    final Throwable[] suppressed = failure.getSuppressed();
    assertSame(navigation, cause);
    assertArrayEquals(new Throwable[] { handler }, suppressed);
    verify(this.chrome).quit();
  }

  @Test
  void closesChromeAndDevToolsEvenWhenListenerCleanupAndReportingThrow() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    final IllegalStateException listeners = new IllegalStateException("listeners failed");
    final IllegalStateException handler = new IllegalStateException("handler failed");
    doThrow(listeners).when(this.tools).clearListeners();
    player.setExceptionHandler((_, _) -> {
      throw handler;
    });
    final IllegalStateException failure = assertThrows(IllegalStateException.class, player::release);
    assertSame(handler, failure);
    verify(this.tools).close();
    verify(this.chrome).quit();
  }

  @Test
  void preservesMultipleCleanupFailuresAndStillQuitsWhenTheReporterThrowsAnError() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    final IllegalStateException listeners = new IllegalStateException("listeners failed");
    final IllegalArgumentException close = new IllegalArgumentException("close failed");
    final AssertionError reporter = new AssertionError("reporter failed");
    doThrow(listeners).when(this.tools).clearListeners();
    doThrow(close).when(this.tools).close();
    player.setExceptionHandler((_, failure) -> {
      assertSame(listeners, failure);
      final Throwable[] suppressed = failure.getSuppressed();
      assertArrayEquals(new Throwable[] { close }, suppressed);
      throw reporter;
    });
    final AssertionError failure = assertThrows(AssertionError.class, player::release);
    assertSame(reporter, failure);
    verify(this.chrome).quit();
  }

  @Test
  void doesNotSuppressACleanupFailureUnderItself() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    final IllegalStateException same = new IllegalStateException("same failure");
    doThrow(same).when(this.tools).clearListeners();
    doThrow(same).when(this.tools).close();
    assertTrue(player.release());
    assertEquals(List.of(same), this.errors);
    assertEquals(0, same.getSuppressed().length);
    verify(this.chrome).quit();
  }

  @Test
  void tabMonitoringParksBetweenDriverQueries() {
    final SeleniumPlayer player = this.mockedPlayer();
    final AtomicReference<Thread> monitor = new AtomicReference<>();
    when(this.chrome.getWindowHandles()).thenAnswer(_ -> {
      monitor.set(Thread.currentThread());
      return Set.of("main");
    });
    startMocked(player);
    Await.until("the tab monitor waits between requests rather than busy-polling Chrome", () -> {
      final Thread current = monitor.get();
      if (current == null || current.getState() != Thread.State.TIMED_WAITING) {
        return false;
      }
      final StackTraceElement[] stack = current.getStackTrace();
      for (final StackTraceElement frame : stack) {
        if (frame.getClassName().equals(SeleniumPlayer.class.getName()) && frame.getMethodName().equals("monitorTabs")) {
          return true;
        }
      }
      return false;
    });
  }

  @Test
  void failsWhenChromeCannotStart() {
    final WebDriverException failure = new WebDriverException("chrome not reachable");
    final SeleniumPlayer.DriverFactory factory = _ -> {
      throw failure;
    };
    final SeleniumPlayer created = new SeleniumPlayer(factory);
    final SeleniumPlayer player = this.track(created);
    final BrowserSource source = mockSource();
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean explains = message.startsWith("Failed to start Chrome: chrome not reachable");
    assertTrue(explains, message);
    assertSame(failure, cause);
  }

  @Test
  void reportsFailuresToCloseTheBrowser() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    final IllegalStateException devToolsFailure = new IllegalStateException("socket closed");
    final WebDriverException quitFailure = new WebDriverException("chrome crashed");
    doThrow(devToolsFailure).when(this.tools).close();
    doThrow(quitFailure).when(this.chrome).quit();
    final boolean released = player.release();
    final List<String> expectedMessages = List.of("Failed to close the DevTools session", "Failed to quit Chrome");
    final List<Throwable> expectedErrors = List.of(devToolsFailure, quitFailure);
    assertTrue(released);
    assertEquals(expectedMessages, this.errorMessages);
    assertEquals(expectedErrors, this.errors);
  }

  @Test
  void followsNewTabsAndReturnsWhenTheyClose() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    this.handles.set(Set.of("main", "popup"));
    verify(this.locator, timeout(5_000)).window("popup");
    verify(this.tools, timeout(5_000)).createSession("popup");
    verify(this.tools, timeout(5_000)).disconnectSession();
    this.handles.set(Set.of("main"));
    verify(this.locator, timeout(5_000)).window("main");
    verify(this.tools, timeout(5_000).times(2)).createSession("main");

    final int queriesBefore = this.handleQueries.get();
    this.handles.set(Set.of());
    // the monitor keeps polling, but with no tab left it has nothing to switch to
    Await.until("the monitor sees the empty tab list twice", () -> this.handleQueries.get() >= queriesBefore + 2);
    verify(this.locator, times(2)).window(anyString());
    verify(this.tools, times(3)).createSession(anyString());
    verify(this.tools, times(2)).clearListeners();
    final boolean playing = player.isPlaying();
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(playing);
    assertTrue(noErrors, this.errorMessages::toString);
  }

  @Test
  void rediscoversReusedTabHandlesAfterRestart() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    this.handles.set(Set.of("main", "popup"));
    verify(this.locator, timeout(5_000)).window("popup");
    player.fail("old browser failed", new WebDriverException("disconnected"));
    final boolean restarted = startMocked(player);
    assertTrue(restarted);
    verify(this.locator, timeout(5_000).times(2)).window("popup");
  }

  @Test
  void stopsFollowingTabsBeforeTheDevToolsSessionIsClosed() throws InterruptedException {
    final SeleniumPlayer player = this.mockedPlayer();
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicReference<Thread> pollingThread = new AtomicReference<>();
    final AtomicBoolean interrupted = new AtomicBoolean();
    // the monitor is inside Chrome when the player is released, and a new tab appears before Chrome answers
    when(this.chrome.getWindowHandles()).thenAnswer(_ -> {
      final Thread monitor = Thread.currentThread();
      pollingThread.set(monitor);
      entered.countDown();
      final long deadline = System.nanoTime() + SPIN_LIMIT_NANOS;
      while (!monitor.isInterrupted() && System.nanoTime() - deadline < 0) {
        LockSupport.parkNanos(SPIN_NANOS);
      }
      interrupted.set(monitor.isInterrupted());
      return Set.of("main", "popup");
    });
    startMocked(player);
    final boolean polling = entered.await(10, TimeUnit.SECONDS);
    assertTrue(polling);

    player.release();
    final Thread monitor = Objects.requireNonNull(pollingThread.get());
    assertTrue(monitor.isDaemon(), "tab discovery must not keep the JVM alive");
    assertTrue(interrupted.get(), "release interrupts the pending tab query");
    assertFalse(monitor.isAlive(), "release waits for tab discovery to finish before closing DevTools");
    verify(this.locator, never()).window("popup");
    verify(this.tools, never()).createSession("popup");
    final InOrder order = inOrder(this.tools);
    order.verify(this.tools).createSession("main");
    order.verify(this.tools).close();
  }

  @Test
  void reportsTabsThatCannotBeFollowed() {
    final SeleniumPlayer player = this.mockedPlayer();
    final WebDriverException failure = new WebDriverException("no such window");
    when(this.chrome.getWindowHandles()).thenThrow(failure);
    startMocked(player);
    Await.until("the failure is reported", () -> !this.errorMessages.isEmpty());
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    final boolean playing = player.isPlaying();
    assertEquals("Failed to follow a new browser tab", message);
    assertSame(failure, error);
    assertTrue(playing, "a tab that cannot be followed does not end the session");
  }

  /**
   * Creates an answer for the tab list that throws while the browser is crashed and lists the tabs otherwise.
   *
   * @param crashed whether the browser is crashed
   * @param lost    the failure of a crashed browser
   * @return the answer
   */
  private Answer<Set<String>> crashingTabs(final AtomicBoolean crashed, final NoSuchSessionException lost) {
    return _ -> {
      this.handleQueries.incrementAndGet();
      final boolean down = crashed.get();
      if (down) {
        throw lost;
      }
      return this.handles.get();
    };
  }

  @Test
  void stopsPlayingWhenTheBrowserSessionIsLostAndCanStartAgain() {
    final SeleniumPlayer player = this.mockedPlayer();
    final NoSuchSessionException lost = new NoSuchSessionException("invalid session id");
    final AtomicBoolean crashed = new AtomicBoolean(true);
    final Answer<Set<String>> tabs = this.crashingTabs(crashed, lost);
    when(this.chrome.getWindowHandles()).thenAnswer(tabs);
    startMocked(player);
    Await.until("the player stops playing", () -> !player.isPlaying());
    verify(this.chrome, after(1_200).times(1)).getWindowHandles();
    final List<String> expectedMessages = List.of("The browser session was lost");
    final List<Throwable> expectedErrors = List.of(lost);
    assertEquals(expectedMessages, this.errorMessages);
    assertEquals(expectedErrors, this.errors);

    crashed.set(false);
    final boolean restarted = startMocked(player);
    final boolean playing = player.isPlaying();
    assertTrue(restarted);
    assertTrue(playing);
    // the lost browser is shut down before the new one starts
    verify(this.chrome, times(1)).quit();
    verify(this.chrome, times(2)).get("http://127.0.0.1/mock");
  }

  @Test
  void doesNotReportTabFailuresCausedByARelease() {
    final SeleniumPlayer player = this.mockedPlayer();
    final CountDownLatch failed = new CountDownLatch(1);
    when(this.chrome.getWindowHandles()).thenAnswer(_ -> {
      player.release();
      failed.countDown();
      throw new WebDriverException("session deleted");
    });
    startMocked(player);
    Await.until("the tab monitor fails", () -> failed.getCount() == 0);
    verify(this.chrome, timeout(5_000)).quit();
    verify(this.chrome, after(1_200).times(1)).getWindowHandles();
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(noErrors, this.errorMessages::toString);
  }

  @Test
  void keepsTheInterruptWhenReleasedFromAnInterruptedThread() {
    final SeleniumPlayer player = this.mockedPlayer();
    startMocked(player);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean released;
    try {
      released = player.release();
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }
    assertTrue(released);
    verify(this.chrome).quit();
  }

  @Test
  void reportsInputThatFailsToReachTheBrowser() {
    final SeleniumPlayer player = this.mockedPlayer();
    final WebDriverException failure = new WebDriverException("stale element");
    doThrow(failure).when(this.chrome).perform(anyCollection());
    startMocked(player);
    player.sendKeyEvent("a");
    Await.until("the failure is reported", () -> !this.errorMessages.isEmpty());
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("Failed to forward input to the browser", message);
    assertSame(failure, error);
  }

  @Test
  void doesNotReportInputFailuresAfterRelease() throws InterruptedException {
    final SeleniumPlayer player = this.mockedPlayer();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    final AtomicBoolean releasedInTime = new AtomicBoolean();
    doAnswer(_ -> {
      entered.countDown();
      final boolean inTime = released.await(10, TimeUnit.SECONDS);
      releasedInTime.set(inTime);
      throw new WebDriverException("browser closed");
    })
      .when(this.chrome)
      .perform(anyCollection());
    startMocked(player);
    player.sendMouseEvent(MouseClick.LEFT, 10, 10);
    final boolean performing = entered.await(10, TimeUnit.SECONDS);
    assertTrue(performing);

    final Thread releaser = new Thread(player::release, "releaser");
    releaser.start();
    Await.until("the player stops playing", () -> !player.isPlaying());
    released.countDown();
    releaser.join(10_000L);
    final boolean inputReleased = releasedInTime.get();
    final boolean noErrors = this.errorMessages.isEmpty();
    assertTrue(inputReleased, "the input must be released by the test, not by its timeout");
    assertTrue(noErrors, this.errorMessages::toString);
  }

  @Test
  void ignoresInputWhoseSubmissionRacesWithExecutorShutdown() throws InterruptedException {
    final SeleniumPlayer original = this.mockedPlayer();
    final SeleniumPlayer player = spy(original);
    this.players.add(player);
    startMocked(player);
    final CountDownLatch checked = new CountDownLatch(1);
    final CountDownLatch submit = new CountDownLatch(1);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread sender = new Thread(
      () -> {
        try {
          player.sendKeyEvent("late");
        } catch (final Throwable exception) {
          failure.set(exception);
        }
      },
      "late-input-submitter"
    );
    doAnswer(invocation -> {
      final Thread caller = Thread.currentThread();
      if (caller.equals(sender)) {
        checked.countDown();
        final boolean ready = submit.await(10, TimeUnit.SECONDS);
        assertTrue(ready);
        return true;
      }
      return invocation.callRealMethod();
    })
      .when(player)
      .canForwardInput();
    try {
      sender.start();
      final boolean checkedBeforeRelease = checked.await(10, TimeUnit.SECONDS);
      assertTrue(checkedBeforeRelease);
      player.release();
      submit.countDown();
      sender.join(10_000L);
      final boolean submitting = sender.isAlive();
      final Throwable thrown = failure.get();
      final boolean noErrors = this.errorMessages.isEmpty();
      assertFalse(submitting);
      assertNull(thrown, "input checked before release may reach an executor already shut down");
      assertTrue(noErrors, this.errorMessages::toString);
      verify(this.chrome, never()).perform(anyCollection());
    } finally {
      submit.countDown();
      sender.join(10_000L);
    }
  }

  @Test
  void discardsQueuedInputWhenReleaseOvertakesIt() throws InterruptedException {
    final SeleniumPlayer player = this.mockedPlayer();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    doAnswer(_ -> {
      entered.countDown();
      final boolean finished = finish.await(10, TimeUnit.SECONDS);
      assertTrue(finished);
      return null;
    })
      .when(this.chrome)
      .perform(anyCollection());
    startMocked(player);
    player.sendKeyEvent("first");
    final boolean performing = entered.await(10, TimeUnit.SECONDS);
    assertTrue(performing);
    player.sendKeyEvent("queued");
    final Thread releaser = new Thread(player::release, "release-queued-input");
    try {
      releaser.start();
      Await.until("the player stops before the queued action starts", () -> !player.isPlaying());
      finish.countDown();
      releaser.join(10_000L);
      final boolean stillReleasing = releaser.isAlive();
      assertFalse(stillReleasing);
      verify(this.chrome, times(1)).perform(anyCollection());
      final boolean noErrors = this.errorMessages.isEmpty();
      assertTrue(noErrors, this.errorMessages::toString);
    } finally {
      finish.countDown();
      releaser.join(10_000L);
    }
  }

  @Test
  void readsScreencastEvents() {
    final SeleniumPlayer.ScreencastFrame frame = readFrame(
      "{\"data\":\"AAEC\",\"sessionId\":12,\"metadata\":{\"deviceWidth\":800,\"deviceHeight\":600.0}}"
    );
    final String data = frame.getData();
    final int sessionId = frame.getSessionId();
    final int width = frame.getPageWidth();
    final int height = frame.getPageHeight();
    assertEquals("AAEC", data);
    assertEquals(12, sessionId);
    assertEquals(800, width);
    assertEquals(600, height);
  }

  @Test
  void readsMissingOrMalformedValuesAsEmpty() {
    final SeleniumPlayer.ScreencastFrame nothing = readFrame("null");
    final SeleniumPlayer.ScreencastFrame malformed = readFrame("{\"data\":5,\"sessionId\":\"x\",\"metadata\":{\"deviceWidth\":\"wide\"}}");
    final SeleniumPlayer.ScreencastFrame noMetadata = readFrame("{\"data\":\"AA\",\"metadata\":7}");
    final String nothingData = nothing.getData();
    final int nothingWidth = nothing.getPageWidth();
    final String malformedData = malformed.getData();
    final int malformedSession = malformed.getSessionId();
    final int malformedWidth = malformed.getPageWidth();
    final int malformedHeight = malformed.getPageHeight();
    final int noMetadataWidth = noMetadata.getPageWidth();
    final String noMetadataData = noMetadata.getData();
    assertEquals("", nothingData);
    assertEquals(0, nothingWidth);
    assertEquals("", malformedData);
    assertEquals(0, malformedSession);
    assertEquals(0, malformedWidth);
    assertEquals(0, malformedHeight);
    assertEquals(0, noMetadataWidth);
    assertEquals("AA", noMetadataData);
  }

  void openSharedBrowser() {
    this.sharedBrowser = SharedBrowserCache.chrome();
  }

  void closeSharedBrowser() {
    final SharedChrome shared = this.sharedBrowser;
    this.sharedBrowser = null;
    if (shared != null) {
      SharedBrowserCache.finishedChrome(shared);
    }
  }
}

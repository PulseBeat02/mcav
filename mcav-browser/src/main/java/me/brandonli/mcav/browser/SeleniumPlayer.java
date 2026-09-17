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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonInput;

/**
 * A {@link BrowserPlayer} that drives Chrome through Selenium and streams the page with the DevTools screencast.
 *
 * <p>Chrome sends a JPEG frame whenever the page changes. Frames are acknowledged right away and the newest one is
 * decoded on a capture thread, so a slow pipeline never holds Chrome back. Input is replayed with Selenium actions on
 * an input thread. A monitor follows tabs the page opens and returns to a remaining tab when the followed one closes;
 * when the browser session is lost, as after a crash of Chrome, the player stops playing and can be started again.
 */
public final class SeleniumPlayer extends AbstractBrowserPlayer {

  private static final Base64.Decoder BASE64 = Base64.getDecoder();
  private static final long TAB_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
  private static final String SCREENCAST_FRAME_EVENT = "Page.screencastFrame";

  private final DriverFactory driverFactory;
  private final List<String> arguments;
  private final AtomicReference<@Nullable ScreencastFrame> latestFrame;
  private final Set<String> knownHandles;

  private volatile @Nullable Connection connection;
  private volatile @Nullable DevTools devTools;
  private volatile @Nullable String currentHandle;
  private @Nullable Thread tabThread;

  SeleniumPlayer(final String... arguments) {
    this(SeleniumPlayer::createDriver, arguments);
  }

  /**
   * Constructs a player that starts Chrome with the given factory, so tests can replace Chrome.
   *
   * @param driverFactory starts Chrome with the options of the player
   * @param arguments     the command-line arguments of Chrome, resolved as {@link BrowserPlayer#selenium(String...)}
   *                      describes
   */
  @VisibleForTesting
  SeleniumPlayer(final DriverFactory driverFactory, final String... arguments) {
    this.driverFactory = driverFactory;
    this.arguments = ChromeArguments.resolve(arguments);
    this.latestFrame = new AtomicReference<>();
    this.knownHandles = new HashSet<>();
  }

  private static ChromeDriver createDriver(final ChromeOptions options) {
    final ChromeDriverService service = ChromeDriverProvider.getService();
    return new ChromeDriver(service, options);
  }

  /**
   * Starts Chrome, opens the page, starts the screencast of its tab, and starts the thread that follows new tabs.
   * Chrome is closed again if the page cannot be opened.
   *
   * @param source the page and the screencast settings
   * @throws PlayerException if Chrome cannot be started or the page cannot be opened
   */
  @Override
  protected void open(final BrowserSource source) {
    final ChromeDriver chrome = this.launchChrome();
    final ExecutorService capture = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "mcav-browser-capture"));
    final ExecutorService input = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "mcav-browser-input"));
    final Connection opened = new Connection(chrome, capture, input);
    this.connection = opened;
    try {
      this.navigate(chrome, source);
    } catch (final RuntimeException exception) {
      // any failure here, not only a WebDriverException, would otherwise leave a live Chrome process and two
      // executors behind
      this.close();
      final String message = exception.getMessage();
      throw new PlayerException("Failed to open " + source + ": " + message, exception);
    }
    this.startTabMonitor(opened, source);
  }

  private ChromeDriver launchChrome() {
    final ChromeOptions options = new ChromeOptions();
    options.addArguments(this.arguments);
    try {
      return this.driverFactory.create(options);
    } catch (final WebDriverException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to start Chrome: " + message, exception);
    }
  }

  private void navigate(final ChromeDriver chrome, final BrowserSource source) {
    this.resizeWindow(chrome, source);
    final String resource = source.getResource();
    chrome.get(resource);
    final String handle = chrome.getWindowHandle();
    synchronized (this.knownHandles) {
      this.knownHandles.add(handle);
    }
    this.currentHandle = handle;
    this.startScreencast(chrome, source, handle);
  }

  private void startTabMonitor(final Connection owner, final BrowserSource source) {
    final Thread monitor = new Thread(() -> this.monitorTabs(owner, source), "mcav-browser-tabs");
    monitor.setDaemon(true);
    this.tabThread = monitor;
    monitor.start();
  }

  private void resizeWindow(final ChromeDriver chrome, final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final Dimension size = new Dimension(width, height);
    final WebDriver.Options options = chrome.manage();
    final WebDriver.Window window = options.window();
    window.setSize(size);
  }

  private void startScreencast(final ChromeDriver chrome, final BrowserSource source, final String handle) {
    final DevTools previous = this.devTools;
    if (previous != null) {
      // the previous tab may be closed, so its session is dropped before anything is sent to it
      previous.disconnectSession();
    }
    final DevTools tools = chrome.getDevTools();
    // without a handle the session attaches to the first tab Chrome lists, which is not the followed tab
    tools.createSession(handle);
    if (previous != null) {
      // clearing sends commands to the session, which must belong to an open tab
      previous.clearListeners();
    }
    final Event<ScreencastFrame> frameEvent = new Event<>(SCREENCAST_FRAME_EVENT, SeleniumPlayer::readFrame);
    tools.addListener(frameEvent, frame -> this.onFrame(tools, frame));
    // Chrome paints no frames for background tabs, and switching windows does not bring the tab to the front
    final Map<String, Object> noParameters = Map.of();
    final Command<Void> bringToFront = new Command<>("Page.bringToFront", noParameters);
    tools.send(bringToFront);
    final Map<String, Object> parameters = createScreencastParameters(source);
    final Command<Void> start = new Command<>("Page.startScreencast", parameters);
    tools.send(start);
    this.devTools = tools;
  }

  private static Map<String, Object> createScreencastParameters(final BrowserSource source) {
    final int quality = source.getScreencastQuality();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int nthFrame = source.getScreencastNthFrame();
    return Map.of("format", "jpeg", "quality", quality, "maxWidth", width, "maxHeight", height, "everyNthFrame", nthFrame);
  }

  /**
   * Reads a {@code Page.screencastFrame} event. Missing or malformed values are read as empty data and zero sizes.
   *
   * @param input the JSON of the event parameters
   * @return the frame
   */
  @VisibleForTesting
  static ScreencastFrame readFrame(final JsonInput input) {
    final Map<String, Object> raw = input.read(Json.MAP_TYPE);
    if (raw == null) {
      return new ScreencastFrame("", 0, 0, 0);
    }
    final Object data = raw.get("data");
    final Object sessionId = raw.get("sessionId");
    final Object metadata = raw.get("metadata");
    int width = 0;
    int height = 0;
    if (metadata instanceof final Map<?, ?> map) {
      final Object deviceWidth = map.get("deviceWidth");
      final Object deviceHeight = map.get("deviceHeight");
      width = readInt(deviceWidth);
      height = readInt(deviceHeight);
    }
    final String encoded = data instanceof final String text ? text : "";
    final int session = readInt(sessionId);
    return new ScreencastFrame(encoded, session, width, height);
  }

  private static int readInt(final @Nullable Object value) {
    if (value instanceof final Number number) {
      return number.intValue();
    }
    return 0;
  }

  private void onFrame(final DevTools tools, final ScreencastFrame frame) {
    // acknowledge first so Chrome keeps sending frames even while a frame is still being decoded
    final int sessionId = frame.getSessionId();
    final Map<String, Object> parameters = Map.of("sessionId", sessionId);
    final Command<Void> acknowledge = new Command<>("Page.screencastFrameAck", parameters);
    try {
      tools.send(acknowledge);
    } catch (final WebDriverException exception) {
      return;
    }
    this.latestFrame.set(frame);
    final Connection current = this.connection;
    if (current != null) {
      current.capture.execute(this::decodeLatestFrame);
    }
  }

  private void decodeLatestFrame() {
    final ScreencastFrame frame = this.latestFrame.getAndSet(null);
    if (frame == null) {
      return;
    }
    final String data = frame.getData();
    if (data.isEmpty()) {
      return;
    }
    final byte[] encoded;
    try {
      encoded = BASE64.decode(data);
    } catch (final RuntimeException exception) {
      this.report("Failed to decode a browser frame", exception);
      return;
    }
    final int pageWidth = frame.getPageWidth();
    final int pageHeight = frame.getPageHeight();
    this.deliverFrame(encoded, pageWidth, pageHeight);
  }

  private void monitorTabs(final Connection owner, final BrowserSource source) {
    while (true) {
      // closing the player clears the connection, then interrupts this thread, which ends the wait at once
      LockSupport.parkNanos(TAB_POLL_NANOS);
      final Connection active = this.connection;
      if (active != owner) {
        return;
      }
      try {
        this.followTabs(owner, source);
      } catch (final NoSuchSessionException exception) {
        this.fail("The browser session was lost", exception);
        return;
      } catch (final WebDriverException exception) {
        final Connection current = this.connection;
        if (current == owner) {
          this.report("Failed to follow a new browser tab", exception);
        }
      }
    }
  }

  private void followTabs(final Connection owner, final BrowserSource source) {
    final ChromeDriver chrome = owner.driver;
    final Set<String> handles = chrome.getWindowHandles();
    final Connection current = this.connection;
    if (current != owner) {
      // the player is closing, and the DevTools session is about to be closed
      return;
    }

    final String target = this.chooseTab(handles);
    if (target == null) {
      return;
    }

    final WebDriver.TargetLocator locator = chrome.switchTo();
    locator.window(target);
    this.currentHandle = target;
    this.startScreencast(chrome, source, target);
  }

  /**
   * Picks the tab to stream: the newest tab the page opened since the last poll, or a remaining tab if the followed
   * tab was closed.
   *
   * @param handles the open tabs, oldest first
   * @return the tab to switch to, or null to stay on the followed tab
   */
  private @Nullable String chooseTab(final Set<String> handles) {
    final String opened = this.recordHandles(handles);
    if (opened != null) {
      return opened;
    }

    // the handle is set by navigate before the monitor starts
    final String followed = Objects.requireNonNull(this.currentHandle, "The first tab is known");
    return findRemainingTab(handles, followed);
  }

  /**
   * Remembers the open tabs and forgets the closed ones.
   *
   * @param handles the open tabs, oldest first
   * @return the newest tab that was not known before, or null if no tab was opened
   */
  private @Nullable String recordHandles(final Set<String> handles) {
    String opened = null;
    synchronized (this.knownHandles) {
      for (final String handle : handles) {
        final boolean known = this.knownHandles.contains(handle);
        if (!known) {
          this.knownHandles.add(handle);
          opened = handle;
        }
      }
      this.knownHandles.retainAll(handles);
    }
    return opened;
  }

  /**
   * Picks the tab to return to when the followed tab was closed.
   *
   * @param handles the open tabs, oldest first
   * @param current the followed tab
   * @return the newest open tab, or null if the followed tab is still open or no tab is left
   */
  private static @Nullable String findRemainingTab(final Set<String> handles, final String current) {
    final boolean stillOpen = handles.contains(current);
    if (stillOpen || handles.isEmpty()) {
      return null;
    }
    final List<String> remaining = new ArrayList<>(handles);
    final int last = remaining.size() - 1;
    return remaining.get(last);
  }

  /**
   * Moves the mouse pointer of Chrome. The move is replayed on the input thread; it is ignored while the player is
   * not playing.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   */
  @Override
  public void moveMouse(final int x, final int y) {
    final Connection current = this.connection;
    if (current == null || !this.canForwardInput()) {
      return;
    }
    final int[] translated = this.translateCoordinates(x, y);
    this.submitInput(current, actions -> actions.moveToLocation(translated[0], translated[1]));
  }

  /**
   * Moves the mouse pointer of Chrome and performs a click. The click is replayed on the input thread; it is ignored
   * while the player is not playing.
   *
   * @param type the kind of click
   * @param x    the x coordinate in the streamed frame
   * @param y    the y coordinate in the streamed frame
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    checkMouseClick(type);
    final Connection current = this.connection;
    if (current == null || !this.canForwardInput()) {
      return;
    }
    final int[] translated = this.translateCoordinates(x, y);
    this.submitInput(current, actions -> {
        final Actions moved = actions.moveToLocation(translated[0], translated[1]);
        return click(moved, type);
      });
  }

  private static Actions click(final Actions actions, final MouseClick type) {
    return switch (type) {
      case LEFT -> actions.click();
      case RIGHT -> actions.contextClick();
      case DOUBLE -> actions.doubleClick();
      case HOLD -> actions.clickAndHold();
      case RELEASE -> actions.release();
    };
  }

  /**
   * Types text into the focused element of Chrome, translating key names such as {@code Enter} into WebDriver keys.
   * The text is typed on the input thread; it is ignored while the player is not playing.
   *
   * @param text the text or key name
   */
  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final Connection current = this.connection;
    if (current == null || !this.canForwardInput()) {
      return;
    }
    final String keys = PlaywrightKeys.toSeleniumKeys(text);
    this.submitInput(current, actions -> actions.sendKeys(keys));
  }

  private void submitInput(final Connection current, final Function<Actions, Actions> builder) {
    current.input.execute(() -> {
      try {
        final Actions actions = new Actions(current.driver);
        final Actions configured = builder.apply(actions);
        final Action action = configured.build();
        action.perform();
      } catch (final WebDriverException exception) {
        final boolean playing = this.isPlaying();
        if (playing) {
          this.report("Failed to forward input to the browser", exception);
        }
      }
    });
  }

  /**
   * Stops the tab monitor, closes the DevTools session, stops the capture and input threads, and quits Chrome.
   * Failures are reported to the exception handler instead of being thrown.
   */
  @Override
  protected void close() {
    // the tab monitor stops once the connection is gone, and must be stopped before the DevTools session it uses
    final Connection current = this.connection;
    this.connection = null;
    this.stopTabMonitor();
    this.closeDevTools();
    if (current != null) {
      ExecutorUtils.shutdownExecutorGracefully(current.input, SHUTDOWN_TIMEOUT);
      ExecutorUtils.shutdownExecutorGracefully(current.capture, SHUTDOWN_TIMEOUT);
      this.quitDriver(current.driver);
    }
    this.latestFrame.set(null);
    synchronized (this.knownHandles) {
      this.knownHandles.clear();
    }
  }

  private void stopTabMonitor() {
    final Thread monitor = this.tabThread;
    if (monitor == null) {
      return;
    }
    this.tabThread = null;
    monitor.interrupt();
    final Thread caller = Thread.currentThread();
    // the monitor closes the player itself when a tab action releases it, and cannot wait for itself; threads compare
    // by identity, so equals tells whether the caller is the monitor
    final boolean calledByMonitor = monitor.equals(caller);
    if (calledByMonitor) {
      return;
    }
    final long timeoutMillis = SHUTDOWN_TIMEOUT.toMillis();
    try {
      monitor.join(timeoutMillis);
    } catch (final InterruptedException exception) {
      caller.interrupt();
    }
  }

  private void closeDevTools() {
    final DevTools tools = this.devTools;
    if (tools == null) {
      return;
    }
    try {
      tools.clearListeners();
      tools.close();
    } catch (final RuntimeException exception) {
      this.report("Failed to close the DevTools session", exception);
    }
    this.devTools = null;
  }

  private void quitDriver(final ChromeDriver chrome) {
    try {
      chrome.quit();
    } catch (final WebDriverException exception) {
      this.report("Failed to quit Chrome", exception);
    }
  }

  /**
   * Starts Chrome.
   */
  @FunctionalInterface
  interface DriverFactory {
    /**
     * Starts Chrome with the given options.
     *
     * @param options the options, including the command-line arguments
     * @return the driver of the started browser
     * @throws WebDriverException if Chrome cannot be started
     */
    ChromeDriver create(ChromeOptions options);
  }

  /**
   * The browser and the threads of a running player, created and released together.
   */
  private static final class Connection {

    private final ChromeDriver driver;
    private final ExecutorService capture;
    private final ExecutorService input;

    Connection(final ChromeDriver driver, final ExecutorService capture, final ExecutorService input) {
      this.driver = driver;
      this.capture = capture;
      this.input = input;
    }
  }

  /**
   * A frame of the screencast.
   */
  @VisibleForTesting
  static final class ScreencastFrame {

    private final String data;
    private final int sessionId;
    private final int pageWidth;
    private final int pageHeight;

    ScreencastFrame(final String data, final int sessionId, final int pageWidth, final int pageHeight) {
      this.data = data;
      this.sessionId = sessionId;
      this.pageWidth = pageWidth;
      this.pageHeight = pageHeight;
    }

    /**
     * Gets the Base64 encoded JPEG image.
     *
     * @return the image, or an empty string if the event had none
     */
    String getData() {
      return this.data;
    }

    /**
     * Gets the number Chrome expects in the acknowledgement of the frame.
     *
     * @return the session number
     */
    int getSessionId() {
      return this.sessionId;
    }

    /**
     * Gets the width of the page in CSS pixels.
     *
     * @return the width, or 0 if unknown
     */
    int getPageWidth() {
      return this.pageWidth;
    }

    /**
     * Gets the height of the page in CSS pixels.
     *
     * @return the height, or 0 if unknown
     */
    int getPageHeight() {
      return this.pageHeight;
    }
  }
}

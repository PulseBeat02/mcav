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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.impl.driver.jar.DriverJar;
import com.microsoft.playwright.options.MouseButton;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link BrowserPlayer} that runs the headless Chromium of Playwright and streams the page with the DevTools
 * screencast.
 *
 * <p>Playwright objects may only be used from the thread that created them and dispatch their events only while
 * that thread is inside a Playwright call, so one browser thread owns the browser: it runs the queued input and
 * otherwise waits inside Playwright for events. Frames are decoded on a capture thread. Pages the page opens are
 * followed, and when the followed page closes the stream returns to the newest remaining page.
 *
 * <p>Every start has its own running flag, so a browser thread that outlives a failed start, for example one stuck
 * in a download, never launches a browser for a later start. A start is refused while such a thread is still alive.
 */
public final class PlaywrightPlayer extends AbstractBrowserPlayer {

  private static final Base64.Decoder BASE64 = Base64.getDecoder();
  private static final long PUMP_MILLIS = 5L;
  private static final long FRAME_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
  private static final Duration START_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

  private final Runnable installer;
  private final Duration startTimeout;
  private final Duration stopTimeout;
  private final List<String> arguments;
  private final AtomicReference<byte@Nullable[]> latestFrame;
  private final AtomicReference<@Nullable Session> currentSession;

  private volatile AtomicBoolean running;
  private volatile CompletableFuture<@Nullable Void> starting;
  private @Nullable Thread browserThread;
  private volatile @Nullable Thread captureThread;

  PlaywrightPlayer(final String... arguments) {
    this(PlaywrightInstaller::ensureInstalled, START_TIMEOUT, STOP_TIMEOUT, arguments);
  }

  /**
   * Constructs a player with its installation step and timeouts replaced, so tests can simulate slow or broken
   * installations.
   *
   * @param installer    makes sure the browser is installed, called on the browser thread before it launches; it is
   *                     interrupted when the start is given up
   * @param startTimeout how long the browser may take to start
   * @param stopTimeout  how long closing waits for each thread of the player
   * @param arguments    the command-line arguments of Chromium
   */
  @VisibleForTesting
  PlaywrightPlayer(final Runnable installer, final Duration startTimeout, final Duration stopTimeout, final String... arguments) {
    this.installer = installer;
    this.startTimeout = startTimeout;
    this.stopTimeout = stopTimeout;
    this.arguments = List.of(arguments);
    this.latestFrame = new AtomicReference<>();
    this.currentSession = new AtomicReference<>();
    this.running = new AtomicBoolean(false);
    this.starting = CompletableFuture.completedFuture(null);
  }

  /**
   * Starts the capture and browser threads and waits until the browser thread has installed and launched Chromium
   * and opened the page.
   *
   * @param source the page and the screencast settings
   * @throws PlayerException if the browser of an earlier start is still shutting down, or the browser cannot be
   *                         installed, launched, or started in time
   */
  @Override
  protected void open(final BrowserSource source) {
    final Thread previous = this.browserThread;
    if (previous != null && previous.isAlive()) {
      throw new PlayerException("The browser of the previous start is still shutting down");
    }

    final AtomicBoolean token = new AtomicBoolean(true);
    final CompletableFuture<@Nullable Void> started = new CompletableFuture<>();
    this.running = token;
    this.starting = started;
    this.latestFrame.set(null);
    this.captureThread = startDaemon(() -> this.decodeFrames(token), "mcav-playwright-capture");
    this.browserThread = startDaemon(() -> this.runBrowser(source, started, token), "mcav-playwright");
    this.awaitStart(started);
  }

  private static Thread startDaemon(final Runnable task, final String name) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void awaitStart(final CompletableFuture<@Nullable Void> started) {
    final long timeoutMillis = this.startTimeout.toMillis();
    try {
      started.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      this.close();
      throw new PlayerException("Interrupted while starting Playwright", exception);
    } catch (final ExecutionException exception) {
      this.close();
      // the future only fails through reportFailure, which always passes the cause
      final Throwable reportedCause = exception.getCause();
      final Throwable cause = Objects.requireNonNullElse(reportedCause, exception);
      final String message = cause.getMessage();
      throw new PlayerException("Failed to start Playwright: " + message, cause);
    } catch (final TimeoutException exception) {
      this.close();
      throw new PlayerException("Playwright did not start within " + timeoutMillis + " ms", exception);
    }
  }

  private void runBrowser(final BrowserSource source, final CompletableFuture<@Nullable Void> started, final AtomicBoolean token) {
    final Thread thread = Thread.currentThread();
    final ClassLoader previousLoader = thread.getContextClassLoader();
    // the Playwright driver locates its bundled files through the context class loader
    final Class<DriverJar> driverClass = DriverJar.class;
    final ClassLoader driverLoader = driverClass.getClassLoader();
    thread.setContextClassLoader(driverLoader);
    try {
      this.runSession(source, started, token);
    } finally {
      thread.setContextClassLoader(previousLoader);
    }
  }

  private void runSession(final BrowserSource source, final CompletableFuture<@Nullable Void> started, final AtomicBoolean token) {
    final Session session = new Session();
    final BrowserResources resources = new BrowserResources();
    try {
      this.installer.run();
      final boolean wanted = token.get();
      if (!wanted) {
        // the start was given up while the browser was being installed, so no browser is launched for it
        return;
      }

      this.launch(source, session, resources, token);
      this.currentSession.set(session);
      started.complete(null);
      this.pump(session, token);
    } catch (final RuntimeException | Error exception) {
      // Playwright is third-party code that drives a Node process and can also fail with an Error, such as a
      // LinkageError; the waiting start learns of every failure first, so it fails at once instead of at its timeout,
      // and errors of the virtual machine are thrown afterwards, so they are not hidden
      this.reportFailure(started, exception);
      ThrowableUtils.throwIfFatal(exception);
    } finally {
      // input sent after this point has no browser to go to
      this.currentSession.compareAndSet(session, null);
      session.detach();
      resources.close();
    }
  }

  private void launch(final BrowserSource source, final Session session, final BrowserResources resources, final AtomicBoolean token) {
    final Map<String, String> environment = PlaywrightInstaller.getEnvironment();
    final Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
    createOptions.setEnv(environment);
    final Playwright playwright = Playwright.create(createOptions);
    resources.setPlaywright(playwright);
    final Browser browser = this.launchChromium(playwright);
    resources.setBrowser(browser);

    final BrowserContext context = createContext(browser, source);
    final Page page = context.newPage();
    final String resource = source.getResource();
    page.navigate(resource);
    this.attachScreencast(context, page, source, session, token);
    context.onPage(popup -> this.attachScreencast(context, popup, source, session, token));
  }

  private Browser launchChromium(final Playwright playwright) {
    final BrowserType chromium = playwright.chromium();
    final BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();
    launchOptions.setHeadless(true);
    launchOptions.setArgs(this.arguments);
    return chromium.launch(launchOptions);
  }

  private static BrowserContext createContext(final Browser browser, final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
    contextOptions.setViewportSize(width, height);
    return browser.newContext(contextOptions);
  }

  private void reportFailure(final CompletableFuture<@Nullable Void> started, final Throwable exception) {
    // a failure before the start is thrown by start; later failures are failures of the running browser, which is
    // gone, so the player stops playing
    final boolean startFailure = started.completeExceptionally(exception);
    if (!startFailure) {
      this.fail("The Playwright browser failed", exception);
    }
  }

  private void attachScreencast(
    final BrowserContext context,
    final Page page,
    final BrowserSource source,
    final Session session,
    final AtomicBoolean token
  ) {
    session.detach();
    final CDPSession devToolsSession = context.newCDPSession(page);
    devToolsSession.on("Page.screencastFrame", frame -> this.onFrame(devToolsSession, frame));
    final JsonObject parameters = createScreencastParameters(source);
    devToolsSession.send("Page.startScreencast", parameters);
    session.replace(devToolsSession, page);
    page.onClose(closedPage -> this.returnToOpenPage(context, closedPage, source, session, token));
  }

  private static JsonObject createScreencastParameters(final BrowserSource source) {
    final int quality = source.getScreencastQuality();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int nthFrame = source.getScreencastNthFrame();
    final JsonObject parameters = new JsonObject();
    parameters.addProperty("format", "jpeg");
    parameters.addProperty("quality", quality);
    parameters.addProperty("maxWidth", width);
    parameters.addProperty("maxHeight", height);
    parameters.addProperty("everyNthFrame", nthFrame);
    return parameters;
  }

  /**
   * Returns the stream to the newest open page when the followed page closes. Pages that close while another page
   * is followed, and pages that close while the browser shuts down, change nothing.
   *
   * @param context    the browser context of the pages
   * @param closedPage the page that closed
   * @param source     the screencast settings
   * @param session    the session of the followed page
   * @param token      the running flag of the start the page belongs to
   */
  private void returnToOpenPage(
    final BrowserContext context,
    final Page closedPage,
    final BrowserSource source,
    final Session session,
    final AtomicBoolean token
  ) {
    final boolean active = token.get();
    final Page attachedPage = session.getPage();
    final Page followed = Objects.requireNonNull(attachedPage, "A page is attached before it can close");
    // Playwright pages compare by identity, so equals tells whether the closed page is the followed one
    final boolean followedPageClosed = closedPage.equals(followed);
    if (!active || !followedPageClosed) {
      return;
    }

    final List<Page> pages = context.pages();
    if (pages.isEmpty()) {
      return;
    }

    final Page newest = pages.getLast();
    this.attachScreencast(context, newest, source, session, token);
  }

  /**
   * Handles a {@code Page.screencastFrame} event: acknowledges it, records the page size, and hands the image to
   * the capture thread. A frame that cannot be read is reported and skipped, so it never reaches the browser thread.
   *
   * @param devToolsSession the DevTools session of the page
   * @param frame           the event parameters
   */
  @VisibleForTesting
  void onFrame(final CDPSession devToolsSession, final JsonObject frame) {
    acknowledgeFrame(devToolsSession, frame);
    final int[] pageSize;
    final byte[] bytes;
    try {
      pageSize = readPageSize(frame);
      bytes = readImage(frame);
    } catch (final RuntimeException exception) {
      this.report("Failed to decode a browser frame", exception);
      return;
    }

    this.setPageSize(pageSize[0], pageSize[1]);
    if (bytes == null) {
      return;
    }

    this.latestFrame.set(bytes);
    final Thread capture = this.captureThread;
    if (capture != null) {
      LockSupport.unpark(capture);
    }
  }

  private static void acknowledgeFrame(final CDPSession devToolsSession, final JsonObject frame) {
    final JsonElement sessionId = frame.get("sessionId");
    if (sessionId == null) {
      return;
    }

    final JsonObject acknowledge = new JsonObject();
    acknowledge.add("sessionId", sessionId);
    devToolsSession.send("Page.screencastFrameAck", acknowledge);
  }

  /**
   * Reads the page size of a screencast frame.
   *
   * @param frame the event parameters
   * @return the width and height in CSS pixels, which are 0 when missing
   * @throws RuntimeException if a size is not a number
   */
  @VisibleForTesting
  static int[] readPageSize(final JsonObject frame) {
    final JsonElement metadata = frame.get("metadata");
    if (metadata == null || !metadata.isJsonObject()) {
      return new int[] { 0, 0 };
    }

    final JsonObject values = metadata.getAsJsonObject();
    final JsonElement deviceWidth = values.get("deviceWidth");
    final JsonElement deviceHeight = values.get("deviceHeight");
    final int width = readInt(deviceWidth);
    final int height = readInt(deviceHeight);
    return new int[] { width, height };
  }

  /**
   * Reads the image of a screencast frame.
   *
   * @param frame the event parameters
   * @return the JPEG image, or null if the frame has none
   * @throws RuntimeException if the image is not Base64 text
   */
  @VisibleForTesting
  static byte@Nullable[] readImage(final JsonObject frame) {
    final JsonElement data = frame.get("data");
    if (data == null) {
      return null;
    }

    final String encoded = data.getAsString();
    return BASE64.decode(encoded);
  }

  private static int readInt(final @Nullable JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      return 0;
    }
    return element.getAsInt();
  }

  private void decodeFrames(final AtomicBoolean token) {
    while (token.get()) {
      final byte[] frame = this.latestFrame.getAndSet(null);
      if (frame == null) {
        LockSupport.parkNanos(FRAME_WAIT_NANOS);
        continue;
      }
      this.deliverFrame(frame, 0, 0);
    }
  }

  private void pump(final Session session, final AtomicBoolean token) {
    while (token.get()) {
      session.runQueuedInput();
      final Page attachedPage = session.getPage();
      final Page page = Objects.requireNonNull(attachedPage, "The first page is attached before the pump starts");
      final boolean closed = page.isClosed();
      if (closed) {
        // the close handler returns to an open page, so a closed page means none is left, as after a crash
        throw new PlayerException("The browser has no open page left");
      }
      waitForEvents(page);
    }
  }

  /**
   * Waits a moment inside Playwright, which dispatches the events of the browser meanwhile.
   *
   * @param page the followed page
   * @throws PlaywrightException if the browser fails
   */
  private static void waitForEvents(final Page page) {
    try {
      page.waitForTimeout(PUMP_MILLIS);
    } catch (final PlaywrightException exception) {
      // a page that closes itself while it is waited on ends the wait; its close handler picks another page
      final boolean closed = page.isClosed();
      if (!closed) {
        throw exception;
      }
    }
  }

  /**
   * Runs input on a page. Failures are reported unless the input closed the page, as a key that closes a popup
   * does.
   *
   * @param page   the page
   * @param action the input
   */
  @VisibleForTesting
  void runAction(final Page page, final PageAction action) {
    final boolean closedBefore = page.isClosed();
    if (closedBefore) {
      return;
    }

    try {
      action.run(page);
    } catch (final RuntimeException exception) {
      final boolean closedByInput = page.isClosed();
      if (!closedByInput) {
        this.report("Failed to forward input to the browser", exception);
      }
    }
  }

  /**
   * Closes a Playwright object, reporting failures instead of throwing them.
   *
   * @param closeable the object to close
   */
  @VisibleForTesting
  void closeQuietly(final AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (final Exception exception) {
      this.report("Failed to close Playwright", exception);
    }
  }

  private void submit(final PageAction action, final Session session) {
    session.queue(() -> {
      final Page attachedPage = session.getPage();
      final Page page = Objects.requireNonNull(attachedPage, "Input runs after the first page is attached");
      this.runAction(page, action);
    });
  }

  /**
   * Gets the session input goes to.
   *
   * @return the session of the playing browser, or null while the player is not playing, including while the page
   *     opens
   */
  private @Nullable Session getInputSession() {
    final Session session = this.currentSession.get();
    final boolean playing = this.canForwardInput();
    return playing ? session : null;
  }

  /**
   * Moves the mouse pointer of Chromium. The move is queued for the browser thread; it is ignored while the player
   * is not playing.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   */
  @Override
  public void moveMouse(final int x, final int y) {
    final Session session = this.getInputSession();
    if (session == null) {
      return;
    }

    final int[] translated = this.translateCoordinates(x, y);
    this.submit(
        page -> {
          final Mouse mouse = page.mouse();
          mouse.move(translated[0], translated[1]);
        },
        session
      );
  }

  /**
   * Moves the mouse pointer of Chromium and performs a click. The click is queued for the browser thread; it is
   * ignored while the player is not playing.
   *
   * @param type the kind of click
   * @param x    the x coordinate in the streamed frame
   * @param y    the y coordinate in the streamed frame
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    checkMouseClick(type);
    final Session session = this.getInputSession();
    if (session == null) {
      return;
    }

    final int[] translated = this.translateCoordinates(x, y);
    this.submit(page -> clickAt(page, type, translated), session);
  }

  private static void clickAt(final Page page, final MouseClick type, final int[] position) {
    final Mouse mouse = page.mouse();
    final int pageX = position[0];
    final int pageY = position[1];
    mouse.move(pageX, pageY);
    final Runnable press =
      switch (type) {
        case LEFT -> () -> mouse.click(pageX, pageY);
        case RIGHT -> () -> {
          final Mouse.ClickOptions options = new Mouse.ClickOptions();
          options.setButton(MouseButton.RIGHT);
          mouse.click(pageX, pageY, options);
        };
        case DOUBLE -> () -> mouse.dblclick(pageX, pageY);
        case HOLD -> mouse::down;
        case RELEASE -> mouse::up;
      };
    press.run();
  }

  /**
   * Presses a named key such as {@code Enter} in Chromium, or types any other text character by character. The input
   * is queued for the browser thread; it is ignored while the player is not playing.
   *
   * @param text the text or key name
   */
  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final Session session = this.getInputSession();
    if (session == null) {
      return;
    }

    final boolean special = PlaywrightKeys.isSpecialKey(text);
    this.submit(
        page -> {
          final Keyboard keyboard = page.keyboard();
          if (special) {
            keyboard.press(text);
          } else {
            keyboard.type(text);
          }
        },
        session
      );
  }

  /**
   * Stops the browser and capture threads, waiting for each of them up to the stop timeout. A browser thread that
   * is still installing is interrupted; a browser thread that does not stop in time is remembered, so the next start
   * is refused until it ends.
   */
  @Override
  protected void close() {
    final AtomicBoolean token = this.running;
    token.set(false);
    this.currentSession.set(null);
    this.stopBrowserThread();
    this.stopCaptureThread();
    this.latestFrame.set(null);
  }

  private void stopBrowserThread() {
    final Thread worker = this.browserThread;
    if (worker == null) {
      return;
    }

    final CompletableFuture<@Nullable Void> started = this.starting;
    final boolean stillStarting = !started.isDone();
    if (stillStarting) {
      // stops an installation that is still running; the Playwright objects of a started browser are not interrupted
      worker.interrupt();
    }

    this.join(worker);
    final boolean alive = worker.isAlive();
    if (!alive) {
      this.browserThread = null;
    }
  }

  private void stopCaptureThread() {
    final Thread capture = this.captureThread;
    if (capture == null) {
      return;
    }

    LockSupport.unpark(capture);
    this.join(capture);
    this.captureThread = null;
  }

  private void join(final Thread thread) {
    final long timeoutMillis = this.stopTimeout.toMillis();
    try {
      thread.join(timeoutMillis);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * Input to run on a page.
   */
  @FunctionalInterface
  interface PageAction {
    /**
     * Sends the input.
     *
     * @param page the page that receives the input
     */
    void run(final Page page);
  }

  /**
   * The Playwright objects of one browser, closed together when the browser thread ends.
   */
  private final class BrowserResources {

    private @Nullable Playwright playwright;
    private @Nullable Browser browser;

    void setPlaywright(final @Nullable Playwright playwright) {
      this.playwright = playwright;
    }

    void setBrowser(final @Nullable Browser browser) {
      this.browser = browser;
    }

    void close() {
      final Browser currentBrowser = this.browser;
      if (currentBrowser != null) {
        PlaywrightPlayer.this.closeQuietly(currentBrowser);
      }

      final Playwright currentPlaywright = this.playwright;
      if (currentPlaywright != null) {
        PlaywrightPlayer.this.closeQuietly(currentPlaywright);
      }
    }
  }

  /**
   * The page that is streamed, its DevTools session, and the input waiting for the browser thread.
   */
  private static final class Session {

    private final LinkedBlockingQueue<Runnable> actions;
    private volatile @Nullable CDPSession devToolsSession;
    private volatile @Nullable Page page;

    Session() {
      // empty until the first page is attached
      this.actions = new LinkedBlockingQueue<>();
    }

    void queue(final Runnable action) {
      this.actions.offer(action);
    }

    void runQueuedInput() {
      while (true) {
        final Runnable action = this.actions.poll();
        if (action == null) {
          return;
        }
        action.run();
      }
    }

    void replace(final CDPSession newSession, final Page newPage) {
      this.devToolsSession = newSession;
      this.page = newPage;
    }

    @Nullable Page getPage() {
      return this.page;
    }

    void detach() {
      final CDPSession current = this.devToolsSession;
      this.devToolsSession = null;
      if (current == null) {
        return;
      }

      try {
        current.detach();
      } catch (final PlaywrightException exception) {
        // the page may already be gone
      }
    }
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.browser.testing.HelperCoverage;
import me.brandonli.mcav.browser.testing.StandardError;
import me.brandonli.mcav.browser.testing.TestPages;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

// Runs the real browser: helper processes with CEF, pages from a local HTTP server, input through the player, and the
// frames that come back. The first run on a machine downloads the CEF natives.
@Tag("cef")
class CefBrowserIntegrationTest {

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  private static final BrowserOptions LOCAL = BrowserOptions.builder().privateNetworks(true).build();

  private static final String MAIN_PAGE = "/main";

  private static final long REFUSAL_WAIT_MILLISECONDS = 1_500L;

  private final TestPages pages = TestPages.start();
  private final List<BrowserPlayer> players = new ArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();

  private static CefBrowserPlayer.DefaultSessionFactory sessions() {
    return new CefBrowserPlayer.DefaultSessionFactory(new JcefNatives(), HelperCoverage.jvmOptions());
  }

  private BrowserPlayer player(final BrowserOptions options) {
    return this.player(options, sessions());
  }

  private BrowserPlayer player(final BrowserOptions options, final CefBrowserPlayer.DefaultSessionFactory sessions) {
    final CefBrowserPlayer player = new CefBrowserPlayer(options, sessions);
    player.setExceptionHandler((message, error) -> this.failures.add(message + ": " + error.getMessage()));
    this.players.add(player);

    return player;
  }

  private Frames start(final BrowserPlayer player, final String path) {
    final Frames frames = Frames.attach(player.getVideoAttachableCallback());
    final URI page = this.pages.uri(path);
    final boolean started = player.start(BrowserSource.uri(page, WIDTH, HEIGHT, 1));
    assertTrue(started);

    return frames;
  }

  // Waits until the page takes clicks. Chromium ignores presses, clicks and keys for a moment after a page appears,
  // while it already passes mouse moves on, so a probe click is sent until the page reports one; the probe's events are
  // forgotten afterwards.
  private void awaitInput(final BrowserPlayer player) {
    Await.until("the page reported its size", () -> this.pages.count("size") > 0);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (this.pages.count("click") == 0) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, "the page never took a click");
      player.sendMouseEvent(MouseClick.LEFT, 1, 1);
      final long probeEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
      while (this.pages.count("click") == 0 && System.nanoTime() < probeEnd) {
        Thread.onSpinWait();
      }
    }
    Await.until("the probe released", () -> this.pages.count("mouseup") >= this.pages.count("mousedown"));
    this.pages.clearEvents();
  }

  @AfterEach
  void releasePlayers() {
    for (final BrowserPlayer player : this.players) {
      player.release();
    }
    this.pages.close();
    final int processCount = HelperProcesses.count();
    assertEquals(0, processCount);
    Await.until("every browser process of this test has ended", () -> countBrowserProcesses() == 0);
  }

  // Counts the processes below this JVM that belong to a browser: the helper JVMs, their CEF processes, and Xvfb.
  static long countBrowserProcesses() {
    final ProcessHandle self = ProcessHandle.current();
    // Windows does not tell the command line of another process, only its program, which for a helper is the Java of
    // this JVM and for a CEF process lies in the jcef folder
    final String java = self.info().command().orElse("");
    try (final Stream<ProcessHandle> descendants = self.descendants()) {
      return descendants
        .filter(ProcessHandle::isAlive)
        .filter(handle -> isBrowserProcess(java, handle))
        .count();
    }
  }

  private static boolean isBrowserProcess(final String java, final ProcessHandle handle) {
    final ProcessHandle.Info information = handle.info();
    final String command = information.commandLine().orElse(information.command().orElse(""));
    final boolean helper = command.contains(HelperLauncher.MAIN_CLASS) || (!java.isEmpty() && command.equals(java));
    return helper || command.contains("jcef") || command.contains("Xvfb");
  }

  @Test
  void shouldStreamAPageWithItsColors() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, MAIN_PAGE);
    final boolean playing = player.isPlaying();
    assertTrue(playing);
    Await.until("a red frame", () -> frames.lastShows(TestPages.MAIN_COLOR));
    final Frames.Frame last = frames.requireLast();
    final int width = last.getWidth();
    assertEquals(WIDTH, width);
    final int height = last.getHeight();
    assertEquals(HEIGHT, height);
    final boolean browserRunning = countBrowserProcesses() > 0;
    assertTrue(browserRunning);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void shouldPassClicksKeysAndTheWheelToThePageWhereTheyLand() {
    final BrowserPlayer player = this.player(LOCAL);
    this.start(player, MAIN_PAGE);
    this.awaitInput(player);
    player.sendMouseEvent(MouseClick.LEFT, 100, 50);
    Await.until("a click", () -> this.hasEventAtX("click", 100));
    final TestPages.PageEvent click = this.pages
      .getEvents("click")
      .stream()
      .filter(event -> event.getX() == 100)
      .findFirst()
      .orElseThrow();
    final int clickY = click.getY();
    assertEquals(50, clickY);
    final int clickButton = click.getButton();
    assertEquals(0, clickButton);
    player.sendMouseEvent(MouseClick.RIGHT, 10, 20);
    Await.until("a context menu", () -> this.pages.count("contextmenu") > 0);
    final int contextMenuButton = this.pages.getEvents("contextmenu").getFirst().getButton();
    assertEquals(2, contextMenuButton);
    player.sendMouseEvent(MouseClick.DOUBLE, 30, 40);
    Await.until("a double click", () -> this.pages.count("dblclick") > 0);
    player.sendMouseEvent(MouseClick.HOLD, 5, 5);
    player.sendMouseEvent(MouseClick.RELEASE, 6, 6);
    Await.until("the held button released", () -> this.hasEventAtX("mouseup", 6));
    final TestPages.PageEvent drag = this.pages
      .getEvents("mousemove")
      .stream()
      .filter(event -> event.getX() == 6 && event.getY() == 6)
      .findFirst()
      .orElseThrow();
    final int buttons = drag.getButtons();
    assertEquals(1, buttons, drag::toString);
    player.sendKeyEvent("hi");
    player.sendKeyEvent("Enter");
    player.sendKeyEvent("PageDown");
    Await.until("the keys", () -> this.pages.count("keydown") >= 4);
    final List<String> keys = this.pages.getEvents("keydown").stream().map(TestPages.PageEvent::getKey).toList();
    final List<String> typedKeys = keys.subList(0, 4);
    assertEquals(List.of("h", "i", "Enter", "PageDown"), typedKeys);
    player.scroll(50, 60, 0, 120);
    Await.until("a turn of the wheel", () -> this.pages.count("wheel") > 0);
    final TestPages.PageEvent wheel = this.pages.getEvents("wheel").getFirst();
    final String wheelKey = wheel.getKey();
    assertEquals("1", wheelKey);
    assertEquals(List.of(), this.failures);
  }

  private boolean hasEventAtX(final String type, final int pageX) {
    return this.pages
      .getEvents(type)
      .stream()
      .anyMatch(event -> event.getX() == pageX);
  }

  @Test
  void shouldOpenAPopupOpenedByThePlayerInPlace() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, MAIN_PAGE);
    this.awaitInput(player);
    player.sendKeyEvent("o");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!frames.lastShows(TestPages.POPUP_COLOR)) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, () -> this.describePopupWait(frames));
      Thread.onSpinWait();
    }
    final boolean playing = player.isPlaying();
    assertTrue(playing);
  }

  private String describePopupWait(final Frames frames) {
    return (
      "the popup is shown in place: " +
      frames.describeLast() +
      "; the pages reported " +
      this.pages.getEvents().stream().map(TestPages.PageEvent::getType).toList()
    );
  }

  @Test
  void shouldOpenALinkToANewWindowInPlace() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/to-popup-link");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    // clicks are ignored for a moment after the page appears, so the link is clicked until it opens
    while (!frames.lastShows(TestPages.POPUP_COLOR)) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, "the link never opened");
      player.sendMouseEvent(MouseClick.LEFT, 50, 50);
      final long pause = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      while (System.nanoTime() < pause && !frames.lastShows(TestPages.POPUP_COLOR)) {
        Thread.onSpinWait();
      }
    }
    final boolean playing = player.isPlaying();
    assertTrue(playing);
  }

  // Clicks a point of the page until a condition holds, since clicks are ignored for a moment after a page appears.
  private static void clickUntil(final BrowserPlayer player, final String description, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, description + " never happened");
      player.sendMouseEvent(MouseClick.LEFT, 20, 20);
      final long pause = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      while (System.nanoTime() < pause && !condition.getAsBoolean()) {
        Thread.onSpinWait();
      }
    }
  }

  @Test
  void shouldOpenALinkIntoAFrameOfThePageInThatFrame() throws InterruptedException {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/named-frame");
    clickUntil(player, "the second page in the frame", () -> this.hasEventOnPage("size", "second"));
    final TestPages.PageEvent size = this.pages
      .getEvents("size")
      .stream()
      .filter(event -> event.getPage().equals("second"))
      .findFirst()
      .orElseThrow();
    final int secondPageWidth = size.getX();
    assertEquals(TestPages.FRAME_WIDTH, secondPageWidth, "the second page fills the frame, not the page");
    Thread.sleep(500L);
    final boolean showsMainPage = frames.lastShows(TestPages.MAIN_COLOR);
    assertTrue(showsMainPage, "the page itself stays");
  }

  private boolean hasEventOnPage(final String type, final String page) {
    return this.pages
      .getEvents(type)
      .stream()
      .anyMatch(event -> event.getPage().equals(page));
  }

  @Test
  void shouldSendAFormInPlaceWhenItsButtonTargetsANewWindow() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/form-target");
    clickUntil(player, "the form sent", () -> frames.lastShows(TestPages.SECOND_COLOR));
    final boolean playing = player.isPlaying();
    assertTrue(playing);
  }

  @Test
  void shouldBlockALinkToANewWindowThatAScriptClicks() throws InterruptedException {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/to-popup-synthetic");
    Thread.sleep(REFUSAL_WAIT_MILLISECONDS);
    final boolean showsMainPage = frames.lastShows(TestPages.MAIN_COLOR);
    assertTrue(showsMainPage, "still the page, not the popup");
  }

  @Test
  void shouldBlockAPopupThePageOpensByItself() throws InterruptedException {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/to-popup");
    Thread.sleep(REFUSAL_WAIT_MILLISECONDS);
    final boolean showsMainPage = frames.lastShows(TestPages.MAIN_COLOR);
    assertTrue(showsMainPage, "still the page, not the popup");
  }

  @Test
  void shouldDismissJavaScriptDialogsAndLetThePageGoOn() {
    final BrowserPlayer player = this.player(BrowserOptions.builder().privateNetworks(true).javaScriptJit(true).frameRate(30).build());
    final Frames frames = this.start(player, "/dialog");
    Await.until("the page past its dialogs", () -> frames.lastShows(TestPages.SECOND_COLOR));
  }

  @Test
  void shouldNotLetAPageNavigateToAFileOrDownloadOne() throws InterruptedException {
    try (final StandardError reports = new StandardError()) {
      final BrowserPlayer toFile = this.player(LOCAL);
      final Frames fileFrames = this.start(toFile, "/to-file");
      final BrowserPlayer toDownload = this.player(LOCAL);
      final Frames downloadFrames = this.start(toDownload, "/to-download");
      Thread.sleep(REFUSAL_WAIT_MILLISECONDS);
      final boolean fileShowsMainPage = fileFrames.lastShows(TestPages.MAIN_COLOR);
      assertTrue(fileShowsMainPage, () -> "still the page, not the file: " + fileFrames.describeLast());
      final boolean downloadShowsMainPage = downloadFrames.lastShows(TestPages.MAIN_COLOR);
      assertTrue(downloadShowsMainPage, () -> "still the page, not a download: " + downloadFrames.describeLast());
      final boolean filePlaying = toFile.isPlaying();
      assertTrue(filePlaying);
      final boolean downloadPlaying = toDownload.isPlaying();
      assertTrue(downloadPlaying);
      final String refusal = "Browser: The download of evil.exe was refused.";
      Await.until("the real download reaches the refusal handler", () -> reports.text().contains(refusal));
      final List<String> downloadReports = reports
        .text()
        .lines()
        .filter(line -> line.contains("Browser: The download of "))
        .map(line -> line.substring(line.indexOf("Browser: The download of ")))
        .toList();
      assertEquals(List.of(refusal), downloadReports);
    }
  }

  @Test
  void shouldFailTheStartWhenThePageCannotBeLoaded() {
    final BrowserPlayer player = this.player(LOCAL);
    final BrowserSource unreachable = BrowserSource.uri(URI.create("http://mcav-browser-test.invalid/"), WIDTH, HEIGHT, 1);
    final BrowserException failure = assertThrows(BrowserException.class, () -> player.start(unreachable));
    final boolean namesTheLoadError = failure.getMessage().contains("NAME_NOT_RESOLVED") || failure.getMessage().contains("-105");
    assertTrue(namesTheLoadError, failure.getMessage());
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void shouldRefuseAPageOnThisMachineUnlessPrivateNetworksAreAllowed() {
    final BrowserPlayer player = this.player(BrowserOptions.DEFAULT);
    final BrowserSource local = BrowserSource.uri(this.pages.uri(MAIN_PAGE), WIDTH, HEIGHT, 1);
    final BrowserException failure = assertThrows(BrowserException.class, () -> player.start(local));
    final String message = failure.getMessage();
    final boolean namesTheProxyRefusal = message.contains("SOCKS") || message.contains("PROXY") || message.contains("TUNNEL");
    assertTrue(namesTheProxyRefusal, message);
    final int requestCount = this.pages.getEvents().size();
    assertEquals(0, requestCount, "the page was never requested");
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void shouldLoadAPublicPageThroughTheGuard() {
    assumeTrue(Boolean.getBoolean("mcav.networkTests"), "This test needs the internet; run it with -Pmcav.networkTests=true!");
    final BrowserPlayer player = this.player(BrowserOptions.DEFAULT);
    final Frames frames = Frames.attach(player.getVideoAttachableCallback());
    final boolean started = player.start(BrowserSource.uri(URI.create("https://example.com/"), WIDTH, HEIGHT, 1));
    assertTrue(started);
    Await.until("a frame of the page", () -> frames.count() > 0);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void shouldLeaveNoProcessAfterAReleaseAndStartANewBrowserFresh() {
    final CefBrowserPlayer.DefaultSessionFactory shared = sessions();
    final BrowserPlayer first = this.player(LOCAL, shared);
    this.start(first, MAIN_PAGE);
    final boolean browserRunning = countBrowserProcesses() > 0;
    assertTrue(browserRunning);
    first.release();
    Await.until("the processes of the first browser ended", () -> countBrowserProcesses() == 0);
    final BrowserPlayer second = this.player(LOCAL, shared);
    final Frames frames = this.start(second, "/second");
    Await.until("the second browser shows its page", () -> frames.lastShows(TestPages.SECOND_COLOR));
  }

  @Test
  void shouldEndEveryBrowserWhenTheModuleStops() {
    final BrowserPlayer player = this.player(LOCAL);
    this.start(player, MAIN_PAGE);
    final int processCountWhilePlaying = HelperProcesses.count();
    assertEquals(1, processCountWhilePlaying);
    final BrowserModule module = new BrowserModule();
    try {
      module.stop();
      final int processCountAfterStop = HelperProcesses.count();
      assertEquals(0, processCountAfterStop);
      final boolean playing = player.isPlaying();
      assertFalse(playing, "the player knows its browser ended");
      assertEquals(List.of("The browser module was stopped!: The browser module was stopped!"), this.failures);
      Await.until("the browser processes ended", () -> countBrowserProcesses() == 0);
    } finally {
      module.start();
    }
    final Frames frames = this.start(player, "/second");
    Await.until("the browser shows its page again", () -> frames.lastShows(TestPages.SECOND_COLOR));
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void shouldRunEveryProcessOfChromiumInASandboxOnMacOs() throws Throwable {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, MAIN_PAGE);
    Await.until("a red frame", () -> frames.lastShows(TestPages.MAIN_COLOR));
    final List<ProcessHandle> chromium;
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      chromium = descendants
        .filter(ProcessHandle::isAlive)
        .filter(handle -> handle.info().command().orElse("").contains("jcef Helper"))
        .toList();
    }
    final boolean empty = chromium.isEmpty();
    assertFalse(empty, "Chromium's processes run");
    for (final ProcessHandle process : chromium) {
      final String command = process.info().command().orElse("?");
      final boolean sandboxed = isSandboxed(process.pid());
      assertTrue(sandboxed, () -> "a process of Chromium runs outside a sandbox: " + command);
    }
  }

  // sandbox_check is called through the restricted native linker; the tests run with native access enabled
  @SuppressWarnings("restricted")
  private static boolean isSandboxed(final long processId) throws Throwable {
    final Linker linker = Linker.nativeLinker();
    final MethodHandle check = linker.downcallHandle(
      linker.defaultLookup().findOrThrow("sandbox_check"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    return (int) check.invokeExact((int) processId, MemorySegment.NULL, 0) != 0;
  }
}

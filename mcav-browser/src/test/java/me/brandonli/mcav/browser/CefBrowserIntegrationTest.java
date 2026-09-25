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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.browser.testing.HelperCoverage;
import me.brandonli.mcav.browser.testing.TestPages;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the real browser: helper processes with CEF, pages from a local HTTP server, input through the player, and the
 * frames that come back. The first run on a machine downloads the CEF natives.
 */
@Tag("cef")
class CefBrowserIntegrationTest {

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  // the test pages are served on this machine, which the browser only reaches with private networks allowed
  private static final BrowserOptions LOCAL = BrowserOptions.builder().privateNetworks(true).build();

  // every test gets its own pages, so events of one test never count for another
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
    assertTrue(player.start(BrowserSource.uri(page, WIDTH, HEIGHT, 1)));
    return frames;
  }

  /**
   * Waits until the page takes clicks. Chromium ignores presses, clicks and keys for a moment after a page appears,
   * while it already passes mouse moves on, so a probe click is sent until the page reports one; the probe's events
   * are forgotten afterwards.
   *
   * @param player the player showing a page of {@link #pages}
   */
  private void awaitInput(final BrowserPlayer player) {
    Await.until("the page reported its size", () -> this.pages.count("size") > 0);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (this.pages.count("click") == 0) {
      assertTrue(System.nanoTime() < deadline, "the page never took a click");
      player.sendMouseEvent(MouseClick.LEFT, 1, 1);
      final long probeEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
      while (this.pages.count("click") == 0 && System.nanoTime() < probeEnd) {
        Thread.onSpinWait();
      }
    }
    // the release of the probe may still be on its way
    Await.until("the probe released", () -> this.pages.count("mouseup") >= this.pages.count("mousedown"));
    this.pages.clearEvents();
  }

  @AfterEach
  void releasePlayers() {
    for (final BrowserPlayer player : this.players) {
      player.release();
    }
    this.pages.close();
    assertEquals(0, HelperProcesses.count());
    Await.until("every browser process of this test has ended", () -> countBrowserProcesses() == 0);
  }

  /**
   * Counts the processes below this JVM that belong to a browser: the helper JVMs, their CEF processes, and Xvfb.
   *
   * @return the number of such processes
   */
  static long countBrowserProcesses() {
    final ProcessHandle self = ProcessHandle.current();
    try (final Stream<ProcessHandle> descendants = self.descendants()) {
      return descendants
        .filter(ProcessHandle::isAlive)
        .filter(handle -> {
          final String command = handle.info().commandLine().orElse("");
          return command.contains(HelperLauncher.MAIN_CLASS) || command.contains("jcef") || command.contains("Xvfb");
        })
        .count();
    }
  }

  @Test
  void aPageIsStreamedWithItsColors() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/main");
    assertTrue(player.isPlaying());
    Await.until("a red frame", () -> frames.lastShows(TestPages.MAIN_COLOR));
    final Frames.Frame last = frames.requireLast();
    assertEquals(WIDTH, last.getWidth());
    assertEquals(HEIGHT, last.getHeight());
    assertTrue(countBrowserProcesses() > 0);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void clicksKeysAndTheWheelReachThePageWhereTheyLand() {
    final BrowserPlayer player = this.player(LOCAL);
    this.start(player, "/main");
    this.awaitInput(player);
    player.sendMouseEvent(MouseClick.LEFT, 100, 50);
    // a report of a probe click may still arrive, so the click is looked for where it was made
    Await.until("a click", () -> this.pages.getEvents("click").stream().anyMatch(event -> event.getX() == 100));
    final TestPages.PageEvent click = this.pages.getEvents("click").stream().filter(event -> event.getX() == 100).findFirst().orElseThrow();
    assertEquals(50, click.getY());
    assertEquals(0, click.getButton());
    player.sendMouseEvent(MouseClick.RIGHT, 10, 20);
    Await.until("a context menu", () -> this.pages.count("contextmenu") > 0);
    assertEquals(2, this.pages.getEvents("contextmenu").getFirst().getButton());
    player.sendMouseEvent(MouseClick.DOUBLE, 30, 40);
    Await.until("a double click", () -> this.pages.count("dblclick") > 0);
    player.sendMouseEvent(MouseClick.HOLD, 5, 5);
    player.sendMouseEvent(MouseClick.RELEASE, 6, 6);
    Await.until("the held button released", () -> this.pages.getEvents("mouseup").stream().anyMatch(event -> event.getX() == 6));
    player.sendKeyEvent("hi");
    player.sendKeyEvent("Enter");
    player.sendKeyEvent("PageDown");
    Await.until("the keys", () -> this.pages.count("keydown") >= 4);
    final List<String> keys = this.pages.getEvents("keydown").stream().map(TestPages.PageEvent::getKey).toList();
    assertEquals(List.of("h", "i", "Enter", "PageDown"), keys.subList(0, 4));
    player.scroll(50, 60, 0, 120);
    Await.until("a turn of the wheel", () -> this.pages.count("wheel") > 0);
    final TestPages.PageEvent wheel = this.pages.getEvents("wheel").getFirst();
    assertEquals("1", wheel.getKey());
    assertEquals(List.of(), this.failures);
  }

  @Test
  void aPopupOpenedByThePlayerOpensInPlace() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/main");
    this.awaitInput(player);
    player.sendKeyEvent("o");
    Await.until("the popup shown in place", () -> frames.lastShows(TestPages.POPUP_COLOR));
    assertTrue(player.isPlaying());
  }

  @Test
  void aLinkToANewWindowOpensInPlace() {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/to-popup-link");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    // clicks are ignored for a moment after the page appears, so the link is clicked until it opens
    while (!frames.lastShows(TestPages.POPUP_COLOR)) {
      assertTrue(System.nanoTime() < deadline, "the link never opened");
      player.sendMouseEvent(MouseClick.LEFT, 50, 50);
      final long pause = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      while (System.nanoTime() < pause && !frames.lastShows(TestPages.POPUP_COLOR)) {
        Thread.onSpinWait();
      }
    }
    assertTrue(player.isPlaying());
  }

  @Test
  void aPopupThePageOpensByItselfIsBlocked() throws InterruptedException {
    final BrowserPlayer player = this.player(LOCAL);
    final Frames frames = this.start(player, "/to-popup");
    Thread.sleep(1_500L);
    assertTrue(frames.lastShows(TestPages.MAIN_COLOR), "still the page, not the popup");
  }

  @Test
  void javaScriptDialogsAreDismissedAndThePageGoesOn() {
    final BrowserPlayer player = this.player(BrowserOptions.builder().privateNetworks(true).javaScriptJit(true).frameRate(30).build());
    final Frames frames = this.start(player, "/dialog");
    Await.until("the page past its dialogs", () -> frames.lastShows(TestPages.SECOND_COLOR));
  }

  @Test
  void aPageCannotNavigateToAFileOrDownloadOne() throws InterruptedException {
    final BrowserPlayer toFile = this.player(LOCAL);
    final Frames fileFrames = this.start(toFile, "/to-file");
    final BrowserPlayer toDownload = this.player(LOCAL);
    final Frames downloadFrames = this.start(toDownload, "/to-download");
    Thread.sleep(1_500L);
    assertTrue(fileFrames.lastShows(TestPages.MAIN_COLOR), "still the page, not the file");
    assertTrue(downloadFrames.lastShows(TestPages.MAIN_COLOR), "still the page, not a download");
    assertTrue(toFile.isPlaying());
    assertTrue(toDownload.isPlaying());
  }

  @Test
  void aPageThatCannotBeLoadedFailsTheStart() {
    final BrowserPlayer player = this.player(LOCAL);
    final BrowserSource unreachable = BrowserSource.uri(URI.create("http://mcav-browser-test.invalid/"), WIDTH, HEIGHT, 1);
    final PlayerException failure = assertThrows(PlayerException.class, () -> player.start(unreachable));
    assertTrue(failure.getMessage().contains("NAME_NOT_RESOLVED") || failure.getMessage().contains("-105"), failure.getMessage());
    assertFalse(player.isPlaying());
  }

  @Test
  void aPageOnThisMachineIsRefusedUnlessPrivateNetworksAreAllowed() {
    final BrowserPlayer player = this.player(BrowserOptions.DEFAULT);
    final BrowserSource local = BrowserSource.uri(this.pages.uri("/main"), WIDTH, HEIGHT, 1);
    final PlayerException failure = assertThrows(PlayerException.class, () -> player.start(local));
    final String message = failure.getMessage();
    assertTrue(message.contains("SOCKS") || message.contains("PROXY") || message.contains("TUNNEL"), message);
    assertEquals(0, this.pages.getEvents().size(), "the page was never requested");
    assertFalse(player.isPlaying());
  }

  @Test
  void aPublicPageLoadsThroughTheGuard() {
    assumeTrue(Boolean.getBoolean("mcav.networkTests"), "needs the internet: -Pmcav.networkTests=true");
    final BrowserPlayer player = this.player(BrowserOptions.DEFAULT);
    final Frames frames = Frames.attach(player.getVideoAttachableCallback());
    assertTrue(player.start(BrowserSource.uri(URI.create("https://example.com/"), WIDTH, HEIGHT, 1)));
    Await.until("a frame of the page", () -> frames.count() > 0);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void aReleasedBrowserLeavesNoProcessAndANewOneStartsFresh() {
    // the players share their sessions like the players of a server, which reuse the launcher of the first
    final CefBrowserPlayer.DefaultSessionFactory shared = sessions();
    final BrowserPlayer first = this.player(LOCAL, shared);
    this.start(first, "/main");
    assertTrue(countBrowserProcesses() > 0);
    first.release();
    Await.until("the processes of the first browser ended", () -> countBrowserProcesses() == 0);
    final BrowserPlayer second = this.player(LOCAL, shared);
    final Frames frames = this.start(second, "/second");
    Await.until("the second browser shows its page", () -> frames.lastShows(TestPages.SECOND_COLOR));
  }

  @Test
  void stoppingTheModuleEndsEveryBrowser() {
    final BrowserPlayer player = this.player(LOCAL);
    this.start(player, "/main");
    assertEquals(1, HelperProcesses.count());
    new BrowserModule().stop();
    assertEquals(0, HelperProcesses.count());
    Await.until("the browser processes ended", () -> countBrowserProcesses() == 0);
  }
}

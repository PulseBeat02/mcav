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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.StandardError;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.McavOffscreenBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class CefEngineTest {

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();

  private static HelperConfiguration configuration(final boolean jit) {
    return configuration(jit, false);
  }

  private static HelperConfiguration configuration(final boolean jit, final boolean autoplay) {
    return new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      ROOT.resolve("s"),
      ROOT.resolve("natives"),
      ROOT.resolve("session").resolve("profile"),
      URI.create("https://example.com/"),
      640,
      480,
      1,
      30,
      jit,
      false,
      autoplay
    );
  }

  @Test
  void chromiumRunsWithoutGpuSoundExtensionsOrPermissionPrompts() {
    final List<String> switches = CefEngine.createSwitches(configuration(false), false, false, 0, null);
    assertTrue(
      switches.containsAll(
        List.of("--disable-gpu", "--mute-audio", "--disable-extensions", "--deny-permission-prompts", "--site-per-process")
      )
    );
    // a page plays sound once a player clicked it, as in a desktop browser; CEF's own default lets it play at once
    assertTrue(switches.contains("--autoplay-policy=document-user-activation-required"));
    final List<String> autoplay = CefEngine.createSwitches(configuration(false, true), false, false, 0, null);
    assertTrue(autoplay.contains("--autoplay-policy=no-user-gesture-required"));
    assertFalse(autoplay.contains("--autoplay-policy=document-user-activation-required"));
    assertTrue(switches.contains("--js-flags=--jitless"));
    assertFalse(switches.contains("--ozone-platform=headless"));
    assertFalse(switches.contains("--use-mock-keychain"));
    for (final String value : switches) {
      assertFalse(value.startsWith("--remote-debugging"), value);
    }
  }

  @Test
  void withAGuardEveryConnectionGoesThroughItAndNothingAroundIt() {
    final List<String> guarded = CefEngine.createSwitches(configuration(false), false, false, 41234, null);
    assertTrue(
      guarded.containsAll(
        List.of(
          "--proxy-server=socks5://127.0.0.1:41234",
          "--proxy-bypass-list=<-loopback>",
          "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
          "--disable-quic"
        )
      )
    );
    final List<String> open = CefEngine.createSwitches(configuration(false), false, false, 0, null);
    for (final String value : open) {
      assertFalse(value.startsWith("--proxy"), value);
    }
  }

  @Test
  void theJitStaysOnlyWhenTheConfigurationAllowsIt() {
    assertFalse(CefEngine.createSwitches(configuration(true), false, false, 0, null).contains("--js-flags=--jitless"));
  }

  @Test
  void linuxUsesTheHeadlessPlatformAndTheNullDisplayAndMacosAMockKeychain() {
    final List<String> linux = CefEngine.createSwitches(configuration(false), true, false, 0, "127.0.0.1:26768");
    assertTrue(linux.contains("--ozone-platform=headless"));
    assertTrue(linux.contains("--password-store=basic"));
    assertTrue(linux.contains("--disable-dev-shm-usage"));
    assertTrue(linux.contains("--display=127.0.0.1:26768"));
    final List<String> noDisplay = CefEngine.createSwitches(configuration(false), true, false, 0, null);
    assertTrue(noDisplay.stream().noneMatch(entry -> entry.startsWith("--display")), noDisplay.toString());
    final List<String> mac = CefEngine.createSwitches(configuration(false), false, true, 0, null);
    assertTrue(mac.contains("--use-mock-keychain"));
    assertFalse(mac.contains("--ozone-platform=headless"));
    assertTrue(mac.stream().noneMatch(entry -> entry.startsWith("--display")), mac.toString());
  }

  @Test
  void theSettingsKeepTheProfileInTheSessionAndTheDebuggingPortClosed() {
    final CefSettings settings = new CefSettings();
    settings.remote_debugging_port = 9222;
    settings.persist_session_cookies = true;
    CefEngine.configureSettings(settings, configuration(false));
    final String profile = ROOT.resolve("session").resolve("profile").toString();
    assertTrue(settings.windowless_rendering_enabled);
    assertEquals(profile, settings.root_cache_path);
    assertEquals(profile, settings.cache_path);
    assertFalse(settings.persist_session_cookies);
    assertEquals(0, settings.remote_debugging_port);
    assertEquals(CefSettings.LogSeverity.LOGSEVERITY_WARNING, settings.log_severity);
  }

  @Test
  void theStateListenerCountsDownOnTerminationAndReportsAFailedStart() {
    final ContentPolicyTest.RecordingEvents events = new ContentPolicyTest.RecordingEvents();
    final CountDownLatch terminated = new CountDownLatch(1);
    final CefEngine.StateListener listener = new CefEngine.StateListener(terminated, events);
    listener.stateHasChanged(CefApp.CefAppState.INITIALIZED);
    assertEquals(1, terminated.getCount());
    listener.stateHasChanged(CefApp.CefAppState.TERMINATED);
    assertEquals(0, terminated.getCount());
    final CountDownLatch failed = new CountDownLatch(1);
    new CefEngine.StateListener(failed, events).stateHasChanged(CefApp.CefAppState.INITIALIZATION_FAILED);
    assertEquals(0, failed.getCount());
    assertEquals(List.of("failure: CEF failed to initialize"), events.log);
  }

  @Test
  void aNewBrowserGetsTheScriptsOfWindowsAndSoundBeforeItLoadsThePage() throws Exception {
    final PageAudio audio = new PageAudio(samples -> {}, System::nanoTime);
    final CefBrowser closed = mock(CefBrowser.class);
    CefEngine.openPage(closed, "https://example.com/", 1_000L, audio);
    verify(closed).loadURL("https://example.com/");
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("closed"));
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(failed);
    CefEngine.openPage(browser, "https://example.com/", 1_000L, audio);
    EventQueue.invokeAndWait(() -> {});
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.openWindowsInPlace();
    final InOrder order = inOrder(devTools, browser);
    order.verify(devTools).addEventListener(audio);
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ENABLE_PAGE_METHOD, "{}");
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ADD_SCRIPT_METHOD, calls.get(1).getParameters());
    order.verify(devTools).executeDevToolsMethod("Runtime.enable", "{}");
    order.verify(devTools).executeDevToolsMethod("Runtime.addBinding", "{\"name\":\"__mcavAudio\"}");
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ADD_SCRIPT_METHOD, PageAudio.install().get(2).getParameters());
    order.verify(browser).loadURL("https://example.com/");
  }

  @Test
  void thePageIsLoadedWhenTheScriptIsPlacedOrItsAnswerIsLost() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> lost = new CompletableFuture<>();
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(lost);
    // the deadline of a real helper, so a slow machine does not reach it before the check that nothing loaded yet
    CefEngine.openPage(browser, "https://example.com/lost", 1_000L, new PageAudio(samples -> {}, System::nanoTime));
    verify(browser, never()).loadURL(anyString());
    Await.until("the page loaded after the timeout", () -> {
      try {
        EventQueue.invokeAndWait(() -> {});
      } catch (final InterruptedException | java.lang.reflect.InvocationTargetException exception) {
        throw new IllegalStateException(exception);
      }
      return mockingDetails(browser).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("loadURL"));
    });
    verify(browser).loadURL("https://example.com/lost");
  }

  @Test
  void theVersionIsDescribedWhenJcefReportsOne() {
    assertEquals("unknown", CefEngine.describe(null));
    final CefApp.CefVersion version = mock(CefApp.CefVersion.class);
    when(version.getCefVersion()).thenReturn("146.0.10");
    when(version.getChromeVersion()).thenReturn("146.0.7680.179");
    assertEquals("146.0.10 (Chromium 146.0.7680.179)", CefEngine.describe(version));
  }

  @Test
  void inputIsSentOnlyWhileTheBrowserHasADevToolsClient() {
    CefEngine.execute(null, DevToolsInput.pressKey("Enter"));
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("{}"));
    CefEngine.execute(devTools, DevToolsInput.pressKey("Enter"));
    verify(devTools, times(2)).executeDevToolsMethod(eq(DevToolsInput.KEY_METHOD), anyString());
  }

  @Test
  void closingDisposesOfWhatWasCreated() {
    final CefApp app = mock(CefApp.class);
    CefEngine.close(null, null, app);
    verify(app).dispose();
    final McavOffscreenBrowser browser = mock(McavOffscreenBrowser.class);
    final CefClient client = mock(CefClient.class);
    final CefApp second = mock(CefApp.class);
    CefEngine.close(browser, client, second);
    final InOrder order = inOrder(browser, client, second);
    order.verify(browser).setCloseAllowed();
    order.verify(browser).close(true);
    order.verify(client).dispose();
    order.verify(second).dispose();
  }

  @Test
  void aShutdownWaitsForCefAndSurvivesFailuresAndInterrupts() {
    final CountDownLatch terminated = new CountDownLatch(1);
    CefEngine.shutDown(terminated::countDown, terminated, 1_000L);
    assertEquals(0, terminated.getCount());
    try (final StandardError errors = new StandardError()) {
      CefEngine.shutDown(
        () -> {
          throw new IllegalStateException("dispose failed");
        },
        new CountDownLatch(1),
        1_000L
      );
      assertTrue(errors.text().contains("Failed to shut CEF down: java.lang.IllegalStateException: dispose failed"), errors.text());
    }
    Thread.currentThread().interrupt();
    try {
      CefEngine.shutDown(() -> {}, new CountDownLatch(1), 60_000L);
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void anEngineThatNeverStartedIgnoresInputAndStops() throws Exception {
    final List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    // a failure on the event thread reaches the default handler
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
      if (thread.getName().startsWith("AWT-EventQueue")) {
        failures.add(failure);
      }
    });
    try (final StandardError errors = new StandardError()) {
      final CefEngine engine = new CefEngine();
      engine.dispatch(DevToolsInput.pressKey("Enter"));
      engine.stop();
      // whatever was sent to the event thread before has run once this has
      EventQueue.invokeAndWait(() -> {});
      // other tests' helpers may still write their last words, so only this engine's are looked for
      assertFalse(errors.text().contains("Failed to shut CEF down"), errors.text());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
    assertEquals(List.of(), failures);
  }

  @Test
  void aFailedDevToolsCallIsLogged() {
    try (final StandardError errors = new StandardError()) {
      assertEquals("", CefEngine.logFailedCall(new IllegalStateException("gone")));
      assertTrue(errors.text().contains("A DevTools call failed: java.lang.IllegalStateException: gone"), errors.text());
    }
  }

  @Test
  void onlyLinuxGetsANullDisplay(@TempDir final Path directory) throws IOException {
    final Path authority = directory.resolve(NullDisplay.AUTHORITY_FILE);
    assertNull(CefEngine.startDisplay(false, authority));
    assertFalse(Files.exists(authority), "no display, no authority file");
    try (NullDisplay display = java.util.Objects.requireNonNull(CefEngine.startDisplay(true, authority))) {
      assertTrue(display.getDisplay().startsWith("127.0.0.1:"));
      assertTrue(Files.exists(authority), "the helper's X clients find the cookie there");
    }
  }
}

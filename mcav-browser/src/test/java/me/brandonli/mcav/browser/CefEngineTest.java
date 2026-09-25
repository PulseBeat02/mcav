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
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import me.brandonli.mcav.browser.testing.Await;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.McavOffscreenBrowser;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CefEngineTest {

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();

  private static HelperConfiguration configuration(final boolean jit) {
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
      false
    );
  }

  @Test
  void chromiumRunsWithoutGpuSoundExtensionsOrPermissionPrompts() {
    final List<String> switches = CefEngine.createSwitches(configuration(false), false, false, 0);
    assertTrue(
      switches.containsAll(
        List.of("--disable-gpu", "--mute-audio", "--disable-extensions", "--deny-permission-prompts", "--site-per-process")
      )
    );
    assertTrue(switches.contains("--js-flags=--jitless"));
    assertFalse(switches.contains("--ozone-platform=headless"));
    assertFalse(switches.contains("--use-mock-keychain"));
    for (final String value : switches) {
      assertFalse(value.startsWith("--remote-debugging"), value);
    }
  }

  @Test
  void withAGuardEveryConnectionGoesThroughItAndNothingAroundIt() {
    final List<String> guarded = CefEngine.createSwitches(configuration(false), false, false, 41234);
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
    final List<String> open = CefEngine.createSwitches(configuration(false), false, false, 0);
    for (final String value : open) {
      assertFalse(value.startsWith("--proxy"), value);
    }
  }

  @Test
  void theJitStaysOnlyWhenTheConfigurationAllowsIt() {
    assertFalse(CefEngine.createSwitches(configuration(true), false, false, 0).contains("--js-flags=--jitless"));
  }

  @Test
  void linuxUsesTheHeadlessPlatformAndMacosAMockKeychain() {
    final List<String> linux = CefEngine.createSwitches(configuration(false), true, false, 0);
    assertTrue(linux.contains("--ozone-platform=headless"));
    assertTrue(linux.contains("--password-store=basic"));
    final List<String> mac = CefEngine.createSwitches(configuration(false), false, true, 0);
    assertTrue(mac.contains("--use-mock-keychain"));
    assertFalse(mac.contains("--ozone-platform=headless"));
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
  void aNewBrowserGetsTheScriptThatOpensWindowsInPlaceBeforeItLoadsThePage() throws Exception {
    final CefBrowser closed = mock(CefBrowser.class);
    CefEngine.openPage(closed, "https://example.com/", 1_000L);
    verify(closed).loadURL("https://example.com/");
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("closed"));
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(failed);
    CefEngine.openPage(browser, "https://example.com/", 1_000L);
    EventQueue.invokeAndWait(() -> {});
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.openWindowsInPlace();
    final InOrder order = inOrder(devTools, browser);
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ENABLE_PAGE_METHOD, "{}");
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ADD_SCRIPT_METHOD, calls.get(1).getParameters());
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
    CefEngine.openPage(browser, "https://example.com/lost", 1_000L);
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
    CefEngine.shutDown(
      () -> {
        throw new IllegalStateException("dispose failed");
      },
      new CountDownLatch(1),
      1_000L
    );
    Thread.currentThread().interrupt();
    try {
      CefEngine.shutDown(() -> {}, new CountDownLatch(1), 60_000L);
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void anEngineThatNeverStartedIgnoresInputAndStops() {
    final CefEngine engine = new CefEngine();
    engine.dispatch(DevToolsInput.pressKey("Enter"));
    engine.stop();
  }
}

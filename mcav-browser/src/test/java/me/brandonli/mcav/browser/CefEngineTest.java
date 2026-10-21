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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.StandardError;
import me.friwi.jcefmaven.CefBuildInfo;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.McavOffscreenBrowser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class CefEngineTest {

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();

  private static final Pattern FEATURE_SEPARATOR = Pattern.compile(",");

  private HelperConfiguration configuration(final AllowedFeature feature) {
    final boolean allowsJit = feature == AllowedFeature.JAVASCRIPT_JIT;
    final boolean allowsAutoplay = feature == AllowedFeature.AUTOPLAY;
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
      allowsJit,
      false,
      allowsAutoplay,
      true,
      ROOT.resolve("server"),
      List.of()
    );
  }

  @Test
  void shouldStartChromiumOnAThreadOfItsOwnWhoseFailureReachesTheCaller() throws Exception {
    final List<String> names = new ArrayList<>();
    CefEngine.runOnOwnThread("mcav-browser-chromium", () -> names.add(Thread.currentThread().getName()));
    assertEquals(List.of("mcav-browser-chromium"), names);

    final IOException refused = new IOException("Landlock could not restrict the thread (error 1)");
    final IOException rethrownRefusal = assertThrows(IOException.class, () -> this.runFailingOnOwnThread(refused));
    assertSame(refused, rethrownRefusal);

    final UnsatisfiedLinkError unlinked = new UnsatisfiedLinkError("libcef.so");
    final UnsatisfiedLinkError rethrownLinkError = assertThrows(UnsatisfiedLinkError.class, () -> this.runUnlinkedOnOwnThread(unlinked));
    assertSame(unlinked, rethrownLinkError);
  }

  private void runFailingOnOwnThread(final Exception exception) throws Exception {
    CefEngine.runOnOwnThread("failing", () -> this.throwException(exception));
  }

  private void runUnlinkedOnOwnThread(final Error error) throws Exception {
    CefEngine.runOnOwnThread("unlinked", () -> this.throwError(error));
  }

  private Object throwException(final Exception exception) throws Exception {
    throw exception;
  }

  private Object throwError(final Error error) {
    throw error;
  }

  @Test
  void shouldRunChromiumWithoutGpuSoundExtensionsOrPermissionPrompts() {
    final List<String> switches = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    final boolean disablesGpuSoundExtensionsAndPrompts = switches.containsAll(
      List.of("--disable-gpu", "--mute-audio", "--disable-extensions", "--deny-permission-prompts", "--site-per-process")
    );
    assertTrue(disablesGpuSoundExtensionsAndPrompts);

    final boolean disablesAudioOutput = switches.contains("--disable-audio-output");
    assertTrue(disablesAudioOutput, "the page's sound is not paced by a sound device");

    final boolean requiresActivationForAutoplay = switches.contains("--autoplay-policy=document-user-activation-required");
    assertTrue(requiresActivationForAutoplay);

    final List<String> autoplaySwitches = CefEngine.createSwitches(
      this.configuration(AllowedFeature.AUTOPLAY),
      OperatingSystem.OTHER,
      0,
      null
    );
    final boolean allowsAutoplay = autoplaySwitches.contains("--autoplay-policy=no-user-gesture-required");
    assertTrue(allowsAutoplay);

    final boolean autoplayRequiresActivation = autoplaySwitches.contains("--autoplay-policy=document-user-activation-required");
    assertFalse(autoplayRequiresActivation);

    final boolean jitless = switches.contains("--js-flags=--jitless");
    assertTrue(jitless);

    final boolean headless = switches.contains("--ozone-platform=headless");
    assertFalse(headless);

    final boolean usesMockKeychain = switches.contains("--use-mock-keychain");
    assertFalse(usesMockKeychain);

    for (final String value : switches) {
      final boolean remoteDebugging = value.startsWith("--remote-debugging");
      assertFalse(remoteDebugging, value);
    }
  }

  @Test
  void shouldSendEveryConnectionThroughAGuardAndNothingAroundIt() {
    final List<String> guarded = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 41234, null);
    final boolean routesThroughTheGuard = guarded.containsAll(
      List.of(
        "--proxy-server=socks5://127.0.0.1:41234",
        "--proxy-bypass-list=<-loopback>",
        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        "--disable-quic"
      )
    );
    assertTrue(routesThroughTheGuard);

    final List<String> open = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    for (final String value : open) {
      final boolean namesAProxy = value.startsWith("--proxy");
      assertFalse(namesAProxy, value);
    }
  }

  // measured with Chromium's net log: the sign-in check, the AI mode check of the search box, the network time service
  // and the preconnect to the search engine
  @Test
  void shouldNeverLetTheOwnServicesOfChromiumContactGoogle() {
    final List<String> switches = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    final List<String> disabled = switches
      .stream()
      .filter(value -> value.startsWith("--disable-features="))
      .toList();
    final boolean redirectsGoogleUrls = switches.containsAll(
      List.of("--gaia-url=https://accounts.invalid", "--google-base-url=https://www.invalid")
    );
    assertTrue(redirectsGoogleUrls);

    final int disableFeaturesCount = disabled.size();
    assertEquals(1, disableFeaturesCount, "Chromium keeps the last --disable-features switch only");

    final String value = disabled.getFirst().substring("--disable-features=".length());
    final List<String> features = List.of(FEATURE_SEPARATOR.split(value, -1));
    final boolean disablesGoogleServices = features.containsAll(List.of("NetworkTimeServiceQuerying", "PreconnectToSearch"));
    assertTrue(disablesGoogleServices, value);

    final boolean keepsCefDisabledFeatures = features.containsAll(CefEngine.CEF_DISABLED_FEATURES);
    assertTrue(keepsCefDisabledFeatures, "the features CEF disables itself stay disabled");
  }

  // Chromium keeps only the last --disable-features, so the helper's repeats the list CEF passes first. When moving to
  // another CEF, copy the list a renderer gets without the helper's switch.
  @Test
  void shouldMatchTheFeaturesCefDisablesItselfToThoseOfThisCef() throws IOException {
    final CefBuildInfo build = CefBuildInfo.fromClasspath();
    final String release = build.getReleaseTag();
    final boolean matchesThisCef = release.contains("+cef-152.0.6+");
    assertTrue(matchesThisCef, release);
  }

  @Test
  void shouldKeepTheJitOnlyWhenTheConfigurationAllowsIt() {
    final boolean jitless = CefEngine.createSwitches(
      this.configuration(AllowedFeature.JAVASCRIPT_JIT),
      OperatingSystem.OTHER,
      0,
      null
    ).contains("--js-flags=--jitless");
    assertFalse(jitless);
  }

  @Test
  void shouldUseTheHeadlessPlatformAndTheNullDisplayOnLinuxAndAMockKeychainOnMacos() {
    final List<String> linux = CefEngine.createSwitches(
      this.configuration(AllowedFeature.NONE),
      OperatingSystem.LINUX,
      0,
      "127.0.0.1:26768"
    );
    final boolean linuxHeadless = linux.contains("--ozone-platform=headless");
    assertTrue(linuxHeadless);

    final boolean basicPasswordStore = linux.contains("--password-store=basic");
    assertTrue(basicPasswordStore);

    final boolean sharedMemoryDisabled = linux.contains("--disable-dev-shm-usage");
    assertTrue(sharedMemoryDisabled);

    final boolean usesNullDisplay = linux.contains("--display=127.0.0.1:26768");
    assertTrue(usesNullDisplay);

    final List<String> noDisplay = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.LINUX, 0, null);
    final boolean namesNoDisplay = noDisplay.stream().noneMatch(entry -> entry.startsWith("--display"));
    assertTrue(namesNoDisplay, noDisplay.toString());

    final List<String> mac = CefEngine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.MAC, 0, null);
    final boolean macMockKeychain = mac.contains("--use-mock-keychain");
    assertTrue(macMockKeychain);

    final boolean macHeadless = mac.contains("--ozone-platform=headless");
    assertFalse(macHeadless);

    final boolean macNamesNoDisplay = mac.stream().noneMatch(entry -> entry.startsWith("--display"));
    assertTrue(macNamesNoDisplay, mac.toString());
  }

  @Test
  void shouldKeepTheProfileInTheSessionAndTheDebuggingPortClosed() {
    final CefSettings settings = new CefSettings();
    settings.remote_debugging_port = 9222;
    settings.persist_session_cookies = true;
    CefEngine.configureSettings(settings, this.configuration(AllowedFeature.NONE));
    final String profile = ROOT.resolve("session").resolve("profile").toString();
    assertTrue(settings.windowless_rendering_enabled);
    assertEquals(profile, settings.root_cache_path);
    assertEquals(profile, settings.cache_path);
    assertFalse(settings.persist_session_cookies);
    assertEquals(0, settings.remote_debugging_port);
    assertEquals(CefSettings.LogSeverity.LOGSEVERITY_WARNING, settings.log_severity);
  }

  @Test
  void shouldCountDownOnTerminationAndReportAFailedStartInTheStateListener() {
    final ContentPolicyTest.RecordingEvents events = new ContentPolicyTest.RecordingEvents();
    final CountDownLatch terminated = new CountDownLatch(1);
    final CefEngine.StateListener listener = new CefEngine.StateListener(terminated, events);
    listener.stateHasChanged(CefApp.CefAppState.INITIALIZED);
    final long countBeforeTermination = terminated.getCount();
    assertEquals(1, countBeforeTermination);

    listener.stateHasChanged(CefApp.CefAppState.TERMINATED);
    final long countAfterTermination = terminated.getCount();
    assertEquals(0, countAfterTermination);

    final CountDownLatch failed = new CountDownLatch(1);
    new CefEngine.StateListener(failed, events).stateHasChanged(CefApp.CefAppState.INITIALIZATION_FAILED);
    final long failedCount = failed.getCount();
    assertEquals(0, failedCount);
    assertEquals(List.of("failure: CEF failed to initialize"), events.log);
  }

  @Test
  void shouldGiveANewBrowserTheScriptsOfWindowsAndSoundBeforeItLoadsThePage() throws Exception {
    final PageAudio audio = new PageAudio(samples -> {}, System::nanoTime);
    final List<String> notices = new CopyOnWriteArrayList<>();
    final CefBrowser closed = mock(CefBrowser.class);
    CefEngine.openPage(closed, "https://example.com/", 1_000L, audio, notices::add);
    verify(closed).loadURL("https://example.com/");
    final CefBrowser browser = mock(CefBrowser.class);

    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("closed"));
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(failed);
    CefEngine.openPage(browser, "https://example.com/", 1_000L, audio, notices::add);
    EventQueue.invokeAndWait(() -> {});
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
    verify(devTools).close();
    verify(devTools, times(10)).executeDevToolsMethod(anyString(), anyString());
    assertEquals(List.of("The scripts of the page were not confirmed in 2 attempts; it loads anyway."), notices);
  }

  @Test
  void shouldReplaceAClientThatLosesItsAnswersAndPlaceTheScriptsAgain() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient lost = mock(CefDevToolsClient.class);
    final CefDevToolsClient working = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(lost, working);
    when(lost.executeDevToolsMethod(anyString(), anyString())).thenReturn(new CompletableFuture<>());
    when(working.executeDevToolsMethod(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("{}"));
    final PageAudio audio = new PageAudio(samples -> {}, System::nanoTime);
    final List<String> notices = new CopyOnWriteArrayList<>();
    CefEngine.openPage(browser, "https://example.com/again", 300L, audio, notices::add);
    Await.until("the page loaded", () -> this.hasLoadedAPage(browser));
    verify(lost).close();
    verify(working).addEventListener(audio);
    verify(working, times(5)).executeDevToolsMethod(anyString(), anyString());
    verify(browser).loadURL("https://example.com/again");
    assertEquals(List.of(), notices, "the new client answered");
  }

  private boolean hasLoadedAPage(final CefBrowser browser) {
    try {
      EventQueue.invokeAndWait(() -> {});
    } catch (final InterruptedException | InvocationTargetException exception) {
      throw new IllegalStateException(exception);
    }

    return mockingDetails(browser)
      .getInvocations()
      .stream()
      .anyMatch(call -> call.getMethod().getName().equals("loadURL"));
  }

  @Test
  void shouldWaitWithThePageForTheAnswerThatItsScriptsArePlacedEvenWhenItIsSlow() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> slow = new CompletableFuture<>();
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(slow);
    final List<String> notices = new CopyOnWriteArrayList<>();
    CefEngine.openPage(browser, "https://example.com/slow", 60_000L, new PageAudio(samples -> {}, System::nanoTime), notices::add);
    Thread.sleep(1_500L);
    EventQueue.invokeAndWait(() -> {});
    verify(browser, never()).loadURL(anyString());

    slow.complete("{}");
    EventQueue.invokeAndWait(() -> {});
    verify(browser).loadURL("https://example.com/slow");
    assertEquals(List.of(), notices);
  }

  @Test
  void shouldLoadThePageWhenTheScriptIsPlacedOrItsAnswerIsLost() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> lost = new CompletableFuture<>();
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(lost);
    final List<String> notices = new CopyOnWriteArrayList<>();
    CefEngine.openPage(browser, "https://example.com/lost", 1_000L, new PageAudio(samples -> {}, System::nanoTime), notices::add);
    verify(browser, never()).loadURL(anyString());

    Await.until("the page loaded after the timeout", () -> this.hasLoadedAPage(browser));
    verify(browser).loadURL("https://example.com/lost");
    verify(devTools).close();
    assertEquals(List.of("The scripts of the page were not confirmed in 2 attempts; it loads anyway."), notices);
  }

  @Test
  void shouldLoadAgainAPageWhoseScriptsAreConfirmedLateToRunThem() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> late = new CompletableFuture<>();
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(late);
    final List<String> notices = new CopyOnWriteArrayList<>();
    CefEngine.openPage(browser, "https://example.com/late", 100L, new PageAudio(samples -> {}, System::nanoTime), notices::add);
    Await.until("the page loaded after the timeout", () -> this.hasLoadedAPage(browser));
    verify(browser, never()).reload();

    late.complete("{}");
    EventQueue.invokeAndWait(() -> {});
    final InOrder order = inOrder(browser);
    order.verify(browser).loadURL("https://example.com/late");
    order.verify(browser).reload();
    assertEquals(
      List.of(
        "The scripts of the page were not confirmed in 2 attempts; it loads anyway.",
        "The scripts of the page were confirmed late; it loads again."
      ),
      notices
    );
  }

  @Test
  void shouldDescribeTheVersionWhenJcefReportsOne() {
    final String unknownDescription = CefEngine.describe(null);
    assertEquals("unknown", unknownDescription);

    final CefApp.CefVersion version = mock(CefApp.CefVersion.class);
    when(version.getCefVersion()).thenReturn("146.0.10");
    when(version.getChromeVersion()).thenReturn("146.0.7680.179");
    final String versionDescription = CefEngine.describe(version);
    assertEquals("146.0.10 (Chromium 146.0.7680.179)", versionDescription);
  }

  @Test
  void shouldOnlySendInputWhileTheBrowserHasADevToolsClient() {
    CefEngine.execute(null, DevToolsInput.pressKey("Enter"));
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("{}"));
    CefEngine.execute(devTools, DevToolsInput.pressKey("Enter"));
    verify(devTools, times(2)).executeDevToolsMethod(eq(DevToolsInput.KEY_METHOD), anyString());
  }

  @Test
  void shouldDisposeOfWhatWasCreatedOnClose() {
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
  void shouldWaitForCefAndSurviveFailuresAndInterruptsOnShutdown() {
    final CountDownLatch terminated = new CountDownLatch(1);
    CefEngine.shutDown(terminated::countDown, terminated, 1_000L);
    final long remainingCount = terminated.getCount();
    assertEquals(0, remainingCount);

    try (final StandardError errors = new StandardError()) {
      CefEngine.shutDown(this::failToDispose, new CountDownLatch(1), 1_000L);
      final boolean reportsTheDisposeFailure = errors
        .text()
        .contains("Failed to shut CEF down (java.lang.IllegalStateException: dispose failed).");
      assertTrue(reportsTheDisposeFailure, errors.text());
    }

    Thread.currentThread().interrupt();
    try {
      CefEngine.shutDown(() -> {}, new CountDownLatch(1), 60_000L);
      final boolean interruptKept = Thread.currentThread().isInterrupted();
      assertTrue(interruptKept, "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
  }

  private void failToDispose() {
    throw new IllegalStateException("dispose failed");
  }

  @Test
  void shouldIgnoreInputAndStopWhenTheEngineNeverStarted() throws Exception {
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> this.recordEventQueueFailure(failures, thread, failure));
    try (final StandardError errors = new StandardError()) {
      final CefEngine engine = new CefEngine();
      engine.dispatch(DevToolsInput.pressKey("Enter"));
      engine.stop();
      EventQueue.invokeAndWait(() -> {});
      final boolean reportsAShutdownFailure = errors.text().contains("Failed to shut CEF down");
      assertFalse(reportsAShutdownFailure, errors.text());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }

    assertEquals(List.of(), failures);
  }

  private void recordEventQueueFailure(final List<Throwable> failures, final Thread thread, final Throwable failure) {
    if (thread.getName().startsWith("AWT-EventQueue")) {
      failures.add(failure);
    }
  }

  @Test
  void shouldLogAFailedDevToolsCall() {
    try (final StandardError errors = new StandardError()) {
      final String fallback = CefEngine.logFailedCall(new IllegalStateException("gone"));
      assertEquals("", fallback);

      final boolean reportsTheFailedCall = errors.text().contains("A DevTools call failed (java.lang.IllegalStateException: gone).");
      assertTrue(reportsTheFailedCall, errors.text());
    }
  }

  @Test
  void shouldOnlyGiveLinuxANullDisplay(@TempDir final Path directory) throws IOException {
    final Path authority = directory.resolve(NullDisplay.AUTHORITY_FILE);
    final @Nullable NullDisplay nonLinuxDisplay = CefEngine.startDisplay(OperatingSystem.OTHER, authority);
    assertNull(nonLinuxDisplay);

    final boolean authorityFileWritten = Files.exists(authority);
    assertFalse(authorityFileWritten, "no display, no authority file");

    try (final NullDisplay display = Objects.requireNonNull(CefEngine.startDisplay(OperatingSystem.LINUX, authority))) {
      final boolean loopbackDisplay = display.getDisplay().startsWith("127.0.0.1:");
      assertTrue(loopbackDisplay);

      final boolean authorityFileWrittenForDisplay = Files.exists(authority);
      assertTrue(authorityFileWrittenForDisplay, "the helper's X clients find the cookie there");
    }
  }

  private enum AllowedFeature {
    NONE,
    JAVASCRIPT_JIT,
    AUTOPLAY,
  }
}

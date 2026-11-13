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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.StandardError;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.IProgressHandler;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.MCAVOffscreenBrowser;
import org.cef.handler.CefLifeSpanHandler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class CefEngineTest {

  private static final long SCRIPT_TIMEOUT_MILLISECONDS = 1_000L;
  private static final long SHUTDOWN_TIMEOUT_MILLISECONDS = 1_000L;

  private static final String SOCKET_FILE = "s";
  private static final String NATIVES_FOLDER = "natives";
  private static final String PROFILE_FOLDER = "profile";
  private static final String SESSION_FOLDER = "session";
  private static final String SERVER_FOLDER = "server";

  private static final String PAGE_URL = "https://example.com/";

  private static final String JITLESS_SWITCH = "--js-flags=--jitless";

  private static final String HEADLESS_SWITCH = "--ozone-platform=headless";

  private static final String MOCK_KEYCHAIN_SWITCH = "--use-mock-keychain";

  private static final List<String> LOCKDOWN_SWITCHES = List.of(
    "--hide-scrollbars",
    "--disable-component-update",
    "--disable-background-networking",
    "--disable-sync",
    "--disable-default-apps",
    "--disable-notifications",
    "--no-first-run",
    "--disable-webgpu"
  );

  private static final String EMPTY_PARAMETERS = "{}";

  private static final String UNCONFIRMED_SCRIPTS_NOTICE = "The scripts of the page were not confirmed in 2 attempts; it loads anyway.";
  private static final String PAGE_LOADED_AFTER_TIMEOUT_MESSAGE = "the page loaded after the timeout";

  private static final String ENTER_KEY = "Enter";

  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();

  private static final Pattern FEATURE_SEPARATOR = Pattern.compile(",");

  private static final String CHROMIUM_THREAD_NAME = "mcav-browser-chromium";

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final String PROXY_SWITCH_PREFIX = "--proxy-server=socks5://127.0.0.1:";
  private static final String DISPLAY_SWITCH_PREFIX = "--display=127.0.0.1:";

  private static final int VIEW_WIDTH = 640;
  private static final int VIEW_HEIGHT = 480;

  private static final int FRAME_INTERVAL = 1;
  private static final long SETTLE_MILLISECONDS = 100L;

  private static final String CONFINEMENT_NOTICE = "notice: %s".formatted(ChromiumConfinement.NOT_ASKED);
  private static final String INITIALIZATION_FAILURE_LINE = "failure: CEF failed to initialize";

  private static final String OS_NAME_PROPERTY = "os.name";

  private CefEngine engine;
  private CefApp application;
  private CefClient client;
  private RecordingBuilder builder;
  private ContentPolicyTest.RecordingEvents events;
  private CefEngine recordingEngine;

  @BeforeEach
  void setUp() {
    this.engine = new CefEngine();
    this.application = mock(CefApp.class);
    this.client = mock(CefClient.class);
    when(this.application.createClient()).thenReturn(this.client);
    doAnswer(_ -> this.terminateCef())
      .when(this.application)
      .dispose();
    this.builder = new RecordingBuilder(this.application);
    this.events = new ContentPolicyTest.RecordingEvents();
    this.recordingEngine = new CefEngine(() -> this.builder);
  }

  private @Nullable Void terminateCef() {
    this.builder.terminate();

    return null;
  }

  @AfterEach
  void stopRecordingEngine() {
    this.recordingEngine.stop();
  }

  private HelperConfiguration configuration(final AllowedFeature feature) {
    final boolean allowsJit = feature == AllowedFeature.JAVASCRIPT_JIT;
    final boolean allowsAutoplay = feature == AllowedFeature.AUTOPLAY;
    return new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      ROOT.resolve(SOCKET_FILE),
      ROOT.resolve(NATIVES_FOLDER),
      ROOT.resolve(SESSION_FOLDER).resolve(PROFILE_FOLDER),
      URI.create(PAGE_URL),
      VIEW_WIDTH,
      VIEW_HEIGHT,
      1,
      30,
      allowsJit,
      false,
      allowsAutoplay,
      true,
      ROOT.resolve(SERVER_FOLDER),
      List.of()
    );
  }

  @Test
  void shouldStartChromiumOnAThreadOfItsOwnWhoseFailureReachesTheCaller() throws Exception {
    final List<String> names = new ArrayList<>();
    this.engine.runOnOwnThread(CHROMIUM_THREAD_NAME, () -> names.add(Thread.currentThread().getName()));
    final List<String> expectedNames = List.of(CHROMIUM_THREAD_NAME);
    assertEquals(expectedNames, names);

    final IOException refused = new IOException("Landlock could not restrict the thread because of error 1 from the kernel!");
    final IOException rethrownRefusal = assertThrows(IOException.class, () -> this.runFailingOnOwnThread(refused));
    assertSame(refused, rethrownRefusal);

    final UnsatisfiedLinkError unlinked = new UnsatisfiedLinkError("The libcef.so native library is missing!");
    final UnsatisfiedLinkError rethrownLinkError = assertThrows(UnsatisfiedLinkError.class, () -> this.runUnlinkedOnOwnThread(unlinked));
    assertSame(unlinked, rethrownLinkError);
  }

  private void runFailingOnOwnThread(final Exception exception) throws Exception {
    this.engine.runOnOwnThread("failing", () -> this.throwException(exception));
  }

  private void runUnlinkedOnOwnThread(final Error error) throws Exception {
    this.engine.runOnOwnThread("unlinked", () -> this.throwError(error));
  }

  private Object throwException(final Exception exception) throws Exception {
    throw exception;
  }

  private Object throwError(final Error error) {
    throw error;
  }

  @Test
  void shouldRunChromiumWithoutGpuSoundExtensionsOrPermissionPrompts() {
    final List<String> switches = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    final boolean disablesGpuSoundExtensionsAndPrompts = switches.containsAll(
      List.of(
        "--disable-gpu",
        "--disable-gpu-compositing",
        "--mute-audio",
        "--disable-extensions",
        "--deny-permission-prompts",
        "--site-per-process"
      )
    );
    assertTrue(disablesGpuSoundExtensionsAndPrompts);

    final boolean disablesAudioOutput = switches.contains("--disable-audio-output");
    assertTrue(disablesAudioOutput, "the page's sound is not paced by a sound device");

    final boolean requiresActivationForAutoplay = switches.contains("--autoplay-policy=document-user-activation-required");
    assertTrue(requiresActivationForAutoplay);

    final List<String> autoplaySwitches = this.engine.createSwitches(
      this.configuration(AllowedFeature.AUTOPLAY),
      OperatingSystem.OTHER,
      0,
      null
    );
    final boolean allowsAutoplay = autoplaySwitches.contains("--autoplay-policy=no-user-gesture-required");
    assertTrue(allowsAutoplay);

    final boolean autoplayRequiresActivation = autoplaySwitches.contains("--autoplay-policy=document-user-activation-required");
    assertFalse(autoplayRequiresActivation);

    final boolean jitless = switches.contains(JITLESS_SWITCH);
    assertTrue(jitless);

    final boolean headless = switches.contains(HEADLESS_SWITCH);
    assertFalse(headless);

    final boolean usesMockKeychain = switches.contains(MOCK_KEYCHAIN_SWITCH);
    assertFalse(usesMockKeychain);

    for (final String value : switches) {
      final boolean remoteDebugging = value.startsWith("--remote-debugging");
      assertFalse(remoteDebugging, value);
    }
  }

  @Test
  void shouldStartChromiumLockedDown() {
    final List<String> switches = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    final boolean locksDownChromium = switches.containsAll(LOCKDOWN_SWITCHES);
    assertTrue(locksDownChromium);
  }

  @Test
  void shouldSendEveryConnectionThroughAGuardAndNothingAroundIt() {
    final List<String> guarded = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 41234, null);
    final boolean routesThroughTheGuard = guarded.containsAll(
      List.of(
        "--proxy-server=socks5://127.0.0.1:41234",
        "--proxy-bypass-list=<-loopback>",
        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        "--disable-quic"
      )
    );
    assertTrue(routesThroughTheGuard);

    final List<String> open = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
    for (final String value : open) {
      final boolean namesAProxy = value.startsWith("--proxy");
      assertFalse(namesAProxy, value);
    }
  }

  // Chromium's own Google contacts: the sign-in check, the search box's AI mode check, network time and the search preconnect
  @Test
  void shouldNeverLetTheOwnServicesOfChromiumContactGoogle() {
    final List<String> switches = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.OTHER, 0, null);
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
    final List<String> features = List.of(FEATURE_SEPARATOR.split(value, KEEP_TRAILING_EMPTY_PARTS));
    final boolean disablesGoogleServices = features.containsAll(List.of("NetworkTimeServiceQuerying", "PreconnectToSearch"));
    assertTrue(disablesGoogleServices, value);

    final boolean keepsCefDisabledFeatures = features.containsAll(CefEngine.CEF_DISABLED_FEATURES);
    assertTrue(keepsCefDisabledFeatures, "the features CEF disables itself stay disabled");
  }

  // Chromium keeps the last --disable-features only, so ours repeats CEF's list; for a new CEF, copy a renderer's list without our switch
  @Test
  void shouldMatchTheFeaturesCefDisablesItselfToThoseOfThisCef() throws IOException {
    final CefBuildInfo build = CefBuildInfo.fromClasspath();
    final String release = build.getReleaseTag();
    final boolean matchesThisCef = release.contains("+cef-152.0.6+");
    assertTrue(matchesThisCef, release);
  }

  @Test
  void shouldKeepTheJitOnlyWhenTheConfigurationAllowsIt() {
    final boolean jitless = this.engine
      .createSwitches(this.configuration(AllowedFeature.JAVASCRIPT_JIT), OperatingSystem.OTHER, 0, null)
      .contains(JITLESS_SWITCH);
    assertFalse(jitless);
  }

  @Test
  void shouldUseTheHeadlessPlatformAndTheNullDisplayOnLinuxAndAMockKeychainOnMacos() {
    final List<String> linux = this.engine.createSwitches(
      this.configuration(AllowedFeature.NONE),
      OperatingSystem.LINUX,
      0,
      "127.0.0.1:26768"
    );
    final boolean linuxHeadless = linux.contains(HEADLESS_SWITCH);
    assertTrue(linuxHeadless);

    final boolean basicPasswordStore = linux.contains("--password-store=basic");
    assertTrue(basicPasswordStore);

    final boolean sharedMemoryDisabled = linux.contains("--disable-dev-shm-usage");
    assertTrue(sharedMemoryDisabled);

    final boolean usesNullDisplay = linux.contains("--display=127.0.0.1:26768");
    assertTrue(usesNullDisplay);

    final List<String> noDisplay = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.LINUX, 0, null);
    final boolean namesNoDisplay = noDisplay.stream().noneMatch(entry -> entry.startsWith("--display"));
    assertTrue(namesNoDisplay, noDisplay.toString());

    final List<String> mac = this.engine.createSwitches(this.configuration(AllowedFeature.NONE), OperatingSystem.MAC, 0, null);
    final boolean macMockKeychain = mac.contains(MOCK_KEYCHAIN_SWITCH);
    assertTrue(macMockKeychain);

    final boolean macHeadless = mac.contains(HEADLESS_SWITCH);
    assertFalse(macHeadless);

    final boolean macNamesNoDisplay = mac.stream().noneMatch(entry -> entry.startsWith("--display"));
    assertTrue(macNamesNoDisplay, mac.toString());
  }

  @Test
  void shouldKeepTheProfileInTheSessionAndTheDebuggingPortClosed() {
    final CefSettings settings = new CefSettings();
    settings.remote_debugging_port = 9222;
    settings.persist_session_cookies = true;
    this.engine.configureSettings(settings, this.configuration(AllowedFeature.NONE));
    final String profile = ROOT.resolve(SESSION_FOLDER).resolve(PROFILE_FOLDER).toString();
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

    final List<String> log = events.getLog();
    final List<String> expectedLog = List.of(INITIALIZATION_FAILURE_LINE);
    assertEquals(expectedLog, log);
  }

  @Test
  void shouldGiveANewBrowserTheScriptsOfWindowsAndSoundBeforeItLoadsThePage() throws Exception {
    final PageAudio audio = new PageAudio(samples -> {}, System::nanoTime);
    final List<String> notices = new CopyOnWriteArrayList<>();
    final CefBrowser closed = mock(CefBrowser.class);
    this.engine.openPage(closed, PAGE_URL, SCRIPT_TIMEOUT_MILLISECONDS, audio, notices::add);
    verify(closed).loadURL(PAGE_URL);
    final CefBrowser browser = mock(CefBrowser.class);

    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("The browser is closed!"));
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(failed);
    this.engine.openPage(browser, PAGE_URL, SCRIPT_TIMEOUT_MILLISECONDS, audio, notices::add);
    EventQueue.invokeAndWait(() -> {});
    EventQueue.invokeAndWait(() -> {});
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.openWindowsInPlace();
    final InOrder order = inOrder(devTools, browser);
    order.verify(devTools).addEventListener(audio);
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ENABLE_PAGE_METHOD, EMPTY_PARAMETERS);
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ADD_SCRIPT_METHOD, calls.get(1).getParameters());
    order.verify(devTools).executeDevToolsMethod("Runtime.enable", EMPTY_PARAMETERS);
    order.verify(devTools).executeDevToolsMethod("Runtime.addBinding", "{\"name\":\"__mcavAudio\"}");
    order.verify(devTools).executeDevToolsMethod(DevToolsInput.ADD_SCRIPT_METHOD, PageAudio.install().get(2).getParameters());
    order.verify(browser).loadURL(PAGE_URL);
    verify(devTools).close();
    verify(devTools, times(10)).executeDevToolsMethod(anyString(), anyString());
    final List<String> expectedNotices = List.of(UNCONFIRMED_SCRIPTS_NOTICE);
    assertEquals(expectedNotices, notices);
  }

  @Test
  void shouldReplaceAClientThatLosesItsAnswersAndPlaceTheScriptsAgain() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient lost = mock(CefDevToolsClient.class);
    final CefDevToolsClient working = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(lost, working);
    when(lost.executeDevToolsMethod(anyString(), anyString())).thenReturn(new CompletableFuture<>());
    when(working.executeDevToolsMethod(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(EMPTY_PARAMETERS));
    final PageAudio audio = new PageAudio(samples -> {}, System::nanoTime);
    final List<String> notices = new CopyOnWriteArrayList<>();
    this.engine.openPage(browser, "https://example.com/again", 300L, audio, notices::add);
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
    this.engine.openPage(browser, "https://example.com/slow", 60_000L, new PageAudio(samples -> {}, System::nanoTime), notices::add);
    Thread.sleep(1_500L);
    EventQueue.invokeAndWait(() -> {});
    verify(browser, never()).loadURL(anyString());

    slow.complete(EMPTY_PARAMETERS);
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
    this.engine.openPage(
      browser,
      "https://example.com/lost",
      SCRIPT_TIMEOUT_MILLISECONDS,
      new PageAudio(samples -> {}, System::nanoTime),
      notices::add
    );
    verify(browser, never()).loadURL(anyString());

    Await.until(PAGE_LOADED_AFTER_TIMEOUT_MESSAGE, () -> this.hasLoadedAPage(browser));
    verify(browser).loadURL("https://example.com/lost");
    verify(devTools).close();
    final List<String> expectedNotices = List.of(UNCONFIRMED_SCRIPTS_NOTICE);
    assertEquals(expectedNotices, notices);
  }

  @Test
  void shouldLoadAgainAPageWhoseScriptsAreConfirmedLateToRunThem() throws Exception {
    final CefBrowser browser = mock(CefBrowser.class);
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(browser.getDevToolsClient()).thenReturn(devTools);
    final CompletableFuture<String> late = new CompletableFuture<>();
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(late);
    final List<String> notices = new CopyOnWriteArrayList<>();
    this.engine.openPage(browser, "https://example.com/late", 100L, new PageAudio(samples -> {}, System::nanoTime), notices::add);
    Await.until(PAGE_LOADED_AFTER_TIMEOUT_MESSAGE, () -> this.hasLoadedAPage(browser));
    verify(browser, never()).reload();

    late.complete(EMPTY_PARAMETERS);
    EventQueue.invokeAndWait(() -> {});
    final InOrder order = inOrder(browser);
    order.verify(browser).loadURL("https://example.com/late");
    order.verify(browser).reload();
    final List<String> expectedNotices = List.of(
      UNCONFIRMED_SCRIPTS_NOTICE,
      "The scripts of the page were confirmed late; it loads again."
    );
    assertEquals(expectedNotices, notices);
  }

  @Test
  void shouldDescribeTheVersionWhenJcefReportsOne() {
    final String unknownDescription = this.engine.describe(null);
    assertEquals("unknown", unknownDescription);

    final CefApp.CefVersion version = mock(CefApp.CefVersion.class);
    when(version.getCefVersion()).thenReturn("146.0.10");
    when(version.getChromeVersion()).thenReturn("146.0.7680.179");
    final String versionDescription = this.engine.describe(version);
    assertEquals("146.0.10 (Chromium 146.0.7680.179)", versionDescription);
  }

  @Test
  void shouldOnlySendInputWhileTheBrowserHasADevToolsClient() {
    this.engine.execute(null, DevToolsInput.pressKey(ENTER_KEY));
    final CefDevToolsClient devTools = mock(CefDevToolsClient.class);
    when(devTools.executeDevToolsMethod(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(EMPTY_PARAMETERS));
    this.engine.execute(devTools, DevToolsInput.pressKey(ENTER_KEY));
    verify(devTools, times(2)).executeDevToolsMethod(eq(DevToolsInput.KEY_METHOD), anyString());
  }

  @Test
  void shouldDisposeOfWhatWasCreatedOnClose() {
    final CefApp application = mock(CefApp.class);
    this.engine.close(null, null, application);
    verify(application).dispose();

    final MCAVOffscreenBrowser browser = mock(MCAVOffscreenBrowser.class);
    final CefClient client = mock(CefClient.class);
    final CefApp second = mock(CefApp.class);
    this.engine.close(browser, client, second);
    final InOrder order = inOrder(browser, client, second);
    order.verify(browser).setCloseAllowed();
    order.verify(browser).close(true);
    order.verify(client).dispose();
    order.verify(second).dispose();
  }

  @Test
  void shouldWaitForCefAndSurviveFailuresAndInterruptsOnShutdown() {
    final CountDownLatch terminated = new CountDownLatch(1);
    this.engine.shutDown(terminated::countDown, terminated, SHUTDOWN_TIMEOUT_MILLISECONDS);
    final long remainingCount = terminated.getCount();
    assertEquals(0, remainingCount);

    try (final StandardError errors = new StandardError()) {
      this.engine.shutDown(this::failToDispose, new CountDownLatch(1), SHUTDOWN_TIMEOUT_MILLISECONDS);
      final boolean reportsTheDisposeFailure = errors
        .text()
        .contains("Because of \"java.lang.IllegalStateException: The disposal failed!\", CEF could not be shut down.");
      assertTrue(reportsTheDisposeFailure, errors.text());
    }

    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      this.engine.shutDown(() -> {}, new CountDownLatch(1), 60_000L);
      final boolean interruptKept = current.isInterrupted();
      assertTrue(interruptKept, "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
  }

  private void failToDispose() {
    final String message = "The disposal failed!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldIgnoreInputAndStopWhenTheEngineNeverStarted() throws Exception {
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> this.recordEventQueueFailure(failures, thread, failure));
    try (final StandardError errors = new StandardError()) {
      final CefEngine engine = new CefEngine();
      engine.dispatch(DevToolsInput.pressKey(ENTER_KEY));
      engine.stop();
      EventQueue.invokeAndWait(() -> {});
      final boolean reportsAShutdownFailure = errors.text().contains("CEF could not be shut down");
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
      final String fallback = this.engine.logFailedCall(new IllegalStateException("The browser is gone!"));
      assertEquals("", fallback);

      final boolean reportsTheFailedCall = errors
        .text()
        .contains("Because of \"java.lang.IllegalStateException: The browser is gone!\", a DevTools call failed.");
      assertTrue(reportsTheFailedCall, errors.text());
    }
  }

  @Test
  void shouldOnlyGiveLinuxANullDisplay(@TempDir final Path directory) throws IOException {
    final Path authority = directory.resolve(NullDisplay.AUTHORITY_FILE);
    final @Nullable NullDisplay nonLinuxDisplay = this.engine.startDisplay(OperatingSystem.OTHER, authority);
    assertNull(nonLinuxDisplay);

    final boolean authorityFileWritten = Files.exists(authority);
    assertFalse(authorityFileWritten, "no display, no authority file");

    try (final NullDisplay display = requireNonNull(this.engine.startDisplay(OperatingSystem.LINUX, authority))) {
      final boolean loopbackDisplay = display.getDisplay().startsWith("127.0.0.1:");
      assertTrue(loopbackDisplay);

      final boolean authorityFileWrittenForDisplay = Files.exists(authority);
      assertTrue(authorityFileWrittenForDisplay, "the helper's X clients find the cookie there");
    }
  }

  @Test
  void shouldBuildCefFromTheInstalledNativesOnTheChromiumThreadAfterTheConfinementNotice(@TempDir final Path folder) throws Exception {
    this.startRecordingEngine(folder);
    final List<String> log = this.events.getLog();
    assertEquals(List.of(CONFINEMENT_NOTICE), log);

    final String buildingThread = this.builder.getBuildingThread();
    assertEquals(CHROMIUM_THREAD_NAME, buildingThread);

    final File installation = this.builder.getInstallation();
    assertEquals(folder.resolve(NATIVES_FOLDER).toFile(), installation);

    final boolean skipsInstallation = this.builder.getSkipInstallation();
    assertTrue(skipsInstallation);

    final IProgressHandler progressHandler = this.builder.getProgressHandler();
    assertNotNull(progressHandler);
    final boolean logsProgressToTheConsole = progressHandler instanceof ConsoleProgressHandler;
    assertFalse(logsProgressToTheConsole);
  }

  private void startRecordingEngine(final Path folder) throws Exception {
    final HelperConfiguration configuration = this.startableConfiguration(folder);
    final PageCompositor painter = new PageCompositor(VIEW_WIDTH, VIEW_HEIGHT, FRAME_INTERVAL, SETTLE_MILLISECONDS, System::nanoTime);
    this.recordingEngine.start(configuration, painter, this.events);
  }

  private HelperConfiguration startableConfiguration(final Path folder) {
    return new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      folder.resolve(SOCKET_FILE),
      folder.resolve(NATIVES_FOLDER),
      folder.resolve(PROFILE_FOLDER),
      URI.create(PAGE_URL),
      VIEW_WIDTH,
      VIEW_HEIGHT,
      FRAME_INTERVAL,
      30,
      false,
      false,
      false,
      false,
      folder.resolve(SERVER_FOLDER),
      List.of()
    );
  }

  @Test
  void shouldGiveCefTheSwitchesTheSettingsAndAStateListenerThatReportsAFailedStart(@TempDir final Path folder) throws Exception {
    this.startRecordingEngine(folder);
    final List<String> switches = this.builder.getJcefArgs();
    final boolean jitless = switches.contains(JITLESS_SWITCH);
    assertTrue(jitless);

    final CefSettings settings = this.builder.getCefSettings();
    final String profile = settings.root_cache_path;
    assertEquals(folder.resolve(PROFILE_FOLDER).toString(), profile);

    final CefEngine.StateListener listener = assertInstanceOf(CefEngine.StateListener.class, this.builder.getStateHandler());
    listener.stateHasChanged(CefApp.CefAppState.INITIALIZATION_FAILED);
    final List<String> log = this.events.getLog();
    assertEquals(List.of(CONFINEMENT_NOTICE, INITIALIZATION_FAILURE_LINE), log);
  }

  @Test
  void shouldRouteChromiumThroughAGuardThatStopsWithTheEngine(@TempDir final Path folder) throws Exception {
    this.startRecordingEngine(folder);
    final List<String> switches = this.builder.getJcefArgs();
    final int port = this.parseSwitchNumber(switches, PROXY_SWITCH_PREFIX);
    final boolean acceptsBeforeStop = this.acceptsConnections(port);
    assertTrue(acceptsBeforeStop);

    this.recordingEngine.stop();
    final boolean acceptsAfterStop = this.acceptsConnections(port);
    assertFalse(acceptsAfterStop);
  }

  private int parseSwitchNumber(final List<String> switches, final String prefix) {
    final List<String> matching = switches
      .stream()
      .filter(value -> value.startsWith(prefix))
      .toList();
    final int count = matching.size();
    assertEquals(1, count, switches.toString());

    final String value = matching.getFirst();
    final String number = value.substring(prefix.length());
    return Integer.parseInt(number);
  }

  private boolean acceptsConnections(final int port) {
    try (final Socket _ = new Socket(LOOPBACK, port)) {
      return true;
    } catch (final IOException exception) {
      return false;
    }
  }

  @Test
  void shouldCreateAndFocusTheBrowserAndOpenThePageOnceCefCreatedIt(@TempDir final Path folder) throws Exception {
    try (final StandardError errors = new StandardError()) {
      this.startRecordingEngine(folder);
      final String text = errors.text();
      final int creation = this.locateReport(text, MCAVOffscreenBrowserTest.CREATION_REPORT);
      final int focus = this.locateReport(text, MCAVOffscreenBrowserTest.FOCUS_REPORT);
      assertTrue(creation < focus, text);
    }

    final ArgumentCaptor<CefLifeSpanHandler> handlers = ArgumentCaptor.forClass(CefLifeSpanHandler.class);
    verify(this.client).addLifeSpanHandler(handlers.capture());
    final ContentPolicy policy = assertInstanceOf(ContentPolicy.class, handlers.getValue());
    verify(this.client).addRequestHandler(policy);
    verify(this.client).addJSDialogHandler(policy);
    verify(this.client).addDownloadHandler(policy);
    verify(this.client).addDialogHandler(policy);
    verify(this.client).addContextMenuHandler(policy);
    verify(this.client).addLoadHandler(policy);

    final CefBrowser prepared = mock(CefBrowser.class);
    policy.onAfterCreated(prepared);
    verify(prepared).loadURL(PAGE_URL);
    final List<String> log = this.events.getLog();
    assertEquals(List.of(CONFINEMENT_NOTICE, "ready: unknown"), log);
  }

  private int locateReport(final String text, final Pattern report) {
    final Matcher matcher = report.matcher(text);
    final boolean found = matcher.find();
    assertTrue(found, text);

    return matcher.start();
  }

  @Test
  void shouldDisposeOfCefOnStop(@TempDir final Path folder) throws Exception {
    this.startRecordingEngine(folder);
    this.recordingEngine.stop();
    verify(this.application).dispose();
  }

  @Test
  void shouldCloseTheNullDisplayOnlyAfterCefOnLinux(@TempDir final Path folder) throws Exception {
    final String osName = requireNonNull(System.getProperty(OS_NAME_PROPERTY)); // checker framework
    final OperatingSystem system = OperatingSystem.fromName(osName);
    assumeTrue(system == OperatingSystem.LINUX, "The null display runs on Linux only!");

    this.startRecordingEngine(folder);
    final List<String> switches = this.builder.getJcefArgs();
    final int display = this.parseSwitchNumber(switches, DISPLAY_SWITCH_PREFIX);
    final int port = NullDisplay.X11_BASE_PORT + display;
    final AtomicBoolean accepted = new AtomicBoolean();
    doAnswer(_ -> this.terminateWhileCheckingDisplay(port, accepted))
      .when(this.application)
      .dispose();
    this.recordingEngine.stop();
    final boolean acceptsDuringDisposal = accepted.get();
    assertTrue(acceptsDuringDisposal);

    final boolean acceptsAfterStop = this.acceptsConnections(port);
    assertFalse(acceptsAfterStop);
  }

  private @Nullable Void terminateWhileCheckingDisplay(final int port, final AtomicBoolean accepted) {
    accepted.set(this.acceptsConnections(port));
    this.builder.terminate();

    return null;
  }

  private enum AllowedFeature {
    NONE,
    JAVASCRIPT_JIT,
    AUTOPLAY,
  }

  private static final class RecordingBuilder extends CefAppBuilder {

    private final CefApp application;

    private @Nullable File installation;
    private @Nullable IProgressHandler progressHandler;
    private @Nullable MavenCefAppHandlerAdapter stateHandler;
    private @Nullable String buildingThread;

    RecordingBuilder(final CefApp application) {
      this.application = application;
    }

    @Override
    public void setInstallDir(final File folder) {
      this.installation = folder;
    }

    @Override
    public void setProgressHandler(final IProgressHandler handler) {
      this.progressHandler = handler;
    }

    // CefAppBuilder's registers the handler in a static field of CefApp
    @Override
    public void setAppHandler(final MavenCefAppHandlerAdapter handler) {
      this.stateHandler = handler;
    }

    @Override
    public CefApp build() {
      final Thread current = Thread.currentThread();
      this.buildingThread = current.getName();

      return this.application;
    }

    void terminate() {
      final MavenCefAppHandlerAdapter handler = this.stateHandler;
      if (handler != null) {
        handler.stateHasChanged(CefApp.CefAppState.TERMINATED);
      }
    }

    @Nullable File getInstallation() {
      return this.installation;
    }

    @Nullable IProgressHandler getProgressHandler() {
      return this.progressHandler;
    }

    @Nullable MavenCefAppHandlerAdapter getStateHandler() {
      return this.stateHandler;
    }

    @Nullable String getBuildingThread() {
      return this.buildingThread;
    }
  }
}

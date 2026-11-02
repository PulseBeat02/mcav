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
import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.McavOffscreenBrowser;
import org.checkerframework.checker.nullness.qual.Nullable;

// CEF in the helper, off-screen on its external message pump, which JCEF drives from the AWT event thread (a headless
// JVM has one too). The helper never downloads: it runs the natives the server installed and verified.
final class CefEngine implements HelperEngine {

  private static final long STOP_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long SCRIPT_TIMEOUT_MILLISECONDS = 5_000L;

  // a DevTools client can lose every answer; a replacement can receive them
  private static final int PLACING_ATTEMPTS = 2;

  private static final String NOT_CONFIRMED = "not confirmed";

  private static final String CHROMIUM_THREAD = "mcav-browser-chromium";

  private static final String DISABLE_GPU_SWITCH = "--disable-gpu";
  private static final String DISABLE_GPU_COMPOSITING_SWITCH = "--disable-gpu-compositing";

  private static final String MUTE_AUDIO_SWITCH = "--mute-audio";
  private static final String DISABLE_AUDIO_OUTPUT_SWITCH = "--disable-audio-output";
  private static final String AUTOPLAY_POLICY_SWITCH = "--autoplay-policy=";
  private static final String AUTOPLAY_WITHOUT_GESTURE = "no-user-gesture-required";
  private static final String AUTOPLAY_AFTER_ACTIVATION = "document-user-activation-required";

  private static final String HIDE_SCROLLBARS_SWITCH = "--hide-scrollbars";
  private static final String DISABLE_EXTENSIONS_SWITCH = "--disable-extensions";
  private static final String DISABLE_COMPONENT_UPDATE_SWITCH = "--disable-component-update";
  private static final String DISABLE_BACKGROUND_NETWORKING_SWITCH = "--disable-background-networking";
  private static final String DISABLE_SYNC_SWITCH = "--disable-sync";
  private static final String DISABLE_DEFAULT_APPS_SWITCH = "--disable-default-apps";
  private static final String DISABLE_NOTIFICATIONS_SWITCH = "--disable-notifications";
  private static final String DENY_PERMISSION_PROMPTS_SWITCH = "--deny-permission-prompts";
  private static final String NO_FIRST_RUN_SWITCH = "--no-first-run";
  private static final String SITE_PER_PROCESS_SWITCH = "--site-per-process";
  private static final String DISABLE_WEBGPU_SWITCH = "--disable-webgpu";

  private static final String GAIA_URL_SWITCH = "--gaia-url=https://accounts.invalid";
  private static final String GOOGLE_BASE_URL_SWITCH = "--google-base-url=https://www.invalid";
  private static final String NETWORK_TIME_FEATURE = "NetworkTimeServiceQuerying";
  private static final String PRECONNECT_TO_SEARCH_FEATURE = "PreconnectToSearch";
  private static final String DISABLE_FEATURES_SWITCH = "--disable-features=";
  private static final String FEATURE_SEPARATOR = ",";

  private static final String JITLESS_SWITCH = "--js-flags=--jitless";

  private static final String PROXY_SERVER_SWITCH = "--proxy-server=socks5://127.0.0.1:";
  private static final String PROXY_BYPASS_LIST_SWITCH = "--proxy-bypass-list=<-loopback>";
  private static final String WEBRTC_POLICY_SWITCH = "--force-webrtc-ip-handling-policy=disable_non_proxied_udp";
  private static final String DISABLE_QUIC_SWITCH = "--disable-quic";

  private static final String HEADLESS_OZONE_SWITCH = "--ozone-platform=headless";
  private static final String BASIC_PASSWORD_STORE_SWITCH = "--password-store=basic";
  private static final String DISABLE_SHARED_MEMORY_SWITCH = "--disable-dev-shm-usage";
  private static final String DISPLAY_SWITCH = "--display=";

  private static final String MOCK_KEYCHAIN_SWITCH = "--use-mock-keychain";

  private static final String UNKNOWN_VERSION = "unknown";

  // CEF passes these in its own --disable-features before the helper's, and Chromium keeps only the last such switch
  @VisibleForTesting
  static final List<String> CEF_DISABLED_FEATURES = List.of(
    "AutofillActorMode",
    "GlicActorUi",
    "KillOnInvalidNavigationHeaders",
    "LensOverlay"
  );

  private final CountDownLatch terminated;

  private volatile @Nullable NetworkGuard guard;
  private volatile @Nullable NullDisplay display;
  private volatile @Nullable CefApp application;
  private volatile @Nullable CefClient client;
  private volatile @Nullable McavOffscreenBrowser browser;

  CefEngine() {
    this.terminated = new CountDownLatch(1);
  }

  static List<String> createSwitches(
    final HelperConfiguration configuration,
    final OperatingSystem system,
    final int guardPort,
    final @Nullable String display
  ) {
    final List<String> switches = new ArrayList<>();
    switches.add(DISABLE_GPU_SWITCH);
    switches.add(DISABLE_GPU_COMPOSITING_SWITCH);
    // the sound reaches the server through PageAudio on Chromium's own clock, not a server sound device, which may stall;
    // a page may play sound only after a click unless the options allow it (CEF's default)
    switches.add(MUTE_AUDIO_SWITCH);
    switches.add(DISABLE_AUDIO_OUTPUT_SWITCH);
    final boolean allowsAutoplay = configuration.isAutoplay();
    switches.add(AUTOPLAY_POLICY_SWITCH + (allowsAutoplay ? AUTOPLAY_WITHOUT_GESTURE : AUTOPLAY_AFTER_ACTIVATION));
    switches.add(HIDE_SCROLLBARS_SWITCH);
    switches.add(DISABLE_EXTENSIONS_SWITCH);
    switches.add(DISABLE_COMPONENT_UPDATE_SWITCH);
    switches.add(DISABLE_BACKGROUND_NETWORKING_SWITCH);
    switches.add(DISABLE_SYNC_SWITCH);
    switches.add(DISABLE_DEFAULT_APPS_SWITCH);
    switches.add(DISABLE_NOTIFICATIONS_SWITCH);
    switches.add(DENY_PERMISSION_PROMPTS_SWITCH);
    switches.add(NO_FIRST_RUN_SWITCH);
    switches.add(SITE_PER_PROCESS_SWITCH);
    switches.add(DISABLE_WEBGPU_SWITCH);
    // keeps Chromium from telling Google a browser runs here: sign-in and search go to names that cannot exist (RFC 6761),
    // and network time and the search preconnect are off
    switches.add(GAIA_URL_SWITCH);
    switches.add(GOOGLE_BASE_URL_SWITCH);
    final List<String> disabledFeatures = new ArrayList<>(CEF_DISABLED_FEATURES);
    disabledFeatures.add(NETWORK_TIME_FEATURE);
    disabledFeatures.add(PRECONNECT_TO_SEARCH_FEATURE);
    switches.add(DISABLE_FEATURES_SWITCH + String.join(FEATURE_SEPARATOR, disabledFeatures));

    if (!configuration.isJavaScriptJit()) {
      switches.add(JITLESS_SWITCH);
    }

    if (guardPort > 0) {
      // the guard carries loopback too; WebRTC uses proxied connections only, and QUIC, which SOCKS cannot carry, is off
      switches.add(PROXY_SERVER_SWITCH + guardPort);
      switches.add(PROXY_BYPASS_LIST_SWITCH);
      switches.add(WEBRTC_POLICY_SWITCH);
      switches.add(DISABLE_QUIC_SWITCH);
    }

    if (system == OperatingSystem.LINUX) {
      // no display server; a container's shared memory is often small, so Chromium uses temporary files
      switches.add(HEADLESS_OZONE_SWITCH);
      switches.add(BASIC_PASSWORD_STORE_SWITCH);
      switches.add(DISABLE_SHARED_MEMORY_SWITCH);
      if (display != null) {
        switches.add(DISPLAY_SWITCH + display);
      }
    }

    if (system == OperatingSystem.MAC) {
      switches.add(MOCK_KEYCHAIN_SWITCH);
    }

    return switches;
  }

  static void configureSettings(final CefSettings settings, final HelperConfiguration configuration) {
    final Path profile = configuration.getProfile();
    final String profilePath = profile.toString();
    settings.windowless_rendering_enabled = true;
    settings.root_cache_path = profilePath;
    settings.cache_path = profilePath;
    settings.persist_session_cookies = false;
    settings.remote_debugging_port = 0;
    settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
  }

  @Override
  public void start(final HelperConfiguration configuration, final McavOffscreenBrowser.PaintListener painter, final HelperEvents events)
    throws Exception {
    final String osName = System.getProperty("os.name");
    final OperatingSystem system = OperatingSystem.fromName(osName);
    final CefAppBuilder builder = new CefAppBuilder();
    final Path natives = configuration.getNatives();
    final File installation = natives.toFile();
    builder.setInstallDir(installation);
    // the server installed and verified the natives; jcefmaven's downloader must never run
    builder.setSkipInstallation(true);
    builder.setProgressHandler((state, percent) -> {});
    final int guardPort = this.startGuard(configuration, events);
    final Path socket = configuration.getSocket();
    final Path authority = socket.resolveSibling(NullDisplay.AUTHORITY_FILE);
    final NullDisplay startedDisplay = startDisplay(system, authority);
    this.display = startedDisplay;
    final List<String> switches = createSwitches(configuration, system, guardPort, NullDisplay.nameOf(startedDisplay));
    builder.addJcefArgs(switches.toArray(String[]::new));
    final CefSettings settings = builder.getCefSettings();
    configureSettings(settings, configuration);
    builder.setAppHandler(new StateListener(this.terminated, events));
    // on Linux the confinement holds for this thread and what it starts (the AWT event thread, CEF, Chromium), leaving the
    // guard, the display and the main thread free; on macOS it holds for the whole helper
    runOnOwnThread(CHROMIUM_THREAD, () -> this.confineAndStartChromium(builder, configuration, system, painter, events));
  }

  private @Nullable Void confineAndStartChromium(
    final CefAppBuilder builder,
    final HelperConfiguration configuration,
    final OperatingSystem system,
    final McavOffscreenBrowser.PaintListener painter,
    final HelperEvents events
  ) throws Exception {
    events.onNotice(ChromiumConfinement.confine(configuration, system));
    this.startChromium(builder, configuration, painter, events);

    return null;
  }

  // a restriction the task puts on its thread holds for that thread and what it starts only
  @VisibleForTesting
  static void runOnOwnThread(final String name, final Callable<?> task) throws Exception {
    final FutureTask<?> future = new FutureTask<>(task);
    final Thread thread = new Thread(future, name);
    thread.start();
    try {
      future.get();
    } catch (final ExecutionException exception) {
      final Throwable cause = Objects.requireNonNullElse(exception.getCause(), exception);
      if (cause instanceof final Error error) {
        throw error;
      }

      throw (Exception) cause;
    }
  }

  private void startChromium(
    final CefAppBuilder builder,
    final HelperConfiguration configuration,
    final McavOffscreenBrowser.PaintListener painter,
    final HelperEvents events
  ) throws Exception {
    final CefApp created = builder.build();
    this.application = created;
    final CefApp.@Nullable CefVersion version = created.getVersion();
    final String versionText = describe(version);
    final CefClient createdClient = created.createClient();
    this.client = createdClient;
    final URI address = configuration.getUrl();
    final String url = address.toString();
    final PageAudio audio = new PageAudio(events::onAudio, System::nanoTime);
    final ContentPolicy policy = new ContentPolicy(events, versionText, prepared -> this.openPreparedPage(prepared, url, audio, events));
    this.addHandlers(createdClient, policy);
    final CefBrowserSettings browserSettings = new CefBrowserSettings();
    browserSettings.windowless_frame_rate = configuration.getFrameRate();
    final int width = configuration.getWidth();
    final int height = configuration.getHeight();
    final McavOffscreenBrowser createdBrowser = new McavOffscreenBrowser(
      createdClient,
      NavigationPolicy.BLANK,
      width,
      height,
      painter,
      browserSettings
    );
    this.browser = createdBrowser;
    EventQueue.invokeAndWait(() -> this.createAndFocus(createdBrowser));
  }

  private void openPreparedPage(final CefBrowser prepared, final String url, final PageAudio audio, final HelperEvents events) {
    openPage(prepared, url, SCRIPT_TIMEOUT_MILLISECONDS, audio, events::onNotice);
  }

  private void createAndFocus(final McavOffscreenBrowser createdBrowser) {
    createdBrowser.createImmediately();
    createdBrowser.setFocus(true);
  }

  private int startGuard(final HelperConfiguration configuration, final HelperEvents events) throws IOException {
    if (configuration.isPrivateNetworks()) {
      return 0;
    }

    final List<String> refusedHosts = configuration.getRefusedHosts();
    final NetworkGuard startedGuard = NetworkGuard.start(events::onNotice, refusedHosts);
    this.guard = startedGuard;

    return startedGuard.getPort();
  }

  private void addHandlers(final CefClient client, final ContentPolicy policy) {
    client.addLifeSpanHandler(policy);
    client.addRequestHandler(policy);
    client.addJSDialogHandler(policy);
    client.addDownloadHandler(policy);
    client.addDialogHandler(policy);
    client.addContextMenuHandler(policy);
    client.addLoadHandler(policy);
  }

  static @Nullable NullDisplay startDisplay(final OperatingSystem system, final Path authority) throws IOException {
    if (system != OperatingSystem.LINUX) {
      return null;
    }

    return NullDisplay.start(authority);
  }

  // Places the window and sound scripts before the page loads, which would miss them otherwise. A JCEF DevTools client can
  // lose its answers, so an unanswered attempt is retried with a new client; after the last one the page loads anyway,
  // and loads again if the answer arrives late.
  static void openPage(
    final CefBrowser created,
    final String url,
    final long timeoutMilliseconds,
    final PageAudio audio,
    final Consumer<String> notices
  ) {
    placeScripts(created, url, timeoutMilliseconds, audio, notices, 1);
  }

  private static void placeScripts(
    final CefBrowser created,
    final String url,
    final long timeoutMilliseconds,
    final PageAudio audio,
    final Consumer<String> notices,
    final int attempt
  ) {
    final CefDevToolsClient devTools = created.getDevToolsClient();
    if (devTools == null) {
      created.loadURL(url);
      return;
    }

    devTools.addEventListener(audio);
    final List<DevToolsInput.DevToolsCall> calls = new ArrayList<>(DevToolsInput.openWindowsInPlace());
    calls.addAll(PageAudio.install());
    CompletableFuture<String> last = CompletableFuture.completedFuture("");
    for (final DevToolsInput.DevToolsCall call : calls) {
      final String method = call.getMethod();
      final String parameters = call.getParameters();
      last = devTools.executeDevToolsMethod(method, parameters);
      last.exceptionally(CefEngine::logFailedCall);
    }
    // a copy times out, so the answer itself can still arrive late
    final CompletableFuture<String> answered = last;
    final CompletableFuture<String> placed = answered.copy().completeOnTimeout(NOT_CONFIRMED, timeoutMilliseconds, TimeUnit.MILLISECONDS);
    final CompletableFuture<String> loading = placed.whenComplete((answer, failure) ->
      retryPlacingOrLoad(created, url, timeoutMilliseconds, audio, notices, attempt, devTools, answered, answer, failure)
    );
    loading.exceptionally(CefEngine::logFailedCall);
  }

  private static void retryPlacingOrLoad(
    final CefBrowser created,
    final String url,
    final long timeoutMilliseconds,
    final PageAudio audio,
    final Consumer<String> notices,
    final int attempt,
    final CefDevToolsClient devTools,
    final CompletableFuture<String> answered,
    final @Nullable String answer,
    final @Nullable Throwable failure
  ) {
    final boolean confirmed = failure == null && !NOT_CONFIRMED.equals(answer);
    if (!confirmed && attempt < PLACING_ATTEMPTS) {
      devTools.close();
      EventQueue.invokeLater(() -> placeScripts(created, url, timeoutMilliseconds, audio, notices, attempt + 1));
      return;
    }

    if (!confirmed) {
      notices.accept("The scripts of the page were not confirmed in %s attempts; it loads anyway.".formatted(attempt));
      final CompletableFuture<String> late = answered.thenApply(lateAnswer -> reloadOnLateAnswer(created, notices, lateAnswer));
      late.exceptionally(CefEngine::logFailedCall);
    }

    EventQueue.invokeLater(() -> created.loadURL(url));
  }

  private static String reloadOnLateAnswer(final CefBrowser created, final Consumer<String> notices, final String lateAnswer) {
    notices.accept("The scripts of the page were confirmed late; it loads again.");
    EventQueue.invokeLater(created::reload);

    return lateAnswer;
  }

  // not awaited: JCEF can lose the result of the very first call, and input needs no answer
  @Override
  public void dispatch(final List<DevToolsInput.DevToolsCall> calls) {
    final McavOffscreenBrowser current = this.browser;
    if (current == null) {
      return;
    }

    EventQueue.invokeLater(() -> execute(current.getDevToolsClient(), calls));
  }

  static String describe(final CefApp.@Nullable CefVersion version) {
    if (version == null) {
      return UNKNOWN_VERSION;
    }

    final String cefVersion = version.getCefVersion();
    final String chromeVersion = version.getChromeVersion();
    return "%s (Chromium %s)".formatted(cefVersion, chromeVersion);
  }

  static void execute(final @Nullable CefDevToolsClient devTools, final List<DevToolsInput.DevToolsCall> calls) {
    if (devTools == null) {
      return;
    }

    for (final DevToolsInput.DevToolsCall call : calls) {
      final String method = call.getMethod();
      final String parameters = call.getParameters();
      final CompletableFuture<String> result = devTools.executeDevToolsMethod(method, parameters);
      result.exceptionally(CefEngine::logFailedCall);
    }
  }

  @VisibleForTesting
  static String logFailedCall(final Throwable failure) {
    System.err.println("Because of \"%s\", a DevTools call failed.".formatted(failure));

    return "";
  }

  @Override
  public void stop() {
    final NetworkGuard currentGuard = this.guard;
    if (currentGuard != null) {
      currentGuard.close();
    }

    final McavOffscreenBrowser current = this.browser;
    final CefClient currentClient = this.client;
    final CefApp currentApplication = this.application;
    if (currentApplication != null) {
      shutDown(() -> close(current, currentClient, currentApplication), this.terminated, STOP_TIMEOUT_MILLISECONDS);
    }
    // the X library ends a process whose display goes away, so the display outlives CEF
    final NullDisplay currentDisplay = this.display;
    if (currentDisplay != null) {
      currentDisplay.close();
    }
  }

  static void close(final @Nullable McavOffscreenBrowser browser, final @Nullable CefClient client, final CefApp application) {
    if (browser != null) {
      browser.setCloseAllowed();
      browser.close(true);
    }

    if (client != null) {
      client.dispose();
    }

    application.dispose();
  }

  static void shutDown(final Runnable shutdown, final CountDownLatch terminated, final long timeoutMilliseconds) {
    try {
      EventQueue.invokeAndWait(shutdown);
      terminated.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } catch (final InvocationTargetException exception) {
      final Throwable cause = exception.getCause();
      System.err.println("Because of \"%s\", CEF could not be shut down.".formatted(cause));
    }
  }

  static final class StateListener extends MavenCefAppHandlerAdapter {

    private final CountDownLatch terminated;
    private final HelperEvents events;

    StateListener(final CountDownLatch terminated, final HelperEvents events) {
      this.terminated = terminated;
      this.events = events;
    }

    @Override
    public void stateHasChanged(final CefApp.CefAppState state) {
      if (state == CefApp.CefAppState.INITIALIZATION_FAILED) {
        this.events.onFailure("CEF failed to initialize");
        this.terminated.countDown();
      } else if (state == CefApp.CefAppState.TERMINATED) {
        this.terminated.countDown();
      }
    }
  }
}

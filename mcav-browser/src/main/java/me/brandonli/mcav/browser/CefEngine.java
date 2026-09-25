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

import java.awt.EventQueue;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

/**
 * The CEF browser of the helper process, started through jcefmaven's {@link CefAppBuilder} from the installation the
 * server prepared (the helper never downloads anything itself).
 *
 * <p>CEF runs with windowless rendering on its external message pump, which JCEF drives from the AWT event thread;
 * that thread exists in a headless JVM too, and no window is ever opened. Chromium is started with the headless
 * platform on Linux, without GPU, without sound, without extensions, background networking or component updates,
 * denying every permission prompt, with a mock keychain on macOS and a basic password store on Linux, and with V8's
 * JIT compiler off unless the configuration allows it. The profile lives in the folder the server gave, and the
 * remote debugging port stays closed. Unless the configuration allows private networks, every connection goes
 * through a {@link NetworkGuard} that lets pages reach public addresses only.
 */
final class CefEngine implements HelperEngine {

  private static final long STOP_TIMEOUT_MILLIS = 10_000L;
  private static final long SCRIPT_TIMEOUT_MILLIS = 1_000L;

  private final CountDownLatch terminated;
  private volatile @Nullable NetworkGuard guard;
  private volatile @Nullable CefApp app;
  private volatile @Nullable CefClient client;
  private volatile @Nullable McavOffscreenBrowser browser;

  /**
   * Constructs an engine that has not started yet.
   */
  CefEngine() {
    this.terminated = new CountDownLatch(1);
  }

  /**
   * Builds the command-line switches of Chromium.
   *
   * @param configuration the configuration of the helper
   * @param linux         whether the helper runs on Linux
   * @param mac           whether the helper runs on macOS
   * @param guardPort     the port of the network guard on the loopback interface, or 0 if pages may reach any address
   * @return the switches
   */
  static List<String> createSwitches(final HelperConfiguration configuration, final boolean linux, final boolean mac, final int guardPort) {
    final List<String> switches = new ArrayList<>();
    switches.add("--disable-gpu");
    switches.add("--disable-gpu-compositing");
    switches.add("--mute-audio");
    switches.add("--hide-scrollbars");
    switches.add("--disable-extensions");
    switches.add("--disable-component-update");
    switches.add("--disable-background-networking");
    switches.add("--disable-sync");
    switches.add("--disable-default-apps");
    switches.add("--disable-notifications");
    switches.add("--deny-permission-prompts");
    switches.add("--no-first-run");
    switches.add("--site-per-process");
    switches.add("--disable-webgpu");
    if (!configuration.isJavaScriptJit()) {
      switches.add("--js-flags=--jitless");
    }
    if (guardPort > 0) {
      // every connection goes through the guard, loopback included, which Chromium would otherwise reach directly;
      // WebRTC may only use proxied connections, and QUIC, which a SOCKS proxy cannot carry, is off
      switches.add("--proxy-server=socks5://127.0.0.1:" + guardPort);
      switches.add("--proxy-bypass-list=<-loopback>");
      switches.add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
      switches.add("--disable-quic");
    }
    if (linux) {
      switches.add("--ozone-platform=headless");
      switches.add("--password-store=basic");
    }
    if (mac) {
      switches.add("--use-mock-keychain");
    }
    return switches;
  }

  /**
   * Configures the settings of CEF: windowless rendering, the profile folder, no persistent cookies, no remote
   * debugging port, and warnings and errors only in the log.
   *
   * @param settings      the settings to change
   * @param configuration the configuration of the helper
   */
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
    final String os = osName.toLowerCase(Locale.ROOT);
    final boolean linux = os.contains("linux");
    final boolean mac = os.contains("mac");
    final CefAppBuilder builder = new CefAppBuilder();
    final Path natives = configuration.getNatives();
    final File installation = natives.toFile();
    builder.setInstallDir(installation);
    // the server installed and verified the natives; jcefmaven's own downloader must never run
    builder.setSkipInstallation(true);
    builder.setProgressHandler((state, percent) -> {});
    int guardPort = 0;
    if (!configuration.isPrivateNetworks()) {
      final NetworkGuard startedGuard = NetworkGuard.start(events::onNotice);
      this.guard = startedGuard;
      guardPort = startedGuard.getPort();
    }
    final List<String> switches = createSwitches(configuration, linux, mac, guardPort);
    builder.addJcefArgs(switches.toArray(String[]::new));
    final CefSettings settings = builder.getCefSettings();
    configureSettings(settings, configuration);
    builder.setAppHandler(new StateListener(this.terminated, events));
    final CefApp created = builder.build();
    this.app = created;
    final String versionText = describe(created.getVersion());
    final CefClient createdClient = created.createClient();
    this.client = createdClient;
    final String url = configuration.getUrl().toString();
    final ContentPolicy policy = new ContentPolicy(events, versionText, prepared -> openPage(prepared, url, SCRIPT_TIMEOUT_MILLIS));
    createdClient.addLifeSpanHandler(policy);
    createdClient.addRequestHandler(policy);
    createdClient.addJSDialogHandler(policy);
    createdClient.addDownloadHandler(policy);
    createdClient.addDialogHandler(policy);
    createdClient.addContextMenuHandler(policy);
    createdClient.addLoadHandler(policy);
    final CefBrowserSettings browserSettings = new CefBrowserSettings();
    browserSettings.windowless_frame_rate = configuration.getFrameRate();
    final int width = configuration.getWidth();
    final int height = configuration.getHeight();
    // the browser starts on the empty document and loads the page once it is prepared, see openPage
    final McavOffscreenBrowser createdBrowser = new McavOffscreenBrowser(
      createdClient,
      NavigationPolicy.BLANK,
      width,
      height,
      painter,
      browserSettings
    );
    this.browser = createdBrowser;
    EventQueue.invokeAndWait(() -> {
      createdBrowser.createImmediately();
      createdBrowser.setFocus(true);
    });
  }

  /**
   * Prepares a browser that was just created on the empty document, on CEF's thread, and then loads its page: the
   * script that opens new windows in place is added to every document it will show first. The browser of the helper
   * is off-screen, so JCEF cancels every popup before the policy hears of it. The calls reach the page asynchronously,
   * so the page is only loaded once the script is in place; JCEF can lose the answer of a call, so after the timeout it
   * is loaded anyway.
   *
   * @param created       the browser
   * @param url           the address of the page
   * @param timeoutMillis how long to wait for the script at most
   */
  static void openPage(final CefBrowser created, final String url, final long timeoutMillis) {
    final CefDevToolsClient devTools = created.getDevToolsClient();
    if (devTools == null) {
      created.loadURL(url);
      return;
    }
    CompletableFuture<String> last = CompletableFuture.completedFuture("");
    for (final DevToolsInput.DevToolsCall call : DevToolsInput.openWindowsInPlace()) {
      last = devTools.executeDevToolsMethod(call.getMethod(), call.getParameters());
      last.exceptionally(CefEngine::logFailedCall);
    }
    final CompletableFuture<String> placed = last.completeOnTimeout("", timeoutMillis, TimeUnit.MILLISECONDS);
    final CompletableFuture<String> loading = placed.whenComplete((answer, failure) -> EventQueue.invokeLater(() -> created.loadURL(url)));
    loading.exceptionally(CefEngine::logFailedCall);
  }

  /**
   * Sends the calls to the DevTools client of the browser on the event thread, which is CEF's thread for them. The
   * results are not awaited: JCEF can lose the result of the very first call, and input needs no answer.
   *
   * @param calls the DevTools calls of the input
   */
  @Override
  public void dispatch(final List<DevToolsInput.DevToolsCall> calls) {
    final McavOffscreenBrowser current = this.browser;
    if (current == null) {
      return;
    }
    EventQueue.invokeLater(() -> execute(current.getDevToolsClient(), calls));
  }

  /**
   * Describes the version of CEF and Chromium.
   *
   * @param version the version JCEF reports, or null if it reports none
   * @return the description, such as {@code 146.0.10 (Chromium 146.0.7680.179)}
   */
  static String describe(final CefApp.@Nullable CefVersion version) {
    if (version == null) {
      return "unknown";
    }
    return version.getCefVersion() + " (Chromium " + version.getChromeVersion() + ")";
  }

  /**
   * Sends DevTools calls without waiting for their answers.
   *
   * @param devTools the DevTools client of the browser, or null once the browser is closing
   * @param calls    the calls
   */
  static void execute(final @Nullable CefDevToolsClient devTools, final List<DevToolsInput.DevToolsCall> calls) {
    if (devTools == null) {
      return;
    }
    for (final DevToolsInput.DevToolsCall call : calls) {
      final String method = call.getMethod();
      final String parameters = call.getParameters();
      final CompletableFuture<String> result = devTools.executeDevToolsMethod(method, parameters);
      // input needs no answer; a failed call is written to the log of the helper
      result.exceptionally(CefEngine::logFailedCall);
    }
  }

  private static String logFailedCall(final Throwable failure) {
    final String message = "A DevTools call failed: " + failure;
    System.err.println(message);
    return message;
  }

  /**
   * Closes the network guard and the browser, disposes of the client and shuts CEF down, waiting up to ten seconds for
   * CEF to terminate.
   */
  @Override
  public void stop() {
    final NetworkGuard currentGuard = this.guard;
    if (currentGuard != null) {
      currentGuard.close();
    }
    final McavOffscreenBrowser current = this.browser;
    final CefClient currentClient = this.client;
    final CefApp currentApp = this.app;
    if (currentApp == null) {
      return;
    }
    shutDown(() -> close(current, currentClient, currentApp), this.terminated, STOP_TIMEOUT_MILLIS);
  }

  /**
   * Closes the browser and disposes of the client and of CEF, as far as they were created.
   *
   * @param browser the browser, or null if it was never created
   * @param client  the client, or null if it was never created
   * @param app     CEF
   */
  static void close(final @Nullable McavOffscreenBrowser browser, final @Nullable CefClient client, final CefApp app) {
    if (browser != null) {
      browser.setCloseAllowed();
      browser.close(true);
    }
    if (client != null) {
      client.dispose();
    }
    app.dispose();
  }

  /**
   * Runs the shutdown on the event thread, CEF's thread, and waits for CEF to terminate. A failed shutdown is written
   * to the log of the helper, and an interrupt ends the waiting and is kept.
   *
   * @param shutdown      the shutdown
   * @param terminated    counts down when CEF has terminated
   * @param timeoutMillis how long to wait for CEF at most
   */
  static void shutDown(final Runnable shutdown, final CountDownLatch terminated, final long timeoutMillis) {
    try {
      EventQueue.invokeAndWait(shutdown);
      terminated.await(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    } catch (final InvocationTargetException exception) {
      final Throwable cause = exception.getCause();
      System.err.println("Failed to shut CEF down: " + cause);
    }
  }

  /**
   * Counts down when CEF has terminated, and reports a CEF that failed to initialize, which leaves nothing to show.
   */
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

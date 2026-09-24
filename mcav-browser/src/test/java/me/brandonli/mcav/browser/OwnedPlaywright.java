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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Playwright;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.AdditionalAnswers;

/**
 * Creates a real owned Playwright connection and records its exact browser process for destructive tests.
 * All Playwright calls, including CDP discovery, run on the player's browser thread. The test thread only sees
 * a ProcessHandle; it never touches the browser connection or guesses ownership from executable names.
 */
final class OwnedPlaywright {

  private final AtomicReference<@Nullable ProcessHandle> browserProcess = new AtomicReference<>();

  Playwright create() {
    this.browserProcess.set(null);
    final Map<String, String> environment = PlaywrightInstaller.getEnvironment();
    final Playwright.CreateOptions options = new Playwright.CreateOptions();
    options.setEnv(environment);
    final Playwright realConnection = Playwright.create(options);
    try {
      final BrowserType realChromium = realConnection.chromium();
      final BrowserType chromium = mock(BrowserType.class, AdditionalAnswers.delegatesTo(realChromium));
      doAnswer(invocation -> {
        final BrowserType.LaunchOptions launchOptions = invocation.getArgument(0);
        final Browser browser = realChromium.launch(launchOptions);
        try {
          final ProcessHandle process = discoverBrowserProcess(browser);
          this.browserProcess.set(process);
          return browser;
        } catch (final RuntimeException | Error failure) {
          browser.close();
          throw failure;
        }
      })
        .when(chromium)
        .launch(any(BrowserType.LaunchOptions.class));
      final Playwright connection = mock(Playwright.class, AdditionalAnswers.delegatesTo(realConnection));
      doReturn(chromium).when(connection).chromium();
      // close() still delegates to the real owned connection, including after a browser crash.
      return connection;
    } catch (final RuntimeException | Error failure) {
      realConnection.close();
      throw failure;
    }
  }

  ProcessHandle process() {
    final ProcessHandle process = this.browserProcess.get();
    return Objects.requireNonNull(process, "CDP must identify the owned browser during a successful start");
  }

  String describeProcess() {
    final ProcessHandle process = this.browserProcess.get();
    if (process == null) {
      return "owned browser process not discovered";
    }
    final long identifier = process.pid();
    final boolean alive = process.isAlive();
    final ProcessHandle.Info info = process.info();
    final Optional<String> command = info.command();
    final Optional<ProcessHandle> parent = process.parent();
    final Optional<Long> parentIdentifier = parent.map(ProcessHandle::pid);
    return "owned browser pid=" + identifier + ", alive=" + alive + ", command=" + command + ", parent=" + parentIdentifier;
  }

  private static ProcessHandle discoverBrowserProcess(final Browser browser) {
    final CDPSession session = browser.newBrowserCDPSession();
    try {
      final JsonObject response = session.send("SystemInfo.getProcessInfo");
      final JsonArray processes = response.getAsJsonArray("processInfo");
      for (final JsonElement element : processes) {
        final JsonObject process = element.getAsJsonObject();
        final JsonElement type = process.get("type");
        final String name = type.getAsString();
        if (!name.equals("browser")) {
          continue;
        }
        final JsonElement identifier = process.get("id");
        final long processId = identifier.getAsLong();
        final ProcessHandle owner = ProcessHandle.current();
        try (final Stream<ProcessHandle> descendants = owner.descendants()) {
          final Stream<ProcessHandle> matching = descendants.filter(candidate -> candidate.pid() == processId);
          final Optional<ProcessHandle> handle = matching.findFirst();
          return handle.orElseThrow(() -> new IllegalStateException("CDP browser PID is not a JVM descendant: " + processId));
        }
      }
      throw new IllegalStateException("CDP did not report a browser process: " + response);
    } finally {
      session.detach();
    }
  }
}

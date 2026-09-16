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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;

/**
 * Skips tests that need a browser the machine does not have, instead of failing them. Servers often have no Chrome,
 * and a machine without internet access cannot download the browser of Playwright. Each check runs once per test run.
 */
final class ExternalBrowsers {

  private static final String PLAYWRIGHT_DOWNLOAD_HOST = "cdn.playwright.dev";
  private static final int HTTPS_PORT = 443;
  private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
  private static final URI BLANK_PAGE = URI.create("about:blank");

  private static String chromeProblem;
  private static String playwrightProblem;

  private ExternalBrowsers() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Skips the test unless Chrome is installed and its ChromeDriver can be resolved, online or from the cache.
   */
  static void assumeChrome() {
    final String problem = getChromeProblem();
    final boolean available = problem.isEmpty();
    assumeTrue(available, problem);
  }

  /**
   * Skips the test unless the headless Chromium of Playwright is installed, or can be downloaded.
   */
  static void assumePlaywrightBrowser() {
    final String problem = getPlaywrightProblem();
    final boolean available = problem.isEmpty();
    assumeTrue(available, problem);
  }

  private static synchronized String getChromeProblem() {
    if (chromeProblem == null) {
      chromeProblem = findChromeProblem();
    }
    return chromeProblem;
  }

  private static synchronized String getPlaywrightProblem() {
    if (playwrightProblem == null) {
      playwrightProblem = findPlaywrightProblem();
    }
    return playwrightProblem;
  }

  private static String findChromeProblem() {
    // WebDriverManager finds Chrome the way ChromeDriver does, through the registry or the known commands
    final WebDriverManager manager = WebDriverManager.chromedriver();
    final Optional<Path> chrome = manager.getBrowserPath();
    if (chrome.isEmpty()) {
      return "Chrome is not installed on this machine";
    }
    try {
      ChromeDriverProvider.prepare();
      return "";
    } catch (final PlayerException exception) {
      final String message = exception.getMessage();
      return "ChromeDriver is not available: " + message;
    }
  }

  private static String findPlaywrightProblem() {
    // starting the real player without installing anything shows whether the browser is there and can run
    final PlaywrightPlayer probe = new PlaywrightPlayer(() -> {}, Duration.ofMinutes(2), Duration.ofSeconds(15));
    final BrowserSource source = BrowserSource.uri(BLANK_PAGE, 50, 64, 64, 1);
    try {
      probe.start(source);
      return "";
    } catch (final PlayerException exception) {
      final String rawMessage = exception.getMessage();
      final String message = String.valueOf(rawMessage);
      final boolean missingBrowser = message.contains("Executable doesn't exist");
      final boolean missingLibraries = message.contains("missing dependencies");
      if (missingLibraries) {
        return "the system libraries Chromium needs are missing: " + message;
      }
      if (missingBrowser && !canReachDownloads()) {
        return "the browser of Playwright is not installed and cannot be downloaded without internet access";
      }
      return "";
    } finally {
      probe.release();
    }
  }

  private static boolean canReachDownloads() {
    try {
      final InetAddress[] addresses = InetAddress.getAllByName(PLAYWRIGHT_DOWNLOAD_HOST);
      return canReachAny(addresses);
    } catch (final UnknownHostException exception) {
      return false;
    }
  }

  // the host can resolve to IPv4 and IPv6 addresses, of which only some may be reachable from this machine
  private static boolean canReachAny(final InetAddress[] addresses) {
    for (final InetAddress address : addresses) {
      final InetSocketAddress socketAddress = new InetSocketAddress(address, HTTPS_PORT);
      final boolean reachable = canConnect(socketAddress);
      if (reachable) {
        return true;
      }
    }
    return false;
  }

  private static boolean canConnect(final InetSocketAddress address) {
    try (final Socket socket = new Socket()) {
      socket.connect(address, CONNECT_TIMEOUT_MILLIS);
      return true;
    } catch (final IOException exception) {
      return false;
    }
  }
}

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
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;

/**
 * Skips tests that need a browser the machine does not have, instead of failing them. Servers often have no Chrome,
 * and a machine without internet access cannot download the browser of Playwright. Each check runs once per test run.
 */
final class ExternalBrowsers {

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
    // Preparing the cached executable requires no running browser. The class fixture owns the actual launch.
    try {
      PlaywrightInstaller.ensureInstalled();
      return "";
    } catch (final PlayerException exception) {
      final String message = exception.getMessage();
      return "the browser of Playwright cannot be prepared: " + message;
    }
  }
}

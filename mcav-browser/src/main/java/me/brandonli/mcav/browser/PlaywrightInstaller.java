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
import com.microsoft.playwright.impl.driver.Driver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.PlayerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the headless Chromium shell Playwright needs, and nothing else.
 *
 * <p>By default Playwright downloads Chromium, Firefox, and WebKit on first use, more than a gigabyte, and
 * gives up after three minutes. This installer runs the Playwright command line for the headless shell only, with a
 * limit of thirty minutes, and tells Playwright to skip its own download afterwards. Browsers land in the Playwright
 * cache of the user, so only the first start on a machine downloads anything. An installation that times out or whose
 * thread is interrupted is killed.
 */
final class PlaywrightInstaller {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightInstaller.class);
  private static final String SKIP_DOWNLOAD = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD";
  private static final String BROWSER = "chromium-headless-shell";
  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(30);
  private static final long KILL_TIMEOUT_SECONDS = 10L;
  private static final Installation INSTALLATION = new Installation(PlaywrightInstaller::install);

  private PlaywrightInstaller() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the environment Playwright must be created with so it does not download every browser.
   *
   * @return the environment variables
   */
  static Map<String, String> getEnvironment() {
    return Map.of(SKIP_DOWNLOAD, "1");
  }

  /**
   * Installs the headless Chromium shell if it is missing. Safe to call more than once.
   *
   * @throws PlayerException if the installation fails
   */
  static void ensureInstalled() {
    INSTALLATION.ensure();
  }

  /**
   * Installs a browser with the Playwright command-line tool.
   *
   * @param browser the browser, such as {@code chromium-headless-shell}
   * @throws PlayerException if the installation fails
   */
  @VisibleForTesting
  static void install(final String browser) {
    final Map<String, String> environment = getEnvironment();
    final Driver driver = Driver.ensureDriverInstalled(environment, false);
    final ProcessBuilder builder = driver.createProcessBuilder();
    final List<String> command = builder.command();
    command.add("install");
    command.add(browser);
    run(builder, browser, INSTALL_TIMEOUT);
  }

  /**
   * Runs an installer process and waits for it. The process is killed, with the processes it started, if it does not
   * finish in time or the waiting thread is interrupted.
   *
   * @param builder the installer process
   * @param browser the browser it installs, used in error messages
   * @param timeout how long the installation may take
   * @throws PlayerException if the process cannot be started, fails, times out, or the wait is interrupted
   */
  @VisibleForTesting
  static void run(final ProcessBuilder builder, final String browser, final Duration timeout) {
    builder.redirectErrorStream(true);
    try {
      // the output goes to a file, so a silent installer cannot block the timeout by never closing its output
      final Path log = Files.createTempFile("mcav-playwright-install", ".log");
      try {
        runLogged(builder, browser, timeout, log);
      } finally {
        deleteLog(log);
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to run the Playwright installer: " + message, exception);
    }
  }

  /**
   * Runs an installer process with its output going to a log file, and fails with that output if it fails.
   *
   * @param builder the installer process
   * @param browser the browser it installs, used in error messages
   * @param timeout how long the installation may take
   * @param log     the log file
   * @throws IOException     if the process cannot be started or the log cannot be read
   * @throws PlayerException if the process fails, times out, or the wait is interrupted
   */
  private static void runLogged(final ProcessBuilder builder, final String browser, final Duration timeout, final Path log)
    throws IOException {
    final File logFile = log.toFile();
    builder.redirectOutput(logFile);
    final Process process = builder.start();
    awaitExit(process, browser, timeout);

    final int exitCode = process.exitValue();
    if (exitCode != 0) {
      final byte[] bytes = Files.readAllBytes(log);
      final String output = new String(bytes, StandardCharsets.UTF_8);
      throw new PlayerException("Playwright failed to install " + browser + " (exit code " + exitCode + "): " + output);
    }
  }

  /**
   * Deletes the log file of an installer.
   *
   * <p>A killed installer, or a virus scanner, may still hold the file on Windows; it is then removed when the JVM
   * exits, and the outcome of the installation is never replaced by a failure to clean up.
   *
   * @param log the log file
   */
  private static void deleteLog(final Path log) {
    final File logFile = log.toFile();
    logFile.deleteOnExit();
    final boolean deleted = logFile.delete();
    LOGGER.debug("Deleted the Playwright installer log {} right away: {}", log, deleted);
  }

  private static void awaitExit(final Process process, final String browser, final Duration timeout) {
    final long timeoutMillis = timeout.toMillis();
    final boolean exited;
    try {
      exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      kill(process);
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new PlayerException("Interrupted while installing " + browser, exception);
    }
    if (!exited) {
      kill(process);
      throw new PlayerException("Playwright did not install " + browser + " within " + timeoutMillis + " ms");
    }
  }

  /**
   * Kills a process and the processes it started, and waits a moment for it to exit so its files are released.
   *
   * @param process the process
   */
  private static void kill(final Process process) {
    final Stream<ProcessHandle> descendants = process.descendants();
    descendants.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    final CompletableFuture<Process> exit = process.onExit();
    final CompletableFuture<Process> bounded = exit.completeOnTimeout(process, KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    bounded.join();
  }

  /**
   * Remembers whether a browser was installed, so it is installed at most once.
   */
  @VisibleForTesting
  static final class Installation {

    private final Consumer<String> installer;
    private boolean installed;

    /**
     * Constructs an installation that has not run yet.
     *
     * @param installer installs a browser by name, throwing a {@link PlayerException} if it cannot
     */
    Installation(final Consumer<String> installer) {
      this.installer = installer;
    }

    /**
     * Installs the headless Chromium shell unless an earlier call succeeded. A failed installation is tried again
     * by the next call.
     *
     * @throws PlayerException if the installation fails
     */
    synchronized void ensure() {
      if (this.installed) {
        return;
      }
      final long start = System.currentTimeMillis();
      this.installer.accept(BROWSER);
      this.installed = true;
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      LOGGER.info("Playwright {} ready in {} ms", BROWSER, elapsed);
    }
  }
}

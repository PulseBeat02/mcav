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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link PlaywrightInstaller}. The installer processes are replaced by small Java programs; one test installs
 * the real browser, which only checks the installation where it is already installed.
 */
final class PlaywrightInstallerTest {

  private static final Duration LONG_TIMEOUT = Duration.ofMinutes(1);

  @TempDir
  private Path directory;

  private static String javaExecutable() {
    final ProcessHandle current = ProcessHandle.current();
    final ProcessHandle.Info info = current.info();
    final Optional<String> command = info.command();
    return command.orElseThrow();
  }

  private ProcessBuilder sleepingInstaller() throws IOException {
    final Path program = this.directory.resolve("Sleeper.java");
    Files.writeString(
      program,
      "public class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(60_000L); } }"
    );
    final String java = javaExecutable();
    final String programPath = program.toString();
    return new ProcessBuilder(java, programPath);
  }

  private static List<ProcessHandle> childrenStartedAfter(final Instant start) {
    final ProcessHandle current = ProcessHandle.current();
    final Stream<ProcessHandle> children = current.children();
    final List<ProcessHandle> all = children.toList();
    final List<ProcessHandle> started = new ArrayList<>();
    for (final ProcessHandle child : all) {
      final ProcessHandle.Info info = child.info();
      final Optional<Instant> startInstant = info.startInstant();
      final Instant childStart = startInstant.orElse(Instant.MAX);
      final boolean alive = child.isAlive();
      if (alive && !childStart.isBefore(start)) {
        started.add(child);
      }
    }
    return started;
  }

  @Test
  void skipsTheBrowserDownloadOfPlaywright() {
    final Map<String, String> environment = PlaywrightInstaller.getEnvironment();
    final Map<String, String> expected = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    assertEquals(expected, environment);
  }

  @Test
  void installsTheHeadlessShellOnlyOnce() {
    final List<String> installed = new CopyOnWriteArrayList<>();
    final PlaywrightInstaller.Installation installation = new PlaywrightInstaller.Installation(installed::add);
    installation.ensure();
    installation.ensure();
    final List<String> expected = List.of("chromium-headless-shell");
    assertEquals(expected, installed);
  }

  @Test
  void triesAFailedInstallationAgain() {
    final List<String> attempts = new CopyOnWriteArrayList<>();
    final PlayerException failure = new PlayerException("download failed");
    final PlaywrightInstaller.Installation installation = new PlaywrightInstaller.Installation(browser -> {
      attempts.add(browser);
      if (attempts.size() == 1) {
        throw failure;
      }
    });
    final PlayerException exception = assertThrows(PlayerException.class, installation::ensure);
    installation.ensure();
    installation.ensure();
    final int count = attempts.size();
    assertEquals(failure, exception);
    assertEquals(2, count);
  }

  @Test
  void installsTheRealHeadlessShell() {
    ExternalBrowsers.assumePlaywrightBrowser();
    assertDoesNotThrow(PlaywrightInstaller::ensureInstalled);
  }

  @Test
  void reportsBrowsersPlaywrightDoesNotKnow() {
    final PlayerException exception = assertThrows(PlayerException.class, () -> PlaywrightInstaller.install("mcav-no-such-browser"));
    final String message = exception.getMessage();
    final boolean namesBrowser = message.startsWith("Playwright failed to install mcav-no-such-browser (exit code ");
    assertTrue(namesBrowser, message);
  }

  @Test
  void reportsInstallersThatCannotBeStarted() {
    final ProcessBuilder builder = new ProcessBuilder("mcav-program-that-does-not-exist");
    final PlayerException exception = assertThrows(PlayerException.class, () -> PlaywrightInstaller.run(builder, "chromium", LONG_TIMEOUT));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean explains = message.startsWith("Failed to run the Playwright installer: ");
    assertTrue(explains, message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void acceptsInstallersThatSucceed() {
    final String java = javaExecutable();
    final ProcessBuilder builder = new ProcessBuilder(java, "-version");
    assertDoesNotThrow(() -> PlaywrightInstaller.run(builder, "chromium", LONG_TIMEOUT));
  }

  @Test
  void includesTheOutputOfFailedInstallers() {
    final String java = javaExecutable();
    final ProcessBuilder builder = new ProcessBuilder(java, "-mcav-invalid-option");
    final PlayerException exception = assertThrows(PlayerException.class, () -> PlaywrightInstaller.run(builder, "chromium", LONG_TIMEOUT));
    final String message = exception.getMessage();
    final boolean hasOutput = message.contains("mcav-invalid-option");
    assertTrue(hasOutput, message);
  }

  @Test
  void killsInstallersThatTakeTooLong() throws IOException {
    final ProcessBuilder builder = this.sleepingInstaller();
    final Duration shortTimeout = Duration.ofSeconds(2);
    final Instant start = Instant.now();
    final PlayerException exception = assertThrows(PlayerException.class, () -> PlaywrightInstaller.run(builder, "chromium", shortTimeout));
    final String message = exception.getMessage();
    final List<ProcessHandle> survivors = childrenStartedAfter(start);
    final boolean noSurvivors = survivors.isEmpty();
    assertEquals("Playwright did not install chromium within 2000 ms", message);
    assertTrue(noSurvivors, survivors::toString);
  }

  @Test
  void killsTheInstallerAndKeepsTheInterruptWhenInterruptedWhileWaiting() throws IOException {
    final ProcessBuilder builder = this.sleepingInstaller();
    final Instant start = Instant.now();
    final Thread current = Thread.currentThread();
    current.interrupt();
    final PlayerException exception;
    try {
      exception = assertThrows(PlayerException.class, () -> PlaywrightInstaller.run(builder, "chromium", LONG_TIMEOUT));
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final List<ProcessHandle> survivors = childrenStartedAfter(start);
    final boolean noSurvivors = survivors.isEmpty();
    assertEquals("Interrupted while installing chromium", message);
    assertInstanceOf(InterruptedException.class, cause);
    assertTrue(noSurvivors, survivors::toString);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(PlaywrightInstaller.class);
  }
}

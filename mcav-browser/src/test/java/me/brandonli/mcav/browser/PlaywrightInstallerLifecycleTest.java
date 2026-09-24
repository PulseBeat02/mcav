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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.impl.driver.Driver;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

/** Verifies installer ownership at the process boundary without starting another browser. */
final class PlaywrightInstallerLifecycleTest {

  @Test
  void reportsElapsedInstallationTime() {
    final Logger logger = mock(Logger.class);
    final List<String> installed = new ArrayList<>();
    final PlaywrightInstaller.Installation installation = new PlaywrightInstaller.Installation(installed::add);
    final long before = System.currentTimeMillis();
    installation.ensure(logger);
    installation.ensure(logger);
    final long after = System.currentTimeMillis();
    final ArgumentCaptor<Long> elapsed = ArgumentCaptor.forClass(Long.class);
    verify(logger).info(eq("Playwright {} ready in {} ms"), eq("chromium-headless-shell"), elapsed.capture());
    final long reported = elapsed.getValue();
    assertTrue(reported >= 0 && reported <= after - before);
    assertEquals(List.of("chromium-headless-shell"), installed);
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void destroysTheInstallerAndItsDescendantsOnAbandonedWaits(final boolean interrupted) throws Exception {
    final Process process = mock(Process.class);
    final ProcessHandle descendant = mock(ProcessHandle.class);
    final ProcessBuilder builder = mock(ProcessBuilder.class);
    final Path path = mock(Path.class);
    final File file = mock(File.class);
    when(path.toFile()).thenReturn(file);
    when(builder.start()).thenReturn(process);
    when(process.descendants()).thenAnswer(_ -> Stream.of(descendant));
    final CompletableFuture<Process> exited = CompletableFuture.completedFuture(process);
    when(process.onExit()).thenReturn(exited);
    if (interrupted) {
      when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException("cancelled"));
    }
    try (final MockedStatic<Files> files = mockStatic(Files.class)) {
      files.when(() -> Files.createTempFile("mcav-playwright-install", ".log")).thenReturn(path);
      final Duration timeout = Duration.ofMillis(1);
      try {
        assertThrows(PlayerException.class, () -> PlaywrightInstaller.run(builder, "chromium", timeout));
      } finally {
        final boolean retained = Thread.interrupted();
        assertEquals(interrupted, retained);
      }
      verify(descendant).destroyForcibly();
      verify(process).destroyForcibly();
      verify(file).deleteOnExit();
      verify(file).delete();
    }
  }

  @Test
  void installsThroughThePublicBridgeAndDeletesItsLog() throws Exception {
    final Field singletonField = PlaywrightInstaller.class.getDeclaredField("INSTALLATION");
    singletonField.setAccessible(true);
    final Object installation = singletonField.get(null);
    final Field installedField = PlaywrightInstaller.Installation.class.getDeclaredField("installed");
    installedField.setAccessible(true);
    final boolean previouslyInstalled = installedField.getBoolean(installation);
    final Driver driver = mock(Driver.class);
    final ProcessBuilder builder = mock(ProcessBuilder.class);
    final Process process = mock(Process.class);
    final Path path = mock(Path.class);
    final File file = mock(File.class);
    final List<String> command = new ArrayList<>(List.of("node", "cli.js"));
    when(driver.createProcessBuilder()).thenReturn(builder);
    when(builder.command()).thenReturn(command);
    when(builder.start()).thenReturn(process);
    when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    when(path.toFile()).thenReturn(file);
    final Map<String, String> environment = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    try (final MockedStatic<Driver> drivers = mockStatic(Driver.class); final MockedStatic<Files> files = mockStatic(Files.class)) {
      drivers.when(() -> Driver.ensureDriverInstalled(environment, false)).thenReturn(driver);
      files.when(() -> Files.createTempFile("mcav-playwright-install", ".log")).thenReturn(path);
      installedField.setBoolean(installation, false);
      assertDoesNotThrow(PlaywrightInstaller::ensureInstalled);
      final List<String> expected = List.of("node", "cli.js", "install", "chromium-headless-shell");
      assertEquals(expected, command);
      verify(builder).start();
      verify(file).deleteOnExit();
      verify(file).delete();
    } finally {
      installedField.setBoolean(installation, previouslyInstalled);
    }
  }
}

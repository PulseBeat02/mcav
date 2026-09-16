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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.chrome.ChromeDriverService;

/**
 * Tests {@link ChromeDriverProvider} and {@link BrowserModule}, which prepares and shuts down its driver service.
 */
final class ChromeDriverProviderTest {

  @AfterEach
  void shutDown() {
    ChromeDriverProvider.shutdown();
  }

  @Test
  void sharesOneRunningService() {
    ExternalBrowsers.assumeChrome();
    final ChromeDriverService first = ChromeDriverProvider.getService();
    final ChromeDriverService second = ChromeDriverProvider.getService();
    final boolean running = first.isRunning();
    assertSame(first, second);
    assertTrue(running);
  }

  @Test
  void replacesAServiceThatStopped() {
    ExternalBrowsers.assumeChrome();
    final ChromeDriverService first = ChromeDriverProvider.getService();
    first.stop();
    final ChromeDriverService second = ChromeDriverProvider.getService();
    final boolean running = second.isRunning();
    assertNotSame(first, second);
    assertTrue(running);
  }

  @Test
  void stopsTheServiceOnShutdown() {
    ExternalBrowsers.assumeChrome();
    final ChromeDriverService service = ChromeDriverProvider.getService();
    ChromeDriverProvider.shutdown();
    final boolean running = service.isRunning();
    assertFalse(running);
    ChromeDriverProvider.shutdown();
    final ChromeDriverService next = ChromeDriverProvider.getService();
    assertNotSame(service, next);
  }

  @Test
  void shutsDownAServiceThatAlreadyStopped() {
    ExternalBrowsers.assumeChrome();
    final ChromeDriverService service = ChromeDriverProvider.getService();
    service.stop();
    ChromeDriverProvider.shutdown();
    final boolean running = service.isRunning();
    assertFalse(running);
  }

  @Test
  void reportsServicesThatFailToStart() throws IOException {
    final ChromeDriverService service = mock(ChromeDriverService.class);
    final IOException failure = new IOException("port is taken");
    doThrow(failure).when(service).start();
    final PlayerException exception = assertThrows(PlayerException.class, () -> ChromeDriverProvider.startService(service));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Failed to start ChromeDriver: port is taken", message);
    assertSame(failure, cause);
    verify(service).start();
  }

  @Test
  void preparesAndReleasesTheServiceAsAModule() {
    ExternalBrowsers.assumeChrome();
    final BrowserModule module = new BrowserModule();
    final String name = module.getModuleName();
    assertEquals("browser", name);
    module.start();
    final ChromeDriverService service = ChromeDriverProvider.getService();
    final boolean runningBeforeStop = service.isRunning();
    module.stop();
    final boolean runningAfterStop = service.isRunning();
    assertTrue(runningBeforeStop);
    assertFalse(runningAfterStop);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(ChromeDriverProvider.class);
  }
}

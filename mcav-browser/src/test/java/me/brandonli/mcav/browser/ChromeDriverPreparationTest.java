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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.slf4j.Logger;

/** Checks cold preparation and service resource ownership without launching a browser. */
final class ChromeDriverPreparationTest {

  private Field prepared;
  private boolean wasPrepared;
  private java.util.logging.Logger seleniumLogger;
  private Level previousLevel;

  @BeforeEach
  void isolatePreparationState() throws ReflectiveOperationException {
    ChromeDriverProvider.shutdown();
    this.prepared = ChromeDriverProvider.class.getDeclaredField("prepared");
    this.prepared.setAccessible(true);
    this.wasPrepared = this.prepared.getBoolean(null);
    this.prepared.setBoolean(null, false);
    this.seleniumLogger = java.util.logging.Logger.getLogger("org.openqa.selenium");
    this.previousLevel = this.seleniumLogger.getLevel();
    this.seleniumLogger.setLevel(Level.FINEST);
  }

  @AfterEach
  void restorePreparationState() throws ReflectiveOperationException {
    try {
      ChromeDriverProvider.shutdown();
    } finally {
      this.prepared.setBoolean(null, this.wasPrepared);
      this.seleniumLogger.setLevel(this.previousLevel);
    }
  }

  @Test
  void coldServiceLookupPreparesTheDriverAndClosesTheOwnedService() throws Exception {
    final WebDriverManager manager = mock(WebDriverManager.class);
    final ChromeDriverService service = mock(ChromeDriverService.class);
    when(service.isRunning()).thenReturn(true);
    try (
      final MockedStatic<WebDriverManager> managers = mockStatic(WebDriverManager.class);
      final MockedConstruction<ChromeDriverService.Builder> builders = mockConstruction(ChromeDriverService.Builder.class, (builder, _) ->
        when(builder.build()).thenReturn(service)
      )
    ) {
      managers.when(WebDriverManager::chromedriver).thenReturn(manager);
      final ChromeDriverService obtained = ChromeDriverProvider.getService();
      final List<ChromeDriverService.Builder> constructed = builders.constructed();
      assertSame(service, obtained);
      assertEquals(1, constructed.size());
      verify(manager).setup();
      verify(service).start();
      final Level level = this.seleniumLogger.getLevel();
      assertEquals(Level.WARNING, level);
      ChromeDriverProvider.shutdown();
      verify(service).stop();
      verify(service).close();
    }
  }

  @Test
  void reportsElapsedPreparationTimeAndOnlyPreparesOnce() {
    final WebDriverManager manager = mock(WebDriverManager.class);
    final Logger logger = mock(Logger.class);
    try (final MockedStatic<WebDriverManager> managers = mockStatic(WebDriverManager.class)) {
      managers.when(WebDriverManager::chromedriver).thenReturn(manager);
      final long before = System.currentTimeMillis();
      ChromeDriverProvider.prepare(logger);
      final long after = System.currentTimeMillis();
      ChromeDriverProvider.prepare(logger);
      verify(manager).setup();
      final ArgumentCaptor<Long> timing = ArgumentCaptor.forClass(Long.class);
      verify(logger).info(eq("ChromeDriver ready in {} ms"), timing.capture());
      final long elapsed = timing.getValue();
      final long total = after - before;
      assertTrue(elapsed >= 0 && elapsed <= total, "reported duration must fit within the observed call duration");
    }
  }
}

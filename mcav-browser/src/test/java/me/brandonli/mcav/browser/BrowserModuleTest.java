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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BrowserModule} with the preparation of ChromeDriver replaced. The real preparation is tested by
 * {@link ChromeDriverProviderTest}.
 */
final class BrowserModuleTest {

  @Test
  void startsEvenWhenChromeDriverCannotBePrepared() {
    final AtomicInteger attempts = new AtomicInteger();
    final BrowserModule module = new BrowserModule(() -> {
      attempts.incrementAndGet();
      throw new PlayerException("ChromeDriver could not be resolved and no driver for win64 is cached");
    });
    module.start();
    module.start();
    final int count = attempts.get();
    assertEquals(2, count);
  }

  @Test
  void passesOnFailuresThatAreNotAboutTheDriver() {
    final IllegalStateException failure = new IllegalStateException("bug");
    final BrowserModule module = new BrowserModule(() -> {
      throw failure;
    });
    final IllegalStateException exception = assertThrows(IllegalStateException.class, module::start);
    assertSame(failure, exception);
  }

  @Test
  void isNamedBrowser() {
    final BrowserModule module = new BrowserModule(() -> {});
    final String name = module.getModuleName();
    assertEquals("browser", name);
  }
}

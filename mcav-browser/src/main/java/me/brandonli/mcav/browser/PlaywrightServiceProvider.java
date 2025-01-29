/*
 * This file is part of mcav, a media playback library for Minecraft
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

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.impl.driver.jar.DriverJar;

final class PlaywrightServiceProvider {

  private static final Playwright PLAYWRIGHT;

  static {
    final Thread thread = Thread.currentThread();
    final ClassLoader classLoader = thread.getContextClassLoader();
    final Class<?> clazz = DriverJar.class;
    final ClassLoader driverClassLoader = clazz.getClassLoader();
    thread.setContextClassLoader(driverClassLoader);
    PLAYWRIGHT = Playwright.create();
    thread.setContextClassLoader(classLoader);
  }

  private PlaywrightServiceProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static void init() {
    // init
  }

  static Playwright getService() {
    return PLAYWRIGHT;
  }

  static void shutdown() {
    if (PLAYWRIGHT != null) {
      PLAYWRIGHT.close();
    }
  }
}

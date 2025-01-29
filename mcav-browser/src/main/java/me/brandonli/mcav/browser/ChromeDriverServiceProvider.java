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

import io.github.bonigarcia.wdm.WebDriverManager;
import java.nio.file.Path;
import me.brandonli.mcav.utils.IOUtils;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;
import org.slf4j.bridge.SLF4JBridgeHandler;

final class ChromeDriverServiceProvider {

  private static final ChromeDriverService SERVICE;

  static {
    final Path path = IOUtils.getCachedFolder();
    final Path driver = path.resolve("driver");
    final String raw = driver.toString();
    final WebDriverManager manager = WebDriverManager.chromedriver();
    manager.clearDriverCache();
    manager.clearResolutionCache();
    manager.cachePath(raw);
    manager.setup();
    SERVICE = new ChromeDriverService.Builder().withLogLevel(ChromiumDriverLogLevel.WARNING).usingAnyFreePort().build();
    SLF4JBridgeHandler.install();
  }

  private ChromeDriverServiceProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static void init() {
    // init
  }

  static ChromeDriverService getService() {
    return SERVICE;
  }

  static void shutdown() {
    SERVICE.close();
    SERVICE.stop();
  }
}

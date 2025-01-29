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
package me.brandonli.mcav.media.player.driver;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.nio.file.Path;
import me.brandonli.mcav.utils.IOUtils;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;

/**
 * A provider class for managing the ChromeDriver service setup and initialization.
 * This class is responsible for configuring, creating, and providing access to a
 * singleton instance of the ChromeDriverService.
 * <p>
 * The service is initialized statically using {@link WebDriverManager} and is configured
 * to use any available free port. The cache location for ChromeDriver binaries is determined
 * by invoking the {@code IOUtils.getCachedFolder()} method.
 * <p>
 * The class is final and cannot be extended. It provides static methods for initializing
 * the service and obtaining the configured instance.
 */
public final class ChromeDriverServiceProvider {

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
    SERVICE = new ChromeDriverService.Builder().withLogLevel(ChromiumDriverLogLevel.DEBUG).usingAnyFreePort().build();
  }

  /**
   * Initializes the ChromeDriverService used for managing ChromeDriver processes.
   * This method ensures that the service is properly configured and ready for use.
   * It is designed to handle the setup process, including defining the cache
   * location for ChromeDriver binaries and specifying other necessary parameters.
   * <p>
   * This method is intended to be called before any operation requiring the
   * ChromeDriverService, to ensure that the service is properly initialized.
   */
  public static void init() {
    // init
  }

  /**
   * Retrieves the singleton instance of the configured {@link ChromeDriverService}.
   * This service is used to manage ChromeDriver processes
   * and is initialized with predefined configurations, such as logging level and port allocation.
   *
   * @return the singleton instance of {@link ChromeDriverService}.
   */
  public static ChromeDriverService getService() {
    return SERVICE;
  }
}

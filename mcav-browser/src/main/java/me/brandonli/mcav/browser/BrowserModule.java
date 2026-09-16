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
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.module.MCAVModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prepares the browser backends. Install it with {@code MCAV.api().install(BrowserModule.class)} before creating
 * a {@link BrowserPlayer}. Starting the module downloads ChromeDriver for the Selenium backend if it is missing; if
 * that fails, for example without internet access and without a cached driver, a warning is logged and the Selenium
 * backend tries again when a Selenium player starts. Playwright downloads its headless Chromium the first time a
 * Playwright player starts, so the Playwright backend does not depend on ChromeDriver.
 */
public final class BrowserModule implements MCAVModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(BrowserModule.class);

  private final Runnable driverPreparer;

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public BrowserModule() {
    this(ChromeDriverProvider::prepare);
  }

  /**
   * Constructs a module that prepares ChromeDriver with the given step, so tests can simulate a failed download.
   *
   * @param driverPreparer resolves ChromeDriver, throwing a {@link PlayerException} if it cannot
   */
  @VisibleForTesting
  BrowserModule(final Runnable driverPreparer) {
    this.driverPreparer = driverPreparer;
  }

  /**
   * Downloads ChromeDriver for the Selenium backend if it is missing. A failed download is logged as a warning and
   * tried again when a Selenium player starts, so the module always starts.
   */
  @Override
  public void start() {
    try {
      this.driverPreparer.run();
    } catch (final PlayerException exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("ChromeDriver is not available yet, Selenium players will try again when they start: {}", reason);
    }
  }

  /**
   * Stops the ChromeDriver process shared by the Selenium players, if it is running.
   */
  @Override
  public void stop() {
    ChromeDriverProvider.shutdown();
  }

  /**
   * Gets the name of the module.
   *
   * @return {@code browser}
   */
  @Override
  public String getModuleName() {
    return "browser";
  }
}

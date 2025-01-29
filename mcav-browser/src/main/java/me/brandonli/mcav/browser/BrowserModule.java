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

import me.brandonli.mcav.module.MCAVModule;

/**
 * The entry point for the browser module. Contains start and shutdown methods for browser services.
 */
public final class BrowserModule implements MCAVModule {

  BrowserModule() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    ChromeDriverServiceProvider.init();
    PlaywrightServiceProvider.init();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    PlaywrightServiceProvider.shutdown();
    ChromeDriverServiceProvider.shutdown();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getModuleName() {
    return "browser";
  }
}

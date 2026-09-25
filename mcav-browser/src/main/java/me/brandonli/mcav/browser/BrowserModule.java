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

import me.brandonli.mcav.module.MCAVModule;

/**
 * The browser module. Install it with {@code MCAV.api().install(BrowserModule.class)} before creating a
 * {@link BrowserPlayer}.
 *
 * <p>Starting the module prepares nothing: the CEF build for this machine is downloaded when the first browser starts,
 * so servers that never show a web page never download it. Stopping the module ends the helper process of every
 * browser that is still running, so disabling a plugin leaves no browser process behind, and a later start of the
 * module can start browsers again.
 */
public final class BrowserModule implements MCAVModule {

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public BrowserModule() {}

  /**
   * Does nothing; browsers are installed and started on demand.
   */
  @Override
  public void start() {
    // the natives are installed by the first browser that starts
  }

  /**
   * Ends the helper process of every browser that is still running.
   */
  @Override
  public void stop() {
    HelperProcesses.closeAll();
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

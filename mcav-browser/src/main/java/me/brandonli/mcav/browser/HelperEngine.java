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

import java.util.List;
import org.cef.browser.McavOffscreenBrowser;

/**
 * The browser inside the helper process: CEF in production, a fake in tests of the helper.
 */
interface HelperEngine {
  /**
   * Starts the browser and opens the page of the configuration. Returns once the browser is being created; the events
   * report when it is ready.
   *
   * @param configuration the configuration of the helper
   * @param painter       receives the painted frames
   * @param events        receives what happens
   * @throws Exception if the browser cannot be started
   */
  void start(HelperConfiguration configuration, McavOffscreenBrowser.PaintListener painter, HelperEvents events) throws Exception;

  /**
   * Sends input to the page, in order.
   *
   * @param calls the DevTools calls of the input
   */
  void dispatch(List<DevToolsInput.DevToolsCall> calls);

  /**
   * Closes the browser and shuts the engine down, waiting a bounded time for it.
   */
  void stop();
}

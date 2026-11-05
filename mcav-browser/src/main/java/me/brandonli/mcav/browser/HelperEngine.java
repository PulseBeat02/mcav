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
import org.cef.browser.MCAVOffscreenBrowser;

interface HelperEngine {
  // returns once the browser is being created; the events report when it is ready
  void start(final HelperConfiguration configuration, final MCAVOffscreenBrowser.PaintListener painter, final HelperEvents events)
    throws Exception;

  void dispatch(final List<DevToolsInput.DevToolsCall> calls);

  void stop();
}

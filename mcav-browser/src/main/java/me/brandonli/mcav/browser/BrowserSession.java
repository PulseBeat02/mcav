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

import me.brandonli.mcav.media.image.ImageBuffer;

// A running browser as a CefBrowserPlayer sees it: it takes input, can send its picture again, and ends when closed.
// HelperSession is the browser of a helper process.
interface BrowserSession extends AutoCloseable {
  boolean sendMouse(MouseInput mouse);

  boolean sendKey(int action, String value);

  void requestFrame();

  /**
   * Ends the browser. Safe to call more than once.
   */
  @Override
  void close();

  interface Listener {
    void onFrame(ImageBuffer frame);

    // Receives sound of the page, on the thread that reads the helper, so the listener must not hold it up.
    void onAudio(byte[] samples);

    void onEnded(String reason, Throwable cause);
  }
}

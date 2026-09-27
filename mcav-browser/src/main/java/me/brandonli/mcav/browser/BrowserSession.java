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

/**
 * A running browser as a {@link CefBrowserPlayer} sees it: it takes input, can send its picture again, and ends when
 * closed. {@link HelperSession} is the browser of a helper process.
 */
interface BrowserSession extends AutoCloseable {
  /**
   * Queues mouse input.
   *
   * @param mouse the input
   * @return false if the queue was full and the input was dropped
   */
  boolean sendMouse(MouseInput mouse);

  /**
   * Queues keyboard input.
   *
   * @param action {@link HelperProtocol#KEY_PRESS} or {@link HelperProtocol#KEY_TYPE}
   * @param value  the key name or the text
   * @return false if the queue was full and the input was dropped
   */
  boolean sendKey(int action, String value);

  /**
   * Hands the current picture of the page to the listener again.
   */
  void requestFrame();

  /**
   * Ends the browser. Safe to call more than once.
   */
  @Override
  void close();

  /**
   * Receives the frames of a session and its unexpected end.
   */
  interface Listener {
    /**
     * Receives a picture of the page, which the listener owns.
     *
     * @param frame the picture
     */
    void onFrame(ImageBuffer frame);

    /**
     * Receives sound of the page, on the thread that reads the helper, so the listener must not hold it up.
     *
     * @param samples 16-bit little-endian stereo samples at 48 kHz, whole frames, which the listener owns
     */
    void onAudio(byte[] samples);

    /**
     * Receives that the browser ended without being asked to.
     *
     * @param reason why it ended
     * @param cause  the failure
     */
    void onEnded(String reason, Throwable cause);
  }
}

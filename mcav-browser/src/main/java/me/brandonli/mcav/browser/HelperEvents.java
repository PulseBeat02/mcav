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

/**
 * What the browser engine of the helper process reports to the helper, which passes it on to the server.
 */
interface HelperEvents {
  /**
   * The browser was created and shows the page.
   *
   * @param engineVersion the version of CEF and Chromium
   */
  void onReady(String engineVersion);

  /**
   * The page started or stopped loading.
   *
   * @param loading true while the page loads
   */
  void onLoading(boolean loading);

  /**
   * The page failed to load.
   *
   * @param code the network error code of Chromium
   * @param text the description of the error
   * @param url  the address that failed
   */
  void onLoadError(int code, String text, String url);

  /**
   * The browser refused or changed something the page asked for, such as a popup, a download or a dialog.
   *
   * @param text what happened
   */
  void onNotice(String text);

  /**
   * The browser cannot go on, for example because its renderer crashed; the helper exits.
   *
   * @param text what happened
   */
  void onFailure(String text);

  /**
   * The page played sound.
   *
   * @param samples 16-bit little-endian stereo samples at 48 kHz, whole frames and at most
   *                {@link HelperProtocol#MAX_AUDIO_BYTES} bytes, which the receiver owns
   */
  void onAudio(byte[] samples);
}

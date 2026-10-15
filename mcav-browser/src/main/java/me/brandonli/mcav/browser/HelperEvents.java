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

// What the browser engine of the helper process reports to the helper, which passes it on to the server.
interface HelperEvents {
  void onReady(String engineVersion);

  // The browser started to load a new page in its main frame. Its sound waits for the next press of a mouse button or a
  // key, as a press on the page before does not count for it.
  void onPageStart();

  void onLoading(boolean loading);

  void onLoadError(int code, String text, String url);

  // The browser refused or changed something the page asked for, such as a popup, a download or a dialog.
  void onNotice(String text);

  // The browser cannot go on, for example because its renderer crashed; the helper exits.
  void onFailure(String text);

  void onAudio(byte[] samples);
}

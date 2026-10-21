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

interface HelperEvents {
  void onReady(final String engineVersion);

  // a press on the page before does not count for the sound of the new page
  void onPageStart();

  void onLoading(final boolean loading);

  void onLoadError(final int code, final String text, final String url);

  void onNotice(final String text);

  // the helper exits
  void onFailure(final String text);

  void onAudio(final byte[] samples);
}

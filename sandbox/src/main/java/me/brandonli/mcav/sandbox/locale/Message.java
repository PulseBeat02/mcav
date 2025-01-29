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
package me.brandonli.mcav.sandbox.locale;

import static me.brandonli.mcav.sandbox.locale.LocaleTools.direct;

public interface Message extends LocaleTools {
  NullComponent<Sender> VIDEO_RELEASED_START = direct("mcav.command.video.release.start");
  NullComponent<Sender> VIDEO_LOADING_ERROR = direct("mcav.command.video.load.error");
  NullComponent<Sender> VIDEO_RELEASED = direct("mcav.command.video.release");
  NullComponent<Sender> VIDEO_PAUSED = direct("mcav.command.video.pause");
  NullComponent<Sender> VIDEO_STARTED = direct("mcav.command.video.start");
  NullComponent<Sender> VIDEO_LOADING = direct("mcav.command.video.info");
  NullComponent<Sender> BROWSER_RELEASED = direct("mcav.command.browser.release");
  NullComponent<Sender> BROWSER_STARTED = direct("mcav.command.browser.start");
  NullComponent<Sender> MRL_ERROR = direct("mcav.command.mrl.error");
  NullComponent<Sender> URL_ERROR = direct("mcav.command.url.error");
  NullComponent<Sender> DIMENSION_ERROR = direct("mcav.command.dimension.error");
  UniComponent<Sender, String> SEND_DUMP = direct("mcav.command.dump.result", null);
  NullComponent<Sender> LOAD_DUMP = direct("mcav.command.dump.load");
}

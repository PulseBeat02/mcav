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
  UniComponent<Sender, String> AUDIO_HTTP = direct("mcav.command.audio.http", null);
  UniComponent<Sender, String> AUDIO_DISCORD = direct("mcav.command.audio.discord", null);
  NullComponent<Sender> RESUME_PLAYER = direct("mcav.command.video.resume");
  NullComponent<Sender> UNSUPPORTED_AUDIO = direct("mcav.command.audio.unsupported");
  NullComponent<Sender> UNSUPPORTED_PLAYER = direct("mcav.command.player.unsupported");
  NullComponent<Sender> RELEASE_PLAYER_START = direct("mcav.command.video.release.start");
  NullComponent<Sender> PLAYER_ERROR = direct("mcav.command.video.load.error");
  NullComponent<Sender> RELEASE_PLAYER = direct("mcav.command.video.release");
  NullComponent<Sender> PAUSE_PLAYER = direct("mcav.command.video.pause");
  NullComponent<Sender> START_VIDEO = direct("mcav.command.video.start");
  NullComponent<Sender> LOAD_VIDEO = direct("mcav.command.video.info");
  NullComponent<Sender> RELEASE_BROWSER = direct("mcav.command.browser.release");
  NullComponent<Sender> START_BROWSER = direct("mcav.command.browser.start");
  NullComponent<Sender> UNSUPPORTED_MRL = direct("mcav.command.mrl.error");
  NullComponent<Sender> UNSUPPORTED_URL = direct("mcav.command.url.error");
  NullComponent<Sender> UNSUPPORTED_DIMENSION = direct("mcav.command.dimension.error");
  UniComponent<Sender, String> SEND_DUMP = direct("mcav.command.dump.result", null);
  NullComponent<Sender> CREATE_DUMP = direct("mcav.command.dump.load");
}

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
package me.brandonli.mcav.plugin.command.video;

import java.io.Serial;
import me.brandonli.mcav.plugin.PluginException;

/**
 * Thrown when a video command refuses a stream that yt-dlp reported for a web page, because the scheme of the stream
 * is not {@code http} or {@code https}.
 *
 * <p>This is a {@link PluginException}: the page, not the server, chose an address that FFmpeg or VLC would open as a
 * file of the server or a local service. It has a type of its own, so {@link AbstractVideoCommand} can tell it apart
 * from unexpected failures and send its message to the sender instead of logging a stack trace. The message names the
 * page and the scheme, never the address of the stream.
 *
 * @since 1.0.0
 */
public final class StreamRefusedException extends PluginException {

  @Serial
  private static final long serialVersionUID = 8414103171094372015L;

  StreamRefusedException(final String message) {
    super(message);
  }

  StreamRefusedException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

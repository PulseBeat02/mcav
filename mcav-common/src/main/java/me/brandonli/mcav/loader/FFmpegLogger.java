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
package me.brandonli.mcav.loader;

import static org.bytedeco.ffmpeg.global.avutil.*;

import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FFmpegLogger extends LogCallback {

  private static final Logger INTERNAL_LOGGER = LoggerFactory.getLogger(FFmpegLogger.class);

  /**
   * {@inheritDoc}
   */
  @Override
  public void call(final int level, final BytePointer msg) {
    final String line = msg.getString();
    switch (level) {
      case AV_LOG_PANIC:
      case AV_LOG_FATAL:
      case AV_LOG_ERROR:
        INTERNAL_LOGGER.error(line);
        break;
      case AV_LOG_WARNING:
        INTERNAL_LOGGER.warn(line);
        break;
      case AV_LOG_INFO:
        INTERNAL_LOGGER.info(line);
        break;
      case AV_LOG_VERBOSE:
      case AV_LOG_DEBUG:
      case AV_LOG_TRACE:
        INTERNAL_LOGGER.debug(line);
        break;
      default:
        assert false;
    }
  }
}

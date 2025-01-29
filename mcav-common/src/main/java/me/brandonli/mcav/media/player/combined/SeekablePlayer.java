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
package me.brandonli.mcav.media.player.combined;

/**
 * The {@code SeekablePlayer} interface provides functionality for seeking media playback
 * to a specific point in time. Implementations of this interface allow users to control
 * and navigate the media timeline by specifying a timestamp in milliseconds.
 *
 * This interface is intended to be implemented by media players that support seeking
 * capabilities, enabling precise control over playback position.
 *
 * The {@link #seek(long)} method plays a key role in enabling seek operations, returning
 * a boolean indicating the success of the operation, while also ensuring proper exception
 * handling for invalid seek requests or errors during the process.
 */
public interface SeekablePlayer {
  /**
   * Seeks the media to the specified timestamp in milliseconds. This method is used
   * to reposition the playback to a specific point in time within the media.
   *
   * @param time the position in milliseconds to seek to. This value must be a non-negative
   *             long and within the valid duration of the media.
   * @return true if the seek operation was successful; false otherwise.
   * @throws Exception if an error occurs during the seek operation, such as when
   *                   the media is not in a seekable state or the specified time
   *                   is invalid.
   */
  boolean seek(final long time) throws Exception;
}

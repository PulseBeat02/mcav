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

import me.brandonli.mcav.media.player.ReleasablePlayer;

/**
 * The {@code ControllablePlayer} interface defines control mechanisms for media players
 * that support seeking, pausing, and resuming playback operations. It extends the
 * {@link ReleasablePlayer} interface to integrate release functionality.
 * <p>
 * Implementations of this interface allow greater flexibility in playback control,
 * enabling users to interact with the media at a precise level.
 * <p>
 * The key control methods provided include:
 * - {@code seek(long time)}: Allows seeking to a specific point in the media timeline.
 * - {@code pause()}: Temporarily halts playback while maintaining the current position.
 * - {@code resume()}: Resumes playback from the paused position.
 * Each method may throw an exception if the operation fails or is unsupported.
 */
public interface ControllablePlayer extends ReleasablePlayer {
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

  /**
   * Pauses media playback if it is currently playing.
   *
   * @return true if the media playback is successfully paused; false otherwise.
   * @throws Exception if an error occurs during the pause operation.
   */
  boolean pause() throws Exception;

  /**
   * Resumes the playback of the media from the current timestamp. If the player has been
   * paused or stopped, it will reinitialize necessary components and start processing
   * frames for audio and/or video output.
   * <p>
   * The method ensures thread safety by synchronizing access to shared resources and
   * handles the starting of frame processing in either single or multiplexer mode
   * depending on the presence of audio data.
   *
   * @return {@code true} if the playback was successfully resumed, {@code false} otherwise.
   * @throws Exception if any error occurs during playback initialization or frame processing.
   */
  boolean resume() throws Exception;
}

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
package me.brandonli.mcav.sandbox.utils;

import com.google.errorprone.annotations.Immutable;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;

/**
 * The {@code <playerType>} argument of the {@code /mcav video} commands: which backend decodes the media.
 *
 * <p>The backend only changes how the media is read; the display and the audio output are chosen by the other
 * arguments. When unsure, use {@link #FFMPEG}.
 */
public enum PlayerArgument {
  /**
   * Decodes with VLC, which handles the widest range of formats and network streams. VLC must be installed on the
   * server or installable by the plugin. A server without VLC downloads it in the background on its first start;
   * until it is ready, the command answers that VLC is still being prepared, and when it is not available at all,
   * that VLC is not supported, and nothing plays.
   */
  VLC(VideoPlayer::vlc),

  /**
   * Decodes with the FFmpeg libraries bundled with the plugin, so nothing has to be installed. This is the
   * recommended choice for files, direct URLs, websites resolved through yt-dlp, and raw FFmpeg inputs written as
   * {@code format||input}.
   */
  FFMPEG(VideoPlayer::ffmpeg),

  /**
   * Captures from a camera or capture card attached to the server. Use it with a device number, such as {@code 0}
   * for the first device, as the media argument.
   */
  DEVICE(VideoPlayer::device);

  /**
   * Creates the player of a constant. The factories capture nothing, so every constant stays immutable.
   */
  @Immutable
  @FunctionalInterface
  private interface PlayerFactory {
    /**
     * Creates a new, not yet started player.
     *
     * @return the player
     */
    VideoPlayerMultiplexer create();
  }

  private final PlayerFactory factory;

  PlayerArgument(final PlayerFactory factory) {
    this.factory = factory;
  }

  /**
   * Creates a new, not yet started player with this backend. Every video gets its own player.
   *
   * @return the player
   */
  public VideoPlayerMultiplexer createPlayer() {
    return this.factory.create();
  }
}

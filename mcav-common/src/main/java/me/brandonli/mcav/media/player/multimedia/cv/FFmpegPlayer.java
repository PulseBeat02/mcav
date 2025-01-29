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
package me.brandonli.mcav.media.player.multimedia.cv;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Represents a video player that uses FFmpeg for frame grabbing.
 */
public class FFmpegPlayer extends AbstractVideoPlayerCV {

  /**
   * Constructs a new FFmpegPlayer instance.
   */
  public FFmpegPlayer() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FrameGrabber getFrameGrabber(final String uri) {
    return new FFmpegFrameGrabber(uri);
  }
}

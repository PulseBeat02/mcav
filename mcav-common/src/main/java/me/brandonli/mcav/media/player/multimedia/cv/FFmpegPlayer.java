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

import com.google.common.base.Preconditions;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

/**
 * A player that decodes media with the bundled FFmpeg, which is always available and handles files, HTTP
 * streams, RTSP cameras, and raw device input. Create instances with
 * {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#ffmpeg()}.
 */
public class FFmpegPlayer extends AbstractVideoPlayerCV {

  /**
   * Constructs a new FFmpeg player.
   */
  public FFmpegPlayer() {
    // configured by the base class
  }

  /**
   * Creates an FFmpeg grabber for a resource, which the player configures and starts.
   *
   * @param resource the resource to decode, such as a file path, a URL, or a device path with an FFmpeg format
   * @return a new, unstarted FFmpeg grabber
   * @throws NullPointerException if the resource is null
   */
  @Override
  protected FrameGrabber createFrameGrabber(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    return new FFmpegFrameGrabber(resource);
  }
}

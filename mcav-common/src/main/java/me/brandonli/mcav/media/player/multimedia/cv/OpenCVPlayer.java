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
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

/**
 * A player that decodes media with the video capture module of OpenCV, which supports files and cameras but no
 * audio. Prefer {@link FFmpegPlayer} unless a capture backend of OpenCV is needed. Create instances with
 * {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#opencv()}.
 */
public final class OpenCVPlayer extends AbstractVideoPlayerCV {

  /**
   * Constructs a new OpenCV player.
   */
  public OpenCVPlayer() {
    // configured by the base class
  }

  /**
   * Creates an OpenCV grabber for a resource, which the player configures and starts.
   *
   * @param resource the resource to decode, such as a file path or a URL
   * @return a new, unstarted OpenCV grabber
   * @throws NullPointerException if the resource is null
   */
  @Override
  protected FrameGrabber createFrameGrabber(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    return new OpenCVFrameGrabber(resource);
  }
}

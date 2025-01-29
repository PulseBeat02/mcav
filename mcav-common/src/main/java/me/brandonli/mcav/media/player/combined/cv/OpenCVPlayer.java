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
package me.brandonli.mcav.media.player.combined.cv;

import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

/**
 * The OpenCVPlayer class is a concrete implementation of the AbstractVideoPlayerCV
 * for video playback using OpenCV as the backend. It uses the OpenCVFrameGrabber
 * for frame grabbing and processing.
 * <p>
 * OpenCVPlayer provides an implementation of the {@linkplain AbstractVideoPlayerCV#getFrameGrabber(String)}
 * method to return an OpenCV-specific frame grabber instance for the given media URI.
 * <p>
 * This class integrates with the AbstractVideoPlayerCV framework for handling
 * video playback, audio-video synchronization, and processing pipelines.
 */
public final class OpenCVPlayer extends AbstractVideoPlayerCV {

  /**
   * {@inheritDoc}
   */
  @Override
  public FrameGrabber getFrameGrabber(final String uri) {
    return new OpenCVFrameGrabber(uri);
  }
}

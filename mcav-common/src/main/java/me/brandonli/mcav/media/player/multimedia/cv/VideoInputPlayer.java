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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.VideoInputFrameGrabber;

/**
 * Represents a video player that uses a device input (like a webcam) for frame grabbing.
 */
public class VideoInputPlayer extends AbstractVideoPlayerCV {

  /**
   * Constructs a new VideoInputPlayer instance.
   */
  public VideoInputPlayer() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FrameGrabber getFrameGrabber(final String uri) {
    final Integer device = Ints.tryParse(uri);
    Preconditions.checkNotNull(device);
    requireNonNull(device); // checker
    return new VideoInputFrameGrabber(device);
  }
}

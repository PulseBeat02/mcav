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
import com.google.common.primitives.Ints;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

/**
 * A player that captures video from a camera or capture card by device index, such as {@code 0} for the default
 * webcam. Devices are opened through OpenCV, which uses the native capture API of every operating system. Create
 * instances with {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#device()} and start them with a
 * {@link me.brandonli.mcav.media.source.device.DeviceSource}.
 */
public class VideoInputPlayer extends AbstractVideoPlayerCV {

  /**
   * Constructs a new capture device player.
   */
  public VideoInputPlayer() {
    // configured by the base class
  }

  /**
   * Creates an OpenCV grabber for the capture device with the given index, which the player configures and starts.
   *
   * @param resource the index of the device as text, such as {@code "0"} for the default webcam
   * @return a new, unstarted OpenCV grabber of the device
   * @throws NullPointerException     if the resource is null
   * @throws IllegalArgumentException if the resource is not a non-negative number
   */
  @Override
  protected FrameGrabber createFrameGrabber(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    final Integer parsedIndex = Ints.tryParse(resource);
    if (parsedIndex == null || parsedIndex < 0) {
      throw new IllegalArgumentException("Device index must be a non-negative number but was " + resource);
    }

    final int deviceIndex = parsedIndex;
    return new OpenCVFrameGrabber(deviceIndex);
  }
}

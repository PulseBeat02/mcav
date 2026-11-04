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
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.source.device.DeviceSource;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A player that captures video from a camera or capture card by device index, such as {@code 0} for the default webcam.
 * Devices are opened through OpenCV, which uses the native capture API of every operating system. Create instances with
 * {@link VideoPlayer#device()} and start them with a {@link DeviceSource}.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class VideoInputPlayer extends AbstractVideoPlayerCv {

  /**
   * Creates a capture device player.
   *
   * @since 1.0.0
   */
  public VideoInputPlayer() {}

  /**
   * Creates an OpenCV grabber for the capture device with the given index, which the player configures and starts.
   *
   * @param resource the index of the device as text, such as {@code "0"} for the default webcam
   * @return a new, unstarted OpenCV grabber of the device
   * @throws NullPointerException     if the resource is null
   * @throws IllegalArgumentException if the resource is not a number or is negative
   * @since 1.0.0
   */
  @Override
  protected FrameGrabber createFrameGrabber(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");

    final @Nullable Integer parsedIndex = Ints.tryParse(resource);
    if (parsedIndex == null || parsedIndex < 0) {
      final String message = "Device index %s must be a number and must not be negative!".formatted(resource);
      throw new IllegalArgumentException(message);
    }

    final int deviceIndex = parsedIndex;
    return new OpenCVFrameGrabber(deviceIndex);
  }
}

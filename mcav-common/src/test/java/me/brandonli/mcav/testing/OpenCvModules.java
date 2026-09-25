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
package me.brandonli.mcav.testing;

import com.google.common.base.Preconditions;
import org.bytedeco.javacpp.Loader;

/**
 * Checks what the OpenCV natives bundled with JavaCV can do on this machine, so tests of features that need a missing
 * part skip themselves.
 *
 * <p>The object detection and GUI natives link GTK 2 on Linux, which most servers lack. The video reader does not need
 * GTK, but the Linux build of OpenCV has no video file backend at all: it only captures from cameras through V4L2.
 */
public final class OpenCvModules {

  private OpenCvModules() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether the natives of a JavaCPP preset can be loaded.
   *
   * @param preset the preset class, such as {@code org.bytedeco.opencv.presets.opencv_objdetect}
   * @return true if the natives were loaded
   */
  public static boolean canLoad(final Class<?> preset) {
    Preconditions.checkNotNull(preset, "Preset must not be null");
    try {
      Loader.load(preset);
      return true;
    } catch (final LinkageError error) {
      return false;
    }
  }
}

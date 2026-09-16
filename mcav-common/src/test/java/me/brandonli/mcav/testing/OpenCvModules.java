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
import java.util.List;
import java.util.stream.Stream;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;

/**
 * Checks what the OpenCV natives bundled with JavaCV can do on this machine, so tests of features that need a missing
 * part skip themselves.
 *
 * <p>The object detection and GUI natives link GTK 2 on Linux, which most servers lack. The video reader does not need
 * GTK, but the Linux build of OpenCV has no video file backend at all: it only captures from cameras through V4L2.
 */
public final class OpenCvModules {

  private static final String VIDEO_SECTION = "Video I/O:";
  private static final List<String> FILE_BACKENDS = List.of("FFMPEG:", "Media Foundation:", "AVFoundation:", "GStreamer:");

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

  /**
   * Checks whether the video module of OpenCV was built with a backend that reads video files, such as FFmpeg on
   * Windows or AVFoundation on macOS.
   *
   * @return true if OpenCV can open video files on this machine
   */
  public static boolean canDecodeVideoFiles() {
    final BytePointer information = opencv_core.getBuildInformation();
    final String text = information.getString();
    final int start = text.indexOf(VIDEO_SECTION);
    if (start < 0) {
      return false;
    }
    final int end = text.indexOf("\n\n", start);
    final int sectionEnd = end < 0 ? text.length() : end;
    final String section = text.substring(start, sectionEnd);
    return listsEnabledFileBackend(section);
  }

  private static boolean listsEnabledFileBackend(final String videoSection) {
    final Stream<String> lineStream = videoSection.lines();
    final List<String> lines = lineStream.toList();
    for (final String line : lines) {
      final String trimmed = line.strip();
      final boolean enabled = trimmed.contains("YES");
      for (final String backend : FILE_BACKENDS) {
        if (enabled && trimmed.startsWith(backend)) {
          return true;
        }
      }
    }
    return false;
  }
}

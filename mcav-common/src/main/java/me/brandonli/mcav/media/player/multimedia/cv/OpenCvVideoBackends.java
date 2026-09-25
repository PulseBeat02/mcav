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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.util.List;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the OpenCV build of this machine can read. OpenCV reads video files through a backend that has to be compiled
 * into it, and the builds differ: the Windows build brings FFmpeg and Media Foundation, the macOS build brings
 * AVFoundation, and the Linux build of the bundled JavaCPP presets brings none of them and only captures from V4L2
 * cameras.
 *
 * <p>Without a backend {@code VideoCapture} refuses to open a file and reports nothing but {@code false}, which used
 * to look exactly like a video that plays without ever showing a frame. The players ask here instead and read the file
 * with the FFmpeg reader that JavaCV bundles, which every platform has.
 */
final class OpenCvVideoBackends {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenCvVideoBackends.class);
  private static final String VIDEO_SECTION = "Video I/O:";
  private static final String SECTION_END = "\n\n";
  private static final String ENABLED = "YES";
  private static final List<String> FILE_BACKENDS = List.of("FFMPEG:", "Media Foundation:", "AVFoundation:", "GStreamer:");
  private static final Supplier<Boolean> FILE_BACKEND = Suppliers.memoize(OpenCvVideoBackends::detectFileBackend);

  private OpenCvVideoBackends() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Whether the OpenCV build of this machine reads video files. The answer is worked out once, because it cannot
   * change while the library runs.
   *
   * @return true if OpenCV has a backend that opens video files
   */
  static boolean canDecodeFiles() {
    return FILE_BACKEND.get();
  }

  private static boolean detectFileBackend() {
    final BytePointer information = opencv_core.getBuildInformation();
    final String text = information.getString();
    return decideFromBuildInformation(text);
  }

  /**
   * Decides from the build information whether files can be read, and says once where they are read instead.
   *
   * @param information the build information, as {@code cv::getBuildInformation} reports it
   * @return true if OpenCV has a backend that opens video files
   */
  @VisibleForTesting
  static boolean decideFromBuildInformation(final String information) {
    final boolean backend = listsEnabledFileBackend(information);
    if (!backend) {
      LOGGER.info(
        "The OpenCV build of this platform has no video file backend, so files are read with the FFmpeg reader of JavaCV instead"
      );
    }
    return backend;
  }

  /**
   * Reads the video section of the build information of OpenCV and looks for a backend that opens files.
   *
   * @param information the build information, as {@code cv::getBuildInformation} reports it
   * @return true if the section lists an enabled file backend
   */
  @VisibleForTesting
  static boolean listsEnabledFileBackend(final String information) {
    Preconditions.checkNotNull(information, "Build information must not be null");
    final int start = information.indexOf(VIDEO_SECTION);
    if (start < 0) {
      return false;
    }
    final int end = information.indexOf(SECTION_END, start);
    final int sectionEnd = end < 0 ? information.length() : end;
    final String section = information.substring(start, sectionEnd);
    final List<String> lines = section.lines().toList();
    for (final String line : lines) {
      final String trimmed = line.strip();
      final boolean enabled = trimmed.contains(ENABLED);
      final boolean names = namesFileBackend(trimmed);
      if (enabled && names) {
        return true;
      }
    }
    return false;
  }

  private static boolean namesFileBackend(final String line) {
    for (final String backend : FILE_BACKENDS) {
      final boolean names = line.startsWith(backend);
      if (names) {
        return true;
      }
    }
    return false;
  }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link OpenCvVideoBackends} against the build information of the platforms the library supports, so both
 * answers are covered wherever the tests run.
 */
final class OpenCvVideoBackendsTest {

  /**
   * The video section of the Linux build of the JavaCPP presets: cameras only.
   */
  private static final String LINUX =
    """
    General configuration for OpenCV 4.14.0 =====================================

    Video I/O:
        v4l/v4l2:                    YES (linux/videodev2.h)
        Orbbec:                      YES

    Parallel framework:             pthreads
    """;

  /**
   * The video section of the Windows build, which has FFmpeg and Media Foundation.
   */
  private static final String WINDOWS =
    """
    General configuration for OpenCV 4.14.0 =====================================

    Video I/O:
        DC1394:                      NO
        FFMPEG:                      YES (prebuilt binaries)
          avcodec:                   YES (58.134.100)
        DirectShow:                  YES
        Media Foundation:            YES

    Parallel framework:             Concurrency
    """;

  /**
   * The video section of the macOS build, which has AVFoundation.
   */
  private static final String MACOS =
    """
    General configuration for OpenCV 4.14.0 =====================================

    Video I/O:
        AVFoundation:                YES
        FFMPEG:                      NO
    """;

  @Test
  void findsNoFileBackendInTheLinuxBuild() {
    final boolean files = OpenCvVideoBackends.decideFromBuildInformation(LINUX);
    assertFalse(files, "the Linux build of the presets only captures from cameras");
  }

  @Test
  void findsTheFileBackendsOfTheOtherBuilds() {
    final boolean windows = OpenCvVideoBackends.decideFromBuildInformation(WINDOWS);
    final boolean macOs = OpenCvVideoBackends.decideFromBuildInformation(MACOS);
    assertTrue(windows);
    assertTrue(macOs);
  }

  @Test
  void readsOnlyTheVideoSection() {
    final String elsewhere =
      """
      General configuration for OpenCV 4.14.0 =====================================

      Media I/O:
          FFMPEG:                      YES

      Video I/O:
          v4l/v4l2:                    YES (linux/videodev2.h)
      """;
    final boolean files = OpenCvVideoBackends.listsEnabledFileBackend(elsewhere);
    assertFalse(files, "a backend named in another section says nothing about video files");
  }

  @Test
  void needsAnEnabledBackendAndAVideoSection() {
    final String disabled =
      """
      Video I/O:
          FFMPEG:                      NO
          GStreamer:                   NO
      """;
    final String truncated = "Video I/O:\n    FFMPEG:                      YES";
    final boolean noBackend = OpenCvVideoBackends.listsEnabledFileBackend(disabled);
    final boolean noSection = OpenCvVideoBackends.listsEnabledFileBackend("General configuration only");
    final boolean lastSection = OpenCvVideoBackends.listsEnabledFileBackend(truncated);
    assertFalse(noBackend, "a backend that was not compiled in cannot open a file");
    assertFalse(noSection);
    assertTrue(lastSection, "the section may be the last one, without a blank line after it");
  }

  @Test
  void answersTheSameEveryTime() {
    final boolean first = OpenCvVideoBackends.canDecodeFiles();
    final boolean second = OpenCvVideoBackends.canDecodeFiles();
    assertTrue(first == second, "the answer cannot change while the library runs");
  }

  @Test
  void picksTheGrabberThatMatchesTheBuild() throws FrameGrabber.Exception {
    try (final FrameGrabber reader = OpenCVPlayer.createFrameGrabber("file.mp4", true)) {
      assertInstanceOf(OpenCVFrameGrabber.class, reader);
    }
    try (final FrameGrabber fallback = OpenCVPlayer.createFrameGrabber("file.mp4", false)) {
      assertInstanceOf(FFmpegFrameGrabber.class, fallback, "a build without a file backend reads the file with FFmpeg");
    }
  }

  @Test
  void refusesNullBuildInformation() {
    assertThrows(NullPointerException.class, () -> OpenCvVideoBackends.listsEnabledFileBackend(null));
  }

  @Test
  void isNotInstantiable() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(OpenCvVideoBackends.class);
  }
}

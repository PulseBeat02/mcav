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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.global.opencv_core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// Tests OpenCvVideoBackends against the build information of the platforms the library supports, so both answers are
// covered wherever the tests run.
final class OpenCvVideoBackendsTest {

  private static final String VIDEO_FILE_NAME = "file.mp4";

  private static final String LINUX = """
  General configuration for OpenCV 4.14.0 =====================================

  Video I/O:
      v4l/v4l2:                    YES (linux/videodev2.h)
      Orbbec:                      YES

  Parallel framework:             pthreads
  """;

  private static final String WINDOWS = """
  General configuration for OpenCV 4.14.0 =====================================

  Video I/O:
      DC1394:                      NO
      FFMPEG:                      YES (prebuilt binaries)
        avcodec:                   YES (58.134.100)
      DirectShow:                  YES
      Media Foundation:            YES

  Parallel framework:             Concurrency
  """;

  private static final String MACOS = """
  General configuration for OpenCV 4.14.0 =====================================

  Video I/O:
      AVFoundation:                YES
      FFMPEG:                      NO
  """;

  @Test
  void shouldFindNoFileBackendInTheLinuxBuild() {
    final VideoFileReader reader = OpenCvVideoBackends.decideFromBuildInformation(LINUX);
    assertEquals(VideoFileReader.FFMPEG, reader, "the Linux build of the presets only captures from cameras");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldDetectTheFileReaderFromTheBuildInformationOfTheLoadedOpenCv() {
    final BytePointer information = opencv_core.getBuildInformation();
    final String text = information.getString();
    final VideoFileReader expected = OpenCvVideoBackends.decideFromBuildInformation(text);

    final VideoFileReader reader = OpenCvVideoBackends.detectFileReader();
    assertSame(expected, reader);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldSayWhereFilesAreReadWhenTheBuildHasNoFileBackend() {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      OpenCvVideoBackends.decideFromBuildInformation(LINUX);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);

    final boolean explained = log.contains("files are read with the FFmpeg reader of JavaCV instead");
    assertTrue(explained, log);
  }

  @Test
  void shouldFindTheFileBackendsOfTheOtherBuilds() {
    final VideoFileReader windows = OpenCvVideoBackends.decideFromBuildInformation(WINDOWS);
    final VideoFileReader macOs = OpenCvVideoBackends.decideFromBuildInformation(MACOS);
    assertEquals(VideoFileReader.OPENCV, windows);
    assertEquals(VideoFileReader.OPENCV, macOs);
  }

  @Test
  void shouldReadOnlyTheVideoSection() {
    final String elsewhere = """
    General configuration for OpenCV 4.14.0 =====================================

    Media I/O:
        FFMPEG:                      YES

    Video I/O:
        v4l/v4l2:                    YES (linux/videodev2.h)
    """;
    final boolean canDecodeFiles = OpenCvVideoBackends.listsEnabledFileBackend(elsewhere);
    assertFalse(canDecodeFiles, "a backend named in another section says nothing about video files");
  }

  @Test
  void shouldNeedAnEnabledBackendAndAVideoSection() {
    final String disabled = """
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

  // only the first call works the answer out, so the test that asks first has to check the answer as well
  @Test
  void shouldDecideOnceFromTheBuildOfThisMachine() {
    final BytePointer information = opencv_core.getBuildInformation();
    final String text = information.getString();
    final VideoFileReader expected = OpenCvVideoBackends.decideFromBuildInformation(text);

    final VideoFileReader firstAnswer = OpenCvVideoBackends.chooseFileReader();
    final VideoFileReader secondAnswer = OpenCvVideoBackends.chooseFileReader();
    assertEquals(expected, firstAnswer);
    assertSame(firstAnswer, secondAnswer, "the answer cannot change while the library runs");
  }

  @Test
  void shouldPickTheGrabberThatMatchesTheBuild() throws FrameGrabber.Exception {
    try (final FrameGrabber reader = OpenCvPlayer.createFrameGrabber(VIDEO_FILE_NAME, VideoFileReader.OPENCV)) {
      assertInstanceOf(OpenCVFrameGrabber.class, reader);
    }

    try (final FrameGrabber fallback = OpenCvPlayer.createFrameGrabber(VIDEO_FILE_NAME, VideoFileReader.FFMPEG)) {
      assertInstanceOf(FFmpegFrameGrabber.class, fallback, "a build without a file backend reads the file with FFmpeg");
    }
  }

  @Test
  void shouldRefuseNullBuildInformation() {
    assertThrows(NullPointerException.class, () -> OpenCvVideoBackends.listsEnabledFileBackend(null));
  }

  @Test
  void shouldNotBeInstantiable() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(OpenCvVideoBackends.class);
  }
}

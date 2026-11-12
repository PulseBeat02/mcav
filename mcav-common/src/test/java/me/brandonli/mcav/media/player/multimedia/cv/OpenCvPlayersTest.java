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

import static me.brandonli.mcav.media.ResourceAssertions.assertThrowsWhileOpening;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class OpenCvPlayersTest {

  private static final Duration PLAYBACK_TIMEOUT = Duration.ofSeconds(10);

  private static final int DECODED_FRAMES_THRESHOLD = 10;

  private static final String UNOPENED_FILE_NAME = "unopened-file.mp4";

  private static final String DECODED_FRAMES_MESSAGE = "frames %s";

  // Loading the natives on the decoding thread would be slow on a busy machine.
  @BeforeAll
  static void loadTheNativeLibrariesAheadOfTheTimedTests() {
    final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1);
    final ResizeFilter resize = new ResizeFilter(2, 2);
    resize.applyFilter(warmUp);
    warmUp.release();
  }

  @Test
  void shouldCreateTheFileGrabberOfThisPlatformWithoutOpeningTheFile() throws FrameGrabber.Exception {
    final OpenCvPlayer player = new OpenCvPlayer();
    final boolean openCvReadsFiles = OpenCvVideoBackends.chooseFileReader() == VideoFileReader.OPENCV;
    try (final FrameGrabber grabber = player.createFrameGrabber(UNOPENED_FILE_NAME)) {
      if (openCvReadsFiles) {
        assertInstanceOf(OpenCVFrameGrabber.class, grabber, "a build with a file backend reads the file itself");
      } else {
        assertInstanceOf(FFmpegFrameGrabber.class, grabber, "a build without a file backend reads the file with FFmpeg");
      }

      assertThrows(NullPointerException.class, () -> player.createFrameGrabber(null));
    } finally {
      player.release();
    }
  }

  @Test
  void shouldReadFilesWithTheGrabberThatCanOpenThem() throws Exception {
    final Path video = TestMedia.video();
    final boolean openCvReadsFiles = OpenCvVideoBackends.chooseFileReader() == VideoFileReader.OPENCV;
    final boolean openCvOpenedTheFile = opensWithOpenCv(video);
    assertEquals(
      openCvReadsFiles,
      openCvOpenedTheFile,
      "the backend check has to agree with what the reader of OpenCV really does with a file"
    );

    try (final FFmpegFrameGrabber ffmpeg = new FFmpegFrameGrabber(video.toString())) {
      ffmpeg.start();
      final int width = ffmpeg.getImageWidth();
      assertEquals(TestMedia.VIDEO_WIDTH, width, "the FFmpeg reader of JavaCV opens the file on every platform");
    }
  }

  // A build without a file backend either refuses to start or starts without a picture size; both mean it cannot read the file.
  private static boolean opensWithOpenCv(final Path video) {
    final String path = video.toString();
    final OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(path);
    try {
      grabber.start();
      final int width = grabber.getImageWidth();
      return width > 0;
    } catch (final FrameGrabber.Exception exception) {
      return false;
    } finally {
      releaseQuietly(grabber);
    }
  }

  private static void releaseQuietly(final OpenCVFrameGrabber grabber) {
    try {
      grabber.release();
    } catch (final FrameGrabber.Exception exception) {
      // a grabber that never opened has nothing to release
    }
  }

  private static int[] playTestVideo(final Dimension size) throws InterruptedException {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    final OpenCvPlayer player = new OpenCvPlayer();
    player.neverDropLateFrames();
    final VideoFilter counter = (image, _) -> recordWidth(image, frames, width);
    attach(player, counter, size);

    final Path video = TestMedia.video();
    final Source source = FileSource.path(video);
    try {
      final boolean started = player.start(source);
      assertTrue(started);

      Polling.pollUntil(PLAYBACK_TIMEOUT, () -> frames.get() > DECODED_FRAMES_THRESHOLD);
    } finally {
      player.release();
    }

    final int frameCount = frames.get();
    final int lastWidth = width.get();
    return new int[] { frameCount, lastWidth };
  }

  private static boolean recordWidth(final ImageBuffer image, final AtomicInteger frames, final AtomicInteger width) {
    final int frameWidth = image.getWidth();
    width.set(frameWidth);
    return frames.incrementAndGet() > 0;
  }

  private static void attach(final VideoPlayerMultiplexer player, final VideoFilter filter, final Dimension size) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);

    if (size != null) {
      final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
      dimension.attach(size);
    }
  }

  @Test
  void shouldDecodeFilesWithTheOpenCvPlayer() throws Exception {
    final int[] result = playTestVideo(null);
    final int frames = result[0];
    final int width = result[1];
    final boolean decoded = frames > DECODED_FRAMES_THRESHOLD;
    assertTrue(decoded, DECODED_FRAMES_MESSAGE.formatted(frames));
    assertEquals(TestMedia.VIDEO_WIDTH, width);

    final OpenCvPlayer openCv = new OpenCvPlayer();
    assertThrowsWhileOpening(NullPointerException.class, () -> openCv.createFrameGrabber(null));
  }

  @Test
  void shouldScaleFilesToTheAttachedSizeWithTheOpenCvPlayer() throws Exception {
    final Dimension size = Dimension.of(160, 120);
    final int[] result = playTestVideo(size);
    final int frames = result[0];
    final int width = result[1];
    final boolean decoded = frames > DECODED_FRAMES_THRESHOLD;
    assertTrue(decoded, DECODED_FRAMES_MESSAGE.formatted(frames));
    assertEquals(160, width, "the frames arrive at the attached size, whether the reader scales them or the pipeline does");
  }

  @Test
  void shouldOpenCamerasByIndexInTheDevicePlayer() {
    final VideoPlayerMultiplexer player = VideoPlayer.device();
    final VideoInputPlayer device = assertInstanceOf(VideoInputPlayer.class, player);
    final FrameGrabber grabber = device.createFrameGrabber("0");
    assertInstanceOf(OpenCVFrameGrabber.class, grabber);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> device.createFrameGrabber("camera"));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> device.createFrameGrabber("-1"));
    assertThrowsWhileOpening(NullPointerException.class, () -> device.createFrameGrabber(null));
  }

  @Test
  void shouldCreateEveryKindOfPlayerFromTheFactories() {
    final VideoPlayerMultiplexer ffmpeg = VideoPlayer.ffmpeg();
    final VideoPlayerMultiplexer openCv = VideoPlayer.opencv();
    assertInstanceOf(FfmpegPlayer.class, ffmpeg);
    assertInstanceOf(OpenCvPlayer.class, openCv);
  }
}

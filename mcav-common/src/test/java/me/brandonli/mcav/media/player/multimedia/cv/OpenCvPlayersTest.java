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
import me.brandonli.mcav.testing.OpenCvModules;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link OpenCVPlayer}, {@link VideoInputPlayer} and the player factories of {@link VideoPlayer}.
 */
final class OpenCvPlayersTest {

  private static final String NO_FILE_BACKEND =
    "The OpenCV build of this system has no video file backend; the Linux build only captures from V4L2 cameras";
  private static final Duration PLAYBACK_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Loads the natives of OpenCV ahead of the timed tests, because loading them on the decoding thread takes seconds on
   * a busy machine.
   */
  @BeforeAll
  static void loadTheNativeLibrariesAheadOfTheTimedTests() {
    final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1);
    final ResizeFilter resize = new ResizeFilter(2, 2);
    resize.applyFilter(warmUp);
    warmUp.release();
  }

  /**
   * Plays the test video with the OpenCV player until more than ten frames arrived, and records the width of every
   * frame.
   *
   * @param size the size attached to the player, or {@code null} for none
   * @return an array of two elements, the number of frames followed by the width of the last frame
   */
  private static int[] playTestVideo(final Dimension size) throws InterruptedException {
    final boolean fileBackend = OpenCvModules.canDecodeVideoFiles();
    Assumptions.assumeTrue(fileBackend, NO_FILE_BACKEND);

    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    // a player that never drops a frame as late, so how many frames arrive does not depend on the load of the machine
    final OpenCVPlayer player = new OpenCVPlayer();
    player.neverDropLateFrames();
    final VideoFilter counter = (image, _) -> {
      final int frameWidth = image.getWidth();
      width.set(frameWidth);
      return frames.incrementAndGet() > 0;
    };
    attach(player, counter, size);

    final Path video = TestMedia.video();
    final Source source = FileSource.path(video);
    try {
      final boolean started = player.start(source);
      assertTrue(started);
      Polling.pollUntil(PLAYBACK_TIMEOUT, () -> frames.get() > 10);
    } finally {
      player.release();
    }

    final int frameCount = frames.get();
    final int lastWidth = width.get();
    return new int[] { frameCount, lastWidth };
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
  void openCvPlayerDecodesFiles() throws Exception {
    final int[] result = playTestVideo(null);
    final int frames = result[0];
    final int width = result[1];
    assertTrue(frames > 10, "frames " + frames);
    assertEquals(TestMedia.VIDEO_WIDTH, width);

    final OpenCVPlayer openCv = new OpenCVPlayer();
    assertThrowsWhileOpening(NullPointerException.class, () -> openCv.createFrameGrabber(null));
  }

  @Test
  void openCvPlayerScalesFilesToTheAttachedSize() throws Exception {
    final Dimension size = Dimension.of(160, 120);
    final int[] result = playTestVideo(size);
    final int frames = result[0];
    final int width = result[1];
    assertTrue(frames > 10, "frames " + frames);
    assertEquals(160, width, "the file reader of OpenCV ignores the requested size, so the frames are scaled");
  }

  @Test
  void devicePlayerOpensCamerasByIndex() {
    final VideoPlayerMultiplexer player = VideoPlayer.device();
    final VideoInputPlayer device = assertInstanceOf(VideoInputPlayer.class, player);
    final FrameGrabber grabber = device.createFrameGrabber("0");
    assertInstanceOf(OpenCVFrameGrabber.class, grabber);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> device.createFrameGrabber("camera"));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> device.createFrameGrabber("-1"));
    assertThrowsWhileOpening(NullPointerException.class, () -> device.createFrameGrabber(null));
  }

  @Test
  void factoriesCreateEveryKindOfPlayer() {
    final VideoPlayerMultiplexer ffmpeg = VideoPlayer.ffmpeg();
    final VideoPlayerMultiplexer openCv = VideoPlayer.opencv();
    assertInstanceOf(FFmpegPlayer.class, ffmpeg);
    assertInstanceOf(OpenCVPlayer.class, openCv);
    assertThrows(NullPointerException.class, () -> VideoPlayer.vlc((String[]) null));
  }
}

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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * Builds RV32 buffers the way VLC fills them and records the frames that reach a video pipeline.
 */
final class VlcTestFrames {

  private static final long FRAME_TIMEOUT_SECONDS = 5L;
  private static final long NO_FRAME_MILLIS = 200L;
  private static final int RGB_MASK = 0xFFFFFF;

  private VlcTestFrames() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Encodes packed ARGB pixels as an RV32 buffer: blue, green, red, then the fourth byte, in a direct buffer like the
   * memory VLC renders into.
   *
   * @param argbPixels the pixels row by row
   * @return the buffer
   */
  static ByteBuffer rv32(final int... argbPixels) {
    final ByteBuffer buffer = ByteBuffer.allocateDirect(argbPixels.length * 4);
    for (final int pixel : argbPixels) {
      buffer.put((byte) pixel);
      buffer.put((byte) (pixel >>> 8));
      buffer.put((byte) (pixel >>> 16));
      buffer.put((byte) (pixel >>> 24));
    }
    buffer.flip();
    return buffer;
  }

  /**
   * Creates an array of equal pixels.
   *
   * @param count the number of pixels
   * @param argb  the pixel
   * @return the pixels
   */
  static int[] solid(final int count, final int argb) {
    final int[] pixels = new int[count];
    Arrays.fill(pixels, argb);
    return pixels;
  }

  /**
   * Creates a filter that records every frame without changing it.
   *
   * @param frames receives the frames
   * @return the filter
   */
  static VideoFilter recorder(final BlockingQueue<RecordedFrame> frames) {
    return (image, metadata) -> {
      final RecordedFrame frame = new RecordedFrame(image, metadata);
      frames.add(frame);
      return false;
    };
  }

  /**
   * Attaches a video pipeline that records every frame.
   *
   * @param player the player
   * @param frames receives the frames
   */
  static void record(final VideoPlayerMultiplexer player, final BlockingQueue<RecordedFrame> frames) {
    final VideoFilter filter = recorder(frames);
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);
  }

  /**
   * Takes the next recorded frame, failing after five seconds.
   *
   * @param frames the recorded frames
   * @return the frame
   * @throws InterruptedException if interrupted while waiting
   */
  static RecordedFrame take(final BlockingQueue<RecordedFrame> frames) throws InterruptedException {
    final RecordedFrame frame = frames.poll(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(frame, "no frame reached the pipeline");
    return frame;
  }

  /**
   * Asserts that no frame reaches the pipeline within 200 ms.
   *
   * @param frames the recorded frames
   * @throws InterruptedException if interrupted while waiting
   */
  static void assertNoFrame(final BlockingQueue<RecordedFrame> frames) throws InterruptedException {
    final RecordedFrame frame = frames.poll(NO_FRAME_MILLIS, TimeUnit.MILLISECONDS);
    assertNull(frame, "a frame reached the pipeline");
  }

  /**
   * A frame that reached the pipeline, copied while the pipeline ran.
   */
  static final class RecordedFrame {

    private final ImageBuffer image;
    private final int width;
    private final int height;
    private final int[] rgb;
    private final OriginalVideoMetadata metadata;

    RecordedFrame(final ImageBuffer image, final OriginalVideoMetadata metadata) {
      this.image = image;
      this.width = image.getWidth();
      this.height = image.getHeight();
      final int[] pixels = image.getPixels();
      this.rgb = new int[pixels.length];
      for (int index = 0; index < pixels.length; index++) {
        this.rgb[index] = pixels[index] & RGB_MASK;
      }
      this.metadata = metadata;
    }

    ImageBuffer getImage() {
      return this.image;
    }

    int getWidth() {
      return this.width;
    }

    int getHeight() {
      return this.height;
    }

    int[] getRgb() {
      return this.rgb;
    }

    OriginalVideoMetadata getMetadata() {
      return this.metadata;
    }
  }
}

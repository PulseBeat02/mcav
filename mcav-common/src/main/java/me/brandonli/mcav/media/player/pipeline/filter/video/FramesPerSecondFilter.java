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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import java.util.function.LongSupplier;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Draws the current frame rate of the pipeline into the top left corner of every frame, which is useful while
 * tuning a pipeline. The count is refreshed after an elapsed window of at least one second. A filter shared by several pipelines
 * counts their combined calls, so use a separate instance when measuring each pipeline.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class FramesPerSecondFilter extends MatVideoFilter {

  private static final Scalar WHITE = new Scalar(255, 255, 255, 0);
  private static final Scalar BLACK = new Scalar(0, 0, 0, 0);
  private static final Point POSITION = new Point(10, 24);
  private static final double FONT_SCALE = 0.6;
  private static final int OUTLINE_THICKNESS = 3;
  private static final int TEXT_THICKNESS = 1;
  private static final boolean BOTTOM_LEFT_ORIGIN = false;
  private static final long WINDOW_NANOSECONDS = 1_000_000_000L;

  private final LongSupplier clock;

  private long windowStart;
  private int framesInWindow;
  private int displayedFrameRate;

  /**
   * Creates a new frame rate filter.
   *
   * @since 1.0.0
   */
  public FramesPerSecondFilter() {
    this(System::nanoTime);
  }

  FramesPerSecondFilter(final LongSupplier clock) {
    this.clock = clock;
    this.windowStart = clock.getAsLong();
  }

  /**
   * Counts the frame and draws the smoothed frame rate onto it in place, as white text with a black outline.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the text is drawn onto every frame
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    this.countFrame();
    final String text = "%s fps".formatted(this.displayedFrameRate);
    drawText(matrix, text, BLACK, OUTLINE_THICKNESS);
    drawText(matrix, text, WHITE, TEXT_THICKNESS);

    return true;
  }

  private synchronized void countFrame() {
    final long now = this.clock.getAsLong();
    this.framesInWindow++;
    final long elapsed = now - this.windowStart;
    if (elapsed < WINDOW_NANOSECONDS) {
      return;
    }

    final double seconds = elapsed / (double) WINDOW_NANOSECONDS;
    this.displayedFrameRate = (int) Math.round(this.framesInWindow / seconds);
    this.framesInWindow = 0;
    this.windowStart = now;
  }

  private static void drawText(final Mat matrix, final String text, final Scalar color, final int thickness) {
    opencv_imgproc.putText(
      matrix,
      text,
      POSITION,
      opencv_imgproc.FONT_HERSHEY_SIMPLEX,
      FONT_SCALE,
      color,
      thickness,
      opencv_imgproc.LINE_AA,
      BOTTOM_LEFT_ORIGIN
    );
  }
}

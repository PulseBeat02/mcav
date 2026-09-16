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

import java.util.concurrent.atomic.AtomicReference;
import org.bytedeco.opencv.opencv_core.Mat;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A matrix a filter reuses for its intermediate results instead of allocating one for every frame.
 *
 * <p>One filter may be attached to several pipelines, whose render threads then call it at the same time, so the
 * matrix is lent to one frame at a time instead of being shared: {@link #borrow()} hands it out, and a caller that
 * finds it lent out gets a new matrix instead. {@link #giveBack(Mat)} keeps one matrix for the next frame and frees any
 * other. A filter used by one pipeline, which is the normal case, therefore allocates a matrix only for its first
 * frame, and OpenCV reallocates its pixels only when a frame has another size or type than the frame before.
 *
 * <p>The kept matrix is freed by JavaCPP once the filter is garbage collected, like every other matrix a filter keeps.
 */
final class ReusableMat {

  private final AtomicReference<@Nullable Mat> idle;

  /**
   * Constructs a new holder without a matrix; the first call to {@link #borrow()} creates it.
   */
  ReusableMat() {
    this.idle = new AtomicReference<>();
  }

  /**
   * Lends the kept matrix to the caller, or creates an empty matrix if it is lent out already or was never created.
   *
   * @return a matrix only the caller uses until it hands it to {@link #giveBack(Mat)}; its content is whatever the
   *     previous borrower left in it
   */
  Mat borrow() {
    final Mat lent = this.idle.getAndSet(null);
    if (lent != null) {
      return lent;
    }
    return new Mat();
  }

  /**
   * Hands a borrowed matrix back. It is kept for the next frame unless another matrix was handed back first, in which
   * case it is freed.
   *
   * @param matrix the matrix, which the caller must not use afterward
   * @return true if the matrix was kept, false if it was freed
   */
  boolean giveBack(final Mat matrix) {
    final boolean kept = this.idle.compareAndSet(null, matrix);
    if (!kept) {
      matrix.close();
    }
    return kept;
  }
}

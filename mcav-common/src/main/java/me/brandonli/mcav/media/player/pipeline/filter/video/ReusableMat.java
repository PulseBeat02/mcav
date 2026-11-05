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

// A filter's scratch matrix, lent to one frame at a time because a filter in several pipelines runs on several threads at once.
final class ReusableMat {

  private final AtomicReference<@Nullable Mat> idle;

  ReusableMat() {
    this.idle = new AtomicReference<>();
  }

  // Lends the kept matrix to the caller, or creates an empty matrix if it is lent out already or was never created.
  Mat borrow() {
    final Mat lent = this.idle.getAndSet(null);
    if (lent != null) {
      return lent;
    }

    return new Mat();
  }

  // Keeps the matrix for the next frame unless another one was handed back first, else frees it.
  boolean giveBack(final Mat matrix) {
    final boolean kept = this.idle.compareAndSet(null, matrix);
    if (!kept) {
      matrix.close();
    }

    return kept;
  }
}

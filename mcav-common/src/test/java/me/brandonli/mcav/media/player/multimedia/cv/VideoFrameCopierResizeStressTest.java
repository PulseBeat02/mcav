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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Copies frames while another thread attaches other sizes and detaches them, the stress half of the race test of the
 * size a copier scales to. The copier reads the attached size once per frame, so every copy has either the size of
 * the frame, when no size was attached, or exactly one of the attached sizes, never the width of one and the height of
 * another, and no copy fails. jcstress cannot run the copier, which writes into OpenCV matrices whose native libraries
 * the jcstress jar does not carry; {@code ConfigureGrabberResizeRace} there covers the same read in the player itself.
 */
final class VideoFrameCopierResizeStressTest {

  private static final int COPIES = 20_000;
  private static final int FRAME_WIDTH = 4;
  private static final int FRAME_HEIGHT = 2;
  private static final Dimension SMALLER = Dimension.of(2, 1);
  private static final Dimension LARGER = Dimension.of(8, 6);
  private static final Set<String> ALLOWED = Set.of("4x2", "2x1", "8x6");

  @Test
  void everyCopyHasTheSizeOfTheFrameOrOfOneAttachedSize() throws InterruptedException {
    final ImagePool pool = new ImagePool(4);
    final DimensionAttachableCallback dimensionCallback = DimensionAttachableCallback.create();
    final VideoFrameCopier copier = new VideoFrameCopier(pool, dimensionCallback);
    final Frame frame = new Frame(FRAME_WIDTH, FRAME_HEIGHT, Frame.DEPTH_UBYTE, 3);
    final AtomicBoolean copying = new AtomicBoolean(true);
    final Thread resizer = new Thread(() -> resizeWhile(copying, dimensionCallback), "stress-resize");
    final Set<String> sizes = new TreeSet<>();
    resizer.start();
    try {
      for (int copy = 0; copy < COPIES; copy++) {
        final MatImageBuffer image = copier.copy(frame);
        final int width = image.getWidth();
        final int height = image.getHeight();
        sizes.add(width + "x" + height);
        pool.recycle(image);
      }
    } finally {
      copying.set(false);
      resizer.join();
      copier.release();
      pool.close();
    }

    final boolean onlyAllowed = ALLOWED.containsAll(sizes);
    final int sizeCount = sizes.size();
    assertTrue(onlyAllowed, () -> "copies of sizes that were never attached: " + sizes);
    assertTrue(sizeCount > 1, () -> "the size never changed while copying, so nothing raced: " + sizes);
  }

  /**
   * Attaches the smaller size, then the larger one, then detaches, over and over until copying ends.
   */
  private static void resizeWhile(final AtomicBoolean copying, final DimensionAttachableCallback dimensionCallback) {
    int step = 0;
    while (copying.get()) {
      switch (step) {
        case 0 -> dimensionCallback.attach(SMALLER);
        case 1 -> dimensionCallback.attach(LARGER);
        default -> dimensionCallback.detach();
      }
      step = (step + 1) % 3;
    }
  }
}

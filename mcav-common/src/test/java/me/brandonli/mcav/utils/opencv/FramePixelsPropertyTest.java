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
package me.brandonli.mcav.utils.opencv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.bytedeco.javacv.Frame;

final class FramePixelsPropertyTest {

  private static final String SEED = "20261003";
  private static final int CHANNELS = 3;
  private static final int MARGIN = 7;
  private static final byte SENTINEL = 93;

  @Property(seed = SEED)
  void compactingPaddedRowsPreservesPixelsAndBufferWindows(
    @ForAll @IntRange(min = 0, max = 32) final int width,
    @ForAll @IntRange(min = 0, max = 24) final int height,
    @ForAll @IntRange(min = 0, max = 16) final int padding,
    @ForAll final long seed,
    @ForAll final boolean direct
  ) {
    final byte[] pixels = new byte[width * height * CHANNELS];
    new Random(seed).nextBytes(pixels);
    final int rowBytes = width * CHANNELS;
    final int stride = rowBytes + padding;
    final ByteBuffer storage = direct ? ByteBuffer.allocateDirect(stride * height + MARGIN) : ByteBuffer.allocate(stride * height + MARGIN);
    storage.position(MARGIN);
    final ByteBuffer source = storage.slice();
    for (int row = 0; row < height; row++) {
      source.put(row * stride, pixels, row * rowBytes, rowBytes);
    }
    source.position(Math.min(1, source.capacity()));
    final int sourcePosition = source.position();
    final byte[] destination = new byte[pixels.length + MARGIN];
    Arrays.fill(destination, SENTINEL);
    final ByteBuffer target = ByteBuffer.wrap(destination);
    target.position(1);
    target.limit(2);
    try (final Frame frame = new Frame()) {
      frame.imageWidth = width;
      frame.imageHeight = height;
      frame.imageStride = stride;
      frame.imageDepth = Frame.DEPTH_UBYTE;
      frame.image = new Buffer[] { source };
      FramePixels.copyBgr(frame, target);
      final byte[] expected = Arrays.copyOf(pixels, destination.length);
      Arrays.fill(expected, pixels.length, expected.length, SENTINEL);
      assertArrayEquals(expected, destination, "only compact pixels are written, starting at target index zero");
      assertEquals(1, target.position());
      assertEquals(2, target.limit());
      assertEquals(sourcePosition, source.position());
      assertEquals(stride * height, source.limit());
      final ByteBuffer allocated = FramePixels.copyBgr(frame);
      final byte[] copy = new byte[allocated.remaining()];
      allocated.get(copy);
      assertArrayEquals(pixels, copy);
    }
  }

  @Provide
  Arbitrary<Integer> widthsWhoseByteCountsWrap() {
    return Arbitraries.of(1_431_655_766, 1_431_655_767, 1_431_655_768);
  }

  @Property(seed = SEED)
  void aWrappedByteCountNeverMakesAHugeRowValid(@ForAll("widthsWhoseByteCountsWrap") final int width) {
    try (final Frame frame = new Frame()) {
      frame.imageWidth = width;
      frame.imageHeight = 1;
      frame.imageStride = 8;
      frame.image = new Buffer[] { ByteBuffer.allocate(8) };
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame, ByteBuffer.allocate(8)));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame));
    }
  }
}

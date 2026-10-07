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
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class FramePixelsBoundsTest {

  @Test
  void refusesARowWhoseByteCountWrapsIntoTheSourcePlane() {
    try (final Frame frame = new Frame()) {
      frame.imageWidth = 1_431_655_766;
      frame.imageHeight = 1;
      frame.imageStride = 2;
      frame.imageDepth = Frame.DEPTH_UBYTE;
      frame.image = new Buffer[] { ByteBuffer.wrap(new byte[] { 11, 12 }) };
      final byte[] untouched = { 21, 22 };
      final ByteBuffer target = ByteBuffer.wrap(untouched);

      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame, target));
      assertArrayEquals(new byte[] { 21, 22 }, untouched);
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame));
    }
  }

  @Test
  void refusesAFrameWhoseByteCountWrapsToZero() {
    try (final Frame frame = new Frame()) {
      frame.imageWidth = 65_536;
      frame.imageHeight = 65_536;
      frame.imageStride = 196_608;
      frame.imageDepth = Frame.DEPTH_UBYTE;
      frame.image = new Buffer[] { ByteBuffer.allocate(0) };

      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame, ByteBuffer.allocate(0)));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame));
    }
  }

  @ParameterizedTest
  @CsvSource({ "-1, 1", "1, -1", "0, -1", "-1, 0" })
  void refusesNegativeDimensions(final int width, final int height) {
    try (final Frame frame = new Frame()) {
      frame.imageWidth = width;
      frame.imageHeight = height;
      frame.imageStride = 3;
      frame.image = new Buffer[] { ByteBuffer.allocate(3) };

      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame, ByteBuffer.allocate(3)));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(frame));
    }
  }

  @Test
  void stillCopiesAnEmptyFrame() {
    try (final Frame frame = new Frame()) {
      frame.image = new Buffer[] { ByteBuffer.allocate(0) };
      final ByteBuffer copied = FramePixels.copyBgr(frame);
      assertEquals(0, copied.remaining());
    }
  }
}

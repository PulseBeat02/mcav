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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.ShortBuffer;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

final class FramePixelsTest {

  private static Frame frame(final int width, final int height, final int stride, final byte[] data) {
    final Frame frame = new Frame();
    frame.imageWidth = width;
    frame.imageHeight = height;
    frame.imageStride = stride;
    frame.imageDepth = Frame.DEPTH_UBYTE;
    frame.imageChannels = stride / width;
    final ByteBuffer plane = ByteBuffer.wrap(data);
    frame.image = new Buffer[] { plane };
    return frame;
  }

  private static byte[] remaining(final ByteBuffer buffer) {
    final ByteBuffer view = buffer.duplicate();
    final int remaining = view.remaining();
    final byte[] bytes = new byte[remaining];
    view.get(bytes);
    return bytes;
  }

  @Test
  void shouldCopyCompactFramesAsTheyAre() {
    final byte[] data = { 1, 2, 3, 4, 5, 6 };
    final Frame compact = frame(2, 1, 6, data);
    final ByteBuffer pixels = FramePixels.copyBgr(compact);
    final byte[] copied = remaining(pixels);
    final boolean direct = pixels.isDirect();
    assertArrayEquals(data, copied);
    assertTrue(direct);
  }

  @Test
  void shouldCopyEveryRowOfACompactFrameAndCheckTheWholeTargetSize() {
    final byte[] data = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
    try (final Frame compact = frame(2, 2, 6, data)) {
      final ByteBuffer actual = FramePixels.copyBgr(compact);
      final byte[] copied = remaining(actual);
      assertArrayEquals(data, copied);

      final ByteBuffer tooSmall = ByteBuffer.allocate(11);
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(compact, tooSmall));
    }
  }

  @Test
  void shouldDropTheRowPaddingOfNarrowFrames() {
    final byte[] data = { 1, 2, 3, 0, 0, 0, 0, 0, 4, 5, 6, 0, 0, 0, 0, 0 };
    final Frame padded = frame(1, 2, 8, data);
    final ByteBuffer pixels = FramePixels.copyBgr(padded);
    final byte[] copied = remaining(pixels);
    final byte[] expected = new byte[] { 1, 2, 3, 4, 5, 6 };
    assertEquals(8, padded.imageChannels, "JavaCV would read eight channels from this frame");
    assertArrayEquals(expected, copied);
  }

  @Test
  void shouldCopyIntoAReusedBufferFromItsStart() {
    final Frame padded = frame(1, 2, 8, new byte[] { 1, 2, 3, 0, 0, 0, 0, 0, 4, 5, 6, 0, 0, 0, 0, 0 });
    final Frame compact = frame(2, 1, 6, new byte[] { 7, 8, 9, 10, 11, 12 });
    final ByteBuffer target = ByteBuffer.allocate(8);
    target.position(5);
    FramePixels.copyBgr(padded, target);
    final byte[] first = new byte[6];
    target.get(0, first);
    FramePixels.copyBgr(compact, target);
    final byte[] second = new byte[6];
    target.get(0, second);
    final int position = target.position();
    final int limit = target.limit();
    final byte[] expectedFirst = new byte[] { 1, 2, 3, 4, 5, 6 };
    final byte[] expectedSecond = new byte[] { 7, 8, 9, 10, 11, 12 };
    assertArrayEquals(expectedFirst, first);
    assertArrayEquals(expectedSecond, second, "the same buffer receives every frame");
    assertEquals(5, position, "the position of the target does not change");
    assertEquals(8, limit);
  }

  @Test
  void shouldRejectTargetsThatAreTooSmall() {
    final ByteBuffer small = ByteBuffer.allocate(5);
    try (final Frame compact = frame(2, 1, 6, new byte[6])) {
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(compact, small));
    }
  }

  @Test
  void shouldRejectFramesWithoutUsablePixels() {
    try (
      final Frame audio = new Frame();
      final Frame noPlanes = frame(1, 1, 3, new byte[3]);
      final Frame shortSamples = frame(1, 1, 3, new byte[3]);
      final Frame shortRows = frame(2, 1, 5, new byte[5])
    ) {
      noPlanes.image = new Buffer[0];
      final ShortBuffer samples = ShortBuffer.allocate(3);
      shortSamples.image = new Buffer[] { samples };
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(audio));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(noPlanes));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(shortSamples));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(shortRows));
    }
  }

  @Test
  void shouldRejectNullArguments() {
    final ByteBuffer target = ByteBuffer.allocate(6);
    try (final Frame compact = frame(2, 1, 6, new byte[6])) {
      assertThrows(NullPointerException.class, () -> FramePixels.copyBgr(compact, null));
    }

    assertThrows(NullPointerException.class, () -> FramePixels.copyBgr(null, target));
    assertThrows(NullPointerException.class, () -> FramePixels.copyBgr(null));
  }

  @Test
  void shouldRejectAPlaneThatCannotHoldTheDeclaredRows() {
    final ByteBuffer target = ByteBuffer.allocate(6);
    try (final Frame compact = frame(1, 2, 3, new byte[3]); final Frame padded = frame(1, 2, 8, new byte[10])) {
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(compact, target));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(padded, target));
      assertThrows(IllegalArgumentException.class, () -> FramePixels.copyBgr(compact));
    }
  }

  @Test
  void shouldRejectAReadOnlyTarget() {
    final ByteBuffer writable = ByteBuffer.allocate(6);
    final ByteBuffer readOnly = writable.asReadOnlyBuffer();
    try (final Frame compact = frame(2, 1, 6, new byte[6]); final Frame paddedWithoutRows = frame(2, 0, 8, new byte[0])) {
      assertThrows(ReadOnlyBufferException.class, () -> FramePixels.copyBgr(compact, readOnly));
      assertThrows(ReadOnlyBufferException.class, () -> FramePixels.copyBgr(paddedWithoutRows, readOnly));
    }
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FramePixels.class);
  }
}

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests {@link VideoFrameCopier}.
 */
final class VideoFrameCopierTest {

  private static final int FRAME_HEIGHT = 2;

  private final ImagePool pool = new ImagePool(4);
  private final DimensionAttachableCallback dimension = DimensionAttachableCallback.create();
  private final VideoFrameCopier copier = new VideoFrameCopier(this.pool, this.dimension);

  @AfterEach
  void releaseEverything() {
    this.copier.release();
    this.pool.close();
  }

  /**
   * Creates a BGR frame of two rows in native memory whose pixels all have the same color, the way decoders do.
   */
  private static Frame solidFrame(final int width, final byte blue, final byte green, final byte red) {
    final Frame frame = new Frame(width, FRAME_HEIGHT, Frame.DEPTH_UBYTE, 3);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    final int stride = frame.imageStride;
    for (int y = 0; y < FRAME_HEIGHT; y++) {
      for (int x = 0; x < width; x++) {
        final int index = y * stride + x * 3;
        pixels.put(index, blue);
        pixels.put(index + 1, green);
        pixels.put(index + 2, red);
      }
    }
    return frame;
  }

  /**
   * Creates the same frame as {@link #solidFrame}, but with its pixels on the Java heap, where OpenCV cannot read them.
   */
  private static Frame heapFrame(final int width, final byte blue, final byte green, final byte red) {
    final Frame frame = solidFrame(width, blue, green, red);
    final ByteBuffer direct = (ByteBuffer) frame.image[0];
    final ByteBuffer whole = direct.duplicate();
    whole.clear();
    final byte[] bytes = new byte[whole.capacity()];
    whole.get(bytes);
    frame.image[0] = ByteBuffer.wrap(bytes);
    return frame;
  }

  /**
   * Creates frames whose pixels cannot be read as 8-bit BGR rows: without an image, without planes, with 16-bit
   * pixels, without width or height, with rows shorter than a row of pixels, and with a plane too small for its rows.
   */
  private static List<Frame> invalidFrames() {
    final Frame withoutImage = new Frame();
    final Frame withoutPlanes = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    withoutPlanes.image = new Buffer[0];
    final Frame sixteenBit = new Frame(4, FRAME_HEIGHT, Frame.DEPTH_USHORT, 3);
    final Frame withoutWidth = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    withoutWidth.imageWidth = 0;
    final Frame withoutHeight = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    withoutHeight.imageHeight = 0;
    final Frame shortRows = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    shortRows.imageStride = 3;
    final Frame truncated = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    truncated.image[0] = ByteBuffer.allocateDirect(10);
    return List.of(withoutImage, withoutPlanes, sixteenBit, withoutWidth, withoutHeight, shortRows, truncated);
  }

  private void attachSize(final int width, final int height) {
    final Dimension size = Dimension.of(width, height);
    this.dimension.attach(size);
  }

  @Test
  void copiesFramesIntoImagesOfTheirSize() {
    final Frame frame = solidFrame(3, (byte) 0x30, (byte) 0x20, (byte) 0x10);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int[] expected = new int[6];
    Arrays.fill(expected, 0xFF102030);

    assertEquals(3, width);
    assertEquals(2, height);
    assertArrayEquals(expected, pixels);
    image.release();
  }

  @Test
  void reusesAnImageHandedBackToThePoolAndRefreshesItsPixels() {
    final Frame black = solidFrame(2, (byte) 0, (byte) 0, (byte) 0);
    final Frame white = solidFrame(2, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
    final MatImageBuffer first = this.copier.copy(black);
    final int[] blackPixels = first.getPixels();
    final int blackPixel = blackPixels[0];
    this.pool.recycle(first);
    final MatImageBuffer second = this.copier.copy(white);
    final int[] whitePixels = second.getPixels();
    final int whitePixel = whitePixels[3];

    assertSame(first, second, "the frame is copied into the image from the pool");
    assertEquals(0xFF000000, blackPixel);
    assertEquals(0xFFFFFFFF, whitePixel, "the cached pixels of the reused image are refreshed");
    second.release();
  }

  @Test
  void resizesPooledImagesOfAnotherSizeInPlace() {
    final Frame wide = solidFrame(4, (byte) 0, (byte) 0, (byte) 0);
    final Frame small = solidFrame(2, (byte) 0x30, (byte) 0x20, (byte) 0x10);
    final MatImageBuffer wideImage = this.copier.copy(wide);
    this.pool.recycle(wideImage);
    final MatImageBuffer smallImage = this.copier.copy(small);
    final int width = smallImage.getWidth();
    final int[] pixels = smallImage.getPixels();
    final int[] expected = new int[4];
    Arrays.fill(expected, 0xFF102030);

    assertSame(wideImage, smallImage, "the pooled image is resized instead of being replaced");
    assertEquals(2, width);
    assertArrayEquals(expected, pixels);
    smallImage.release();
  }

  @Test
  void averagesAllSourcePixelsWhenShrinkingEitherAxis() {
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 3 : 1;
      final int height = horizontal ? 1 : 3;
      try (final Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {
        final ByteBuffer data = (ByteBuffer) frame.image[0];
        for (int index = 0; index < data.capacity(); index++) {
          data.put(index, (byte) 0);
        }
        final int middle = horizontal ? 3 : frame.imageStride;
        data.put(middle, (byte) 90);
        this.attachSize(1, 1);
        try (final MatImageBuffer image = this.copier.copy(frame)) {
          final int[] actual = image.getPixels();
          assertArrayEquals(new int[] { 0xFF00001E }, actual);
        }
      }
    }
  }

  @Test
  void interpolatesGrowingFramesAndRefreshesTheReusedPixelCache() {
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 2 : 1;
      final int height = horizontal ? 1 : 2;
      try (final Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {
        final ByteBuffer data = (ByteBuffer) frame.image[0];
        for (int index = 0; index < data.capacity(); index++) {
          data.put(index, (byte) 0);
        }
        final int secondPixel = horizontal ? 3 : frame.imageStride;
        data.put(secondPixel, (byte) 80);
        this.attachSize(horizontal ? 4 : 1, horizontal ? 1 : 4);
        final MatImageBuffer first = this.copier.copy(frame);
        final int[] actual = first.getPixels();
        assertArrayEquals(new int[] { 0xFF000000, 0xFF000014, 0xFF00003C, 0xFF000050 }, actual);
        this.pool.recycle(first);
        data.put(secondPixel, (byte) 0);
        try (final MatImageBuffer second = this.copier.copy(frame)) {
          final int[] refreshed = second.getPixels();
          assertSame(first, second);
          assertArrayEquals(new int[] { 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000 }, refreshed);
        }
      }
    }
  }

  @Test
  void shrinksFramesThatDoNotHaveTheAttachedSize() {
    this.attachSize(2, 1);
    final Frame frame = solidFrame(4, (byte) 0x30, (byte) 0x20, (byte) 0x10);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final boolean copied = this.copier.hasUnscaledCopy();

    assertEquals(2, width);
    assertEquals(1, height);
    assertArrayEquals(new int[] { 0xFF102030, 0xFF102030 }, pixels, "area averaging keeps a solid color");
    assertFalse(copied, "frames in native memory are scaled straight from the memory of the decoder");

    this.pool.recycle(image);
    final MatImageBuffer again = this.copier.copy(frame);
    assertSame(image, again, "the scaled image is reused as well");
    again.release();
  }

  @Test
  void enlargesFramesThatAreSmallerThanTheAttachedSize() {
    this.attachSize(8, 4);
    final Frame frame = solidFrame(4, (byte) 0x30, (byte) 0x20, (byte) 0x10);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int corner = pixels[31];

    assertEquals(8, width);
    assertEquals(4, height);
    assertEquals(0xFF102030, corner);
    image.release();
  }

  @Test
  void scalesFramesThatOnlyDifferInHeight() {
    final Frame frame = solidFrame(4, (byte) 0, (byte) 0, (byte) 0x40);
    final MatImageBuffer unscaled = this.copier.copy(frame);
    this.pool.recycle(unscaled);
    this.attachSize(4, 1);
    final MatImageBuffer scaled = this.copier.copy(frame);
    final int width = scaled.getWidth();
    final int height = scaled.getHeight();
    final int[] pixels = scaled.getPixels();
    final int first = pixels[0];

    assertEquals(4, width);
    assertEquals(1, height);
    assertEquals(0xFF400000, first);
    assertSame(unscaled, scaled, "the pooled image of the old height is resized in place");
    scaled.release();
  }

  @Test
  void copiesFramesThatAlreadyHaveTheAttachedSizeWithoutScaling() {
    this.attachSize(4, 2);
    final Frame frame = solidFrame(4, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int first = pixels[0];

    assertEquals(4, width);
    assertEquals(0xFF030201, first);
    image.release();
  }

  @Test
  void copiesFramesAtTheirOwnSizeWhenTheAttachedSizeIsEmpty() {
    // attach() rejects an empty size, so the only way a copier ever sees one is the race this guards against: the
    // callback is detached between the copier asking whether a size is attached and asking what it is, and answers
    // the second question with its empty fallback. Scaling a frame to 0x0 used to throw out of the decoding loop
    // and end playback, so an empty size must be copied at the size of the frame instead.
    final DimensionAttachableCallback detachedMidFrame = Mockito.mock(DimensionAttachableCallback.class);
    Mockito.when(detachedMidFrame.isAttached()).thenReturn(true);
    Mockito.when(detachedMidFrame.retrieve()).thenReturn(Dimension.NONE);
    final VideoFrameCopier racingCopier = new VideoFrameCopier(this.pool, detachedMidFrame);

    final Frame frame = solidFrame(4, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer image = racingCopier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int first = pixels[0];

    assertEquals(4, width);
    assertEquals(FRAME_HEIGHT, height);
    assertEquals(0xFF030201, first);
    image.release();
    racingCopier.release();
  }

  @Test
  void handsThePooledImageBackWhenAFrameCannotBeCopied() {
    final Frame usable = solidFrame(4, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer pooled = this.copier.copy(usable);
    this.pool.recycle(pooled);
    final int unusedBefore = this.pool.getUnusedCount();
    assertEquals(1, unusedBefore);

    final Frame broken = new Frame(4, FRAME_HEIGHT, Frame.DEPTH_UBYTE, 3);
    broken.image = new Buffer[0];
    assertThrows(RuntimeException.class, () -> this.copier.copy(broken));
    final int unusedAfter = this.pool.getUnusedCount();
    assertEquals(1, unusedAfter, "a frame that cannot be copied must not cost the pool one of its images");
  }

  @Test
  void scalesExactlyPackedNativeRowsWithoutAllocatingAnUnscaledCopy() {
    this.attachSize(1, 1);
    try (final Frame frame = new Frame()) {
      frame.imageWidth = 2;
      frame.imageHeight = 2;
      frame.imageDepth = Frame.DEPTH_UBYTE;
      frame.imageChannels = 3;
      frame.imageStride = 6;
      final ByteBuffer pixels = ByteBuffer.allocateDirect(12);
      for (int index = 0; index < 12; index++) {
        pixels.put(index, (byte) 32);
      }
      frame.image = new Buffer[] { pixels };
      try (final MatImageBuffer image = this.copier.copy(frame)) {
        final int[] actual = image.getPixels();
        final boolean extraCopy = this.copier.hasUnscaledCopy();
        assertArrayEquals(new int[] { 0xFF202020 }, actual);
        assertFalse(extraCopy, "a valid native plane with exact row and capacity bounds scales directly");
      }
    }
  }

  @Test
  void scalesFramesOutsideOfNativeMemoryThroughACopy() {
    this.attachSize(2, 1);
    final Frame frame = heapFrame(4, (byte) 0x30, (byte) 0x20, (byte) 0x10);
    final MatImageBuffer image = this.copier.copy(frame);
    final int[] pixels = image.getPixels();
    final boolean copied = this.copier.hasUnscaledCopy();

    assertArrayEquals(new int[] { 0xFF102030, 0xFF102030 }, pixels);
    assertTrue(copied, "OpenCV cannot read the heap, so the frame is copied into native memory first");
    image.release();
  }

  @Test
  void rejectsFramesWithoutUsableBgrPixelsWhileScaling() {
    this.attachSize(2, 1);
    final List<Frame> frames = invalidFrames();
    for (final Frame frame : frames) {
      assertThrows(IllegalArgumentException.class, () -> this.copier.copy(frame));
    }
  }

  @Test
  void releasingTwiceIsHarmless() {
    this.attachSize(1, 1);
    final Frame frame = heapFrame(2, (byte) 0, (byte) 0, (byte) 0);
    final MatImageBuffer image = this.copier.copy(frame);
    final boolean copiedBeforeRelease = this.copier.hasUnscaledCopy();
    this.copier.release();
    this.copier.release();
    final boolean copiedAfterRelease = this.copier.hasUnscaledCopy();
    final MatImageBuffer afterRelease = this.copier.copy(frame);
    final int width = afterRelease.getWidth();

    assertTrue(copiedBeforeRelease);
    assertFalse(copiedAfterRelease);
    assertEquals(1, width, "the copier still works after its unscaled image was released");
    image.release();
    afterRelease.release();
  }
}

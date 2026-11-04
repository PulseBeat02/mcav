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

final class VideoFrameCopierTest {

  private static final int FRAME_WIDTH = 4;
  private static final int FRAME_HEIGHT = 2;

  private static final int NARROW_FRAME_WIDTH = 2;
  private static final int SHRUNK_WIDTH = 2;

  private static final int BGR_CHANNELS = 3;

  private static final byte SOLID_BLUE = (byte) 0x30;
  private static final byte SOLID_GREEN = (byte) 0x20;
  private static final byte SOLID_RED = (byte) 0x10;
  private static final int SOLID_ARGB = 0xFF102030;

  private final ImagePool pool = new ImagePool(4);
  private final DimensionAttachableCallback dimension = DimensionAttachableCallback.create();
  private final VideoFrameCopier copier = new VideoFrameCopier(this.pool, this.dimension);

  @AfterEach
  void releaseEverything() {
    this.copier.release();
    this.pool.close();
  }

  private static Frame solidFrame(final int width, final byte blue, final byte green, final byte red) {
    final Frame frame = new Frame(width, FRAME_HEIGHT, Frame.DEPTH_UBYTE, BGR_CHANNELS);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    final int stride = frame.imageStride;
    for (int row = 0; row < FRAME_HEIGHT; row++) {
      for (int column = 0; column < width; column++) {
        final int index = row * stride + column * BGR_CHANNELS;
        pixels.put(index, blue);
        pixels.put(index + 1, green);
        pixels.put(index + 2, red);
      }
    }

    return frame;
  }

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

  private static List<Frame> invalidFrames() {
    final Frame withoutImage = new Frame();
    final Frame withoutPlanes = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    withoutPlanes.image = new Buffer[0];
    final Frame sixteenBit = new Frame(FRAME_WIDTH, FRAME_HEIGHT, Frame.DEPTH_USHORT, BGR_CHANNELS);
    final Frame withoutWidth = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    withoutWidth.imageWidth = 0;
    final Frame withoutHeight = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    withoutHeight.imageHeight = 0;
    final Frame shortRows = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    shortRows.imageStride = 3;
    final Frame truncated = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    truncated.image[0] = ByteBuffer.allocateDirect(10);
    final Frame lastRowCut = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    final int lastRowEnd = lastRowCut.imageStride + FRAME_WIDTH * BGR_CHANNELS;
    lastRowCut.image[0] = ByteBuffer.allocateDirect(lastRowEnd - 1);
    return List.of(withoutImage, withoutPlanes, sixteenBit, withoutWidth, withoutHeight, shortRows, truncated, lastRowCut);
  }

  private void attachSize(final int width, final int height) {
    final Dimension size = Dimension.of(width, height);
    this.dimension.attach(size);
  }

  @Test
  void shouldCopyFramesIntoImagesOfTheirSize() {
    final Frame frame = solidFrame(3, SOLID_BLUE, SOLID_GREEN, SOLID_RED);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int[] expected = new int[6];
    Arrays.fill(expected, SOLID_ARGB);

    assertEquals(3, width);
    assertEquals(FRAME_HEIGHT, height);
    assertArrayEquals(expected, pixels);

    image.release();
  }

  @Test
  void shouldReuseAnImageHandedBackToThePoolAndRefreshItsPixels() {
    final Frame black = solidFrame(NARROW_FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    final Frame white = solidFrame(NARROW_FRAME_WIDTH, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
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
  void shouldResizePooledImagesOfAnotherSizeInPlace() {
    final Frame wide = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    final Frame small = solidFrame(NARROW_FRAME_WIDTH, SOLID_BLUE, SOLID_GREEN, SOLID_RED);
    final MatImageBuffer wideImage = this.copier.copy(wide);
    this.pool.recycle(wideImage);
    final MatImageBuffer smallImage = this.copier.copy(small);
    final int width = smallImage.getWidth();
    final int[] pixels = smallImage.getPixels();
    final int[] expected = new int[4];
    Arrays.fill(expected, SOLID_ARGB);

    assertSame(wideImage, smallImage, "the pooled image is resized instead of being replaced");
    assertEquals(NARROW_FRAME_WIDTH, width);
    assertArrayEquals(expected, pixels);

    smallImage.release();
  }

  @Test
  void shouldAverageAllSourcePixelsWhenShrinkingEitherAxis() {
    for (final Axis axis : Axis.values()) {
      this.assertShrinkingAveragesAllSourcePixels(axis);
    }
  }

  private void assertShrinkingAveragesAllSourcePixels(final Axis axis) {
    final boolean horizontal = axis == Axis.HORIZONTAL;
    final int width = horizontal ? 3 : 1;
    final int height = horizontal ? 1 : 3;
    try (final Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, BGR_CHANNELS)) {
      final ByteBuffer data = (ByteBuffer) frame.image[0];
      for (int index = 0; index < data.capacity(); index++) {
        data.put(index, (byte) 0);
      }

      final int middle = horizontal ? BGR_CHANNELS : frame.imageStride;
      data.put(middle, (byte) 90);
      this.attachSize(1, 1);
      try (final MatImageBuffer image = this.copier.copy(frame)) {
        final int[] actual = image.getPixels();
        final int[] expected = new int[] { 0xFF00001E };
        assertArrayEquals(expected, actual);
      }
    }
  }

  @Test
  void shouldInterpolateGrowingFramesAndRefreshTheReusedPixelCache() {
    for (final Axis axis : Axis.values()) {
      this.assertGrowingInterpolatesAndRefreshesThePixelCache(axis);
    }
  }

  private void assertGrowingInterpolatesAndRefreshesThePixelCache(final Axis axis) {
    final boolean horizontal = axis == Axis.HORIZONTAL;
    final int width = horizontal ? 2 : 1;
    final int height = horizontal ? 1 : 2;
    try (final Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, BGR_CHANNELS)) {
      final ByteBuffer data = (ByteBuffer) frame.image[0];
      for (int index = 0; index < data.capacity(); index++) {
        data.put(index, (byte) 0);
      }

      final int secondPixel = horizontal ? BGR_CHANNELS : frame.imageStride;
      data.put(secondPixel, (byte) 80);
      this.attachSize(horizontal ? 4 : 1, horizontal ? 1 : 4);
      final MatImageBuffer first = this.copier.copy(frame);
      final int[] actual = first.getPixels();
      final int[] expected = new int[] { 0xFF000000, 0xFF000014, 0xFF00003C, 0xFF000050 };
      assertArrayEquals(expected, actual);

      this.pool.recycle(first);
      data.put(secondPixel, (byte) 0);
      try (final MatImageBuffer second = this.copier.copy(frame)) {
        final int[] refreshed = second.getPixels();
        assertSame(first, second);
        final int[] expectedRefreshed = new int[] { 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000 };
        assertArrayEquals(expectedRefreshed, refreshed);
      }
    }
  }

  @Test
  void shouldShrinkFramesThatDoNotHaveTheAttachedSize() {
    this.attachSize(SHRUNK_WIDTH, 1);
    final Frame frame = solidFrame(FRAME_WIDTH, SOLID_BLUE, SOLID_GREEN, SOLID_RED);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final boolean copied = this.copier.hasUnscaledCopy();

    assertEquals(SHRUNK_WIDTH, width);
    assertEquals(1, height);
    final int[] expected = new int[] { SOLID_ARGB, SOLID_ARGB };
    assertArrayEquals(expected, pixels, "area averaging keeps a solid color");
    assertFalse(copied, "frames in native memory are scaled straight from the memory of the decoder");

    this.pool.recycle(image);
    final MatImageBuffer again = this.copier.copy(frame);
    assertSame(image, again, "the scaled image is reused as well");

    again.release();
  }

  @Test
  void shouldEnlargeFramesThatAreSmallerThanTheAttachedSize() {
    this.attachSize(8, 4);
    final Frame frame = solidFrame(FRAME_WIDTH, SOLID_BLUE, SOLID_GREEN, SOLID_RED);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int corner = pixels[31];

    assertEquals(8, width);
    assertEquals(4, height);
    assertEquals(SOLID_ARGB, corner);

    image.release();
  }

  @Test
  void shouldScaleFramesThatOnlyDifferInHeight() {
    final Frame frame = solidFrame(FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0x40);
    final MatImageBuffer unscaled = this.copier.copy(frame);
    this.pool.recycle(unscaled);
    this.attachSize(FRAME_WIDTH, 1);
    final MatImageBuffer scaled = this.copier.copy(frame);
    final int width = scaled.getWidth();
    final int height = scaled.getHeight();
    final int[] pixels = scaled.getPixels();
    final int first = pixels[0];

    assertEquals(FRAME_WIDTH, width);
    assertEquals(1, height);
    assertEquals(0xFF400000, first);
    assertSame(unscaled, scaled, "the pooled image of the old height is resized in place");

    scaled.release();
  }

  @Test
  void shouldCopyFramesThatAlreadyHaveTheAttachedSizeWithoutScaling() {
    this.attachSize(FRAME_WIDTH, FRAME_HEIGHT);
    final Frame frame = solidFrame(FRAME_WIDTH, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer image = this.copier.copy(frame);
    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int first = pixels[0];

    assertEquals(FRAME_WIDTH, width);
    assertEquals(0xFF030201, first);

    image.release();
  }

  @Test
  void shouldCopyFramesAtTheirOwnSizeWhenTheAttachedSizeIsEmpty() {
    final DimensionAttachableCallback detachedMidFrame = Mockito.mock(DimensionAttachableCallback.class);
    Mockito.when(detachedMidFrame.isAttached()).thenReturn(true);
    Mockito.when(detachedMidFrame.retrieve()).thenReturn(Dimension.NONE);
    final VideoFrameCopier racingCopier = new VideoFrameCopier(this.pool, detachedMidFrame);

    final Frame frame = solidFrame(FRAME_WIDTH, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer image = racingCopier.copy(frame);
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int first = pixels[0];

    assertEquals(FRAME_WIDTH, width);
    assertEquals(FRAME_HEIGHT, height);
    assertEquals(0xFF030201, first);

    image.release();
    racingCopier.release();
  }

  @Test
  void shouldHandThePooledImageBackWhenAFrameCannotBeCopied() {
    final Frame usable = solidFrame(FRAME_WIDTH, (byte) 1, (byte) 2, (byte) 3);
    final MatImageBuffer pooled = this.copier.copy(usable);
    this.pool.recycle(pooled);
    final int unusedBefore = this.pool.getUnusedCount();
    assertEquals(1, unusedBefore);

    final Frame broken = new Frame(FRAME_WIDTH, FRAME_HEIGHT, Frame.DEPTH_UBYTE, BGR_CHANNELS);
    broken.image = new Buffer[0];
    assertThrows(RuntimeException.class, () -> this.copier.copy(broken));

    final int unusedAfter = this.pool.getUnusedCount();
    assertEquals(1, unusedAfter, "a frame that cannot be copied must not cost the pool one of its images");
  }

  @Test
  void shouldScaleExactlyPackedNativeRowsWithoutAllocatingAnUnscaledCopy() {
    this.attachSize(1, 1);
    try (final Frame frame = new Frame()) {
      frame.imageWidth = 2;
      frame.imageHeight = 2;
      frame.imageDepth = Frame.DEPTH_UBYTE;
      frame.imageChannels = BGR_CHANNELS;
      frame.imageStride = 6;
      final ByteBuffer pixels = ByteBuffer.allocateDirect(12);
      for (int index = 0; index < 12; index++) {
        pixels.put(index, (byte) 32);
      }

      frame.image = new Buffer[] { pixels };
      try (final MatImageBuffer image = this.copier.copy(frame)) {
        final int[] actual = image.getPixels();
        final boolean extraCopy = this.copier.hasUnscaledCopy();
        final int[] expected = new int[] { 0xFF202020 };
        assertArrayEquals(expected, actual);
        assertFalse(extraCopy, "a valid native plane with exact row and capacity bounds scales directly");
      }
    }
  }

  @Test
  void shouldScaleFramesOutsideOfNativeMemoryThroughACopy() {
    this.attachSize(SHRUNK_WIDTH, 1);
    final Frame frame = heapFrame(FRAME_WIDTH, SOLID_BLUE, SOLID_GREEN, SOLID_RED);
    final MatImageBuffer image = this.copier.copy(frame);
    final int[] pixels = image.getPixels();
    final boolean copied = this.copier.hasUnscaledCopy();

    final int[] expected = new int[] { SOLID_ARGB, SOLID_ARGB };
    assertArrayEquals(expected, pixels);
    assertTrue(copied, "OpenCV cannot read the heap, so the frame is copied into native memory first");

    image.release();
  }

  @Test
  void shouldRejectFramesWithoutUsableBgrPixelsWhileScaling() {
    this.attachSize(SHRUNK_WIDTH, 1);
    final List<Frame> frames = invalidFrames();
    for (final Frame frame : frames) {
      assertThrows(IllegalArgumentException.class, () -> this.copier.copy(frame));
    }
  }

  @Test
  void shouldReleaseTwiceHarmlessly() {
    this.attachSize(1, 1);
    final Frame frame = heapFrame(NARROW_FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
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

  @Test
  void shouldFreeTheUnscaledCopyOnReleaseRatherThanLeavingItToTheGarbageCollector() {
    this.attachSize(1, 1);
    final Frame frame = heapFrame(NARROW_FRAME_WIDTH, (byte) 0, (byte) 0, (byte) 0);
    final MatImageBuffer image = this.copier.copy(frame);
    final MatImageBuffer unscaled = this.copier.getUnscaledCopy();
    final int unscaledWidth = unscaled.getWidth();
    this.copier.release();

    assertEquals(NARROW_FRAME_WIDTH, unscaledWidth, "the copy has the size of the frame");
    assertThrows(IllegalStateException.class, unscaled::getWidth, "the copy was released");

    image.release();
  }

  private enum Axis {
    HORIZONTAL,
    VERTICAL,
  }
}

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.testing.Images;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

final class MatVideoFilterTest {

  private static final int BLACK = 0xFF000000;

  private static final int IMAGE_SIDE = 2;

  private static ImageBuffer foreignImage(final int width, final int height, final int argb, final WrittenPixels written) {
    final byte[] bgr = new byte[width * height * 3];
    for (int index = 0; index < bgr.length; index += 3) {
      bgr[index] = (byte) argb;
      bgr[index + 1] = (byte) (argb >> 8);
      bgr[index + 2] = (byte) (argb >> 16);
    }

    final ByteBuffer data = ByteBuffer.wrap(bgr);
    final ImageBuffer image = mock(ImageBuffer.class);
    when(image.getWidth()).thenReturn(width);
    when(image.getHeight()).thenReturn(height);
    when(image.getData()).thenReturn(data);
    doAnswer(written::record).when(image).updateData(any(), anyInt(), anyInt());
    return image;
  }

  @Test
  void shouldCopyResultsBackIntoImagesThatAreNotBackedByAMat() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(1, 1, BLACK, written);
    final InvertFilter filter = new InvertFilter();
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    final byte[] bytes = written.getBytes();
    final byte[] expected = new byte[] { -1, -1, -1 };
    assertTrue(modified);
    assertArrayEquals(expected, bytes, "the inverted black pixel is written back as white BGR");
    verify(image).updateData(any(), anyInt(), anyInt());
    verify(image, never()).setAsBufferedImage(any());
    verify(image, never()).updateArgb(any(), anyInt(), anyInt());
  }

  @Test
  void shouldWriteResultsOfAnotherSizeBackWithTheirSize() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(IMAGE_SIDE, IMAGE_SIDE, 0xFF102030, written);
    final CropFilter filter = new CropFilter(1, 0, 1, 1);
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    final byte[] bytes = written.getBytes();
    final byte[] expected = new byte[] { 0x30, 0x20, 0x10 };
    assertTrue(modified);
    assertArrayEquals(expected, bytes, "one BGR pixel remains");
    verify(image).updateData(any(), eq(1), eq(1));
  }

  @Test
  void shouldLeaveForeignImagesAloneWhenNothingChanged() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(1, 1, BLACK, written);
    final CropFilter filter = new CropFilter(0, 0, 1, 1);
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    assertFalse(modified);
    verify(image, never()).updateData(any(), anyInt(), anyInt());
    verify(image, never()).setAsBufferedImage(any());
  }

  @Test
  void shouldKeepTheCacheWhenTheMatWasNotModified() {
    try (final ImageBuffer image = Images.solid(IMAGE_SIDE, IMAGE_SIDE, 0xFF123456)) {
      final int[] before = image.getPixels();
      final UnchangedFilter filter = new UnchangedFilter();
      final boolean modified = filter.applyFilter(image);
      final int[] after = image.getPixels();
      assertFalse(modified);
      assertSame(before, after);
    }
  }

  @Test
  void shouldUseEmptyMetadataInTheSingleArgumentApply() {
    final AtomicReference<OriginalVideoMetadata> received = new AtomicReference<>();
    final VideoFilter recorder = (_, metadata) -> this.recordMetadata(received, metadata);
    try (final ImageBuffer image = Images.indexed(IMAGE_SIDE, IMAGE_SIDE)) {
      final boolean changed = recorder.applyFilter(image);
      final OriginalVideoMetadata metadata = received.get();
      assertTrue(changed, "the single-argument apply returns the result of the filter");
      assertSame(OriginalVideoMetadata.EMPTY, metadata);
    }
  }

  @Test
  void shouldLeaveTheFrameUntouchedWithTheNoOperationFilter() {
    try (final ImageBuffer image = Images.indexed(IMAGE_SIDE, IMAGE_SIDE)) {
      final ByteBuffer before = ByteBuffer.allocate(image.getData().remaining()).put(image.getData()).flip();
      final boolean changed = VideoFilter.NO_OPERATION.applyFilter(image);
      final ByteBuffer data = image.getData();
      final int width = image.getWidth();
      final int height = image.getHeight();
      assertFalse(changed, "the no-operation filter leaves every frame untouched");
      assertEquals(before, data);
      assertEquals(IMAGE_SIDE, width);
      assertEquals(IMAGE_SIDE, height);
    }
  }

  private boolean recordMetadata(final AtomicReference<OriginalVideoMetadata> received, final OriginalVideoMetadata metadata) {
    received.set(metadata);

    return true;
  }

  @Test
  void shouldRejectMissingImages() {
    final InvertFilter filter = new InvertFilter();
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, OriginalVideoMetadata.EMPTY));
    assertThrows(NullPointerException.class, () -> VideoFilter.NO_OPERATION.applyFilter(null));
  }

  @Test
  void shouldRejectAReleasedFrame() {
    final ImageBuffer image = Images.solid(IMAGE_SIDE, IMAGE_SIDE, BLACK);
    image.close();

    final InvertFilter inPlace = new InvertFilter();
    final CropFilter cropping = new CropFilter(0, 0, 1, 1);
    final TransposeFilter transposing = new TransposeFilter();
    assertThrows(IllegalStateException.class, () -> inPlace.applyFilter(image));
    assertThrows(IllegalStateException.class, () -> cropping.applyFilter(image));
    assertThrows(IllegalStateException.class, () -> transposing.applyFilter(image));
  }

  // Copies the pixels written into a foreign image during the call, as their buffer belongs to a temporary image released afterward.
  private static final class WrittenPixels {

    private byte[] bytes = new byte[0];

    // The answer's result is ignored, because the update returns nothing.
    private Object record(final InvocationOnMock invocation) {
      final ByteBuffer data = invocation.getArgument(0);
      final ByteBuffer view = data.duplicate();
      final byte[] copy = new byte[view.remaining()];
      view.get(copy);
      this.bytes = copy;

      return invocation;
    }

    private byte[] getBytes() {
      return Arrays.copyOf(this.bytes, this.bytes.length);
    }
  }

  // A filter that never changes anything.
  private static final class UnchangedFilter extends MatVideoFilter {

    @Override
    protected boolean modifyMat(final Mat matrix) {
      return false;
    }
  }
}

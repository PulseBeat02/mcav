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

/**
 * Tests {@link MatVideoFilter} and the defaults of {@link VideoFilter}.
 */
final class MatVideoFilterTest {

  /**
   * Creates an image that is not backed by a matrix and whose pixels all have the same color, and records what the
   * filter writes back into it.
   */
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
  void copiesResultsBackIntoImagesThatAreNotBackedByAMat() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(1, 1, 0xFF000000, written);
    final InvertFilter filter = new InvertFilter();
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    final byte[] bytes = written.getBytes();
    assertTrue(modified);
    assertArrayEquals(new byte[] { -1, -1, -1 }, bytes, "the inverted black pixel is written back as white BGR");
    verify(image).updateData(any(), anyInt(), anyInt());
    verify(image, never()).setAsBufferedImage(any());
    verify(image, never()).updateArgb(any(), anyInt(), anyInt());
  }

  @Test
  void writesResultsOfAnotherSizeBackWithTheirSize() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(2, 2, 0xFF102030, written);
    final CropFilter filter = new CropFilter(1, 0, 1, 1);
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    final byte[] bytes = written.getBytes();
    assertTrue(modified);
    assertArrayEquals(new byte[] { 0x30, 0x20, 0x10 }, bytes, "one BGR pixel remains");
    verify(image).updateData(any(), eq(1), eq(1));
  }

  @Test
  void leavesForeignImagesAloneWhenNothingChanged() {
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(1, 1, 0xFF000000, written);
    final CropFilter filter = new CropFilter(0, 0, 1, 1);
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    assertFalse(modified);
    verify(image, never()).updateData(any(), anyInt(), anyInt());
    verify(image, never()).setAsBufferedImage(any());
  }

  @Test
  void keepsTheCacheWhenTheMatWasNotModified() {
    try (final ImageBuffer image = Images.solid(2, 2, 0xFF123456)) {
      final int[] before = image.getPixels();
      final UnchangedFilter filter = new UnchangedFilter();
      final boolean modified = filter.applyFilter(image);
      final int[] after = image.getPixels();
      assertFalse(modified);
      assertSame(before, after);
    }
  }

  @Test
  void singleArgumentApplyUsesEmptyMetadata() {
    final AtomicReference<OriginalVideoMetadata> received = new AtomicReference<>();
    final VideoFilter recorder = (_, metadata) -> {
      received.set(metadata);
      return true;
    };
    final WrittenPixels written = new WrittenPixels();
    final ImageBuffer image = foreignImage(1, 1, 0, written);
    final boolean result = recorder.applyFilter(image);
    final OriginalVideoMetadata metadata = received.get();
    final boolean noOp = VideoFilter.NO_OP.applyFilter(image);
    assertTrue(result);
    assertFalse(noOp, "the no-op filter leaves every frame untouched");
    assertSame(OriginalVideoMetadata.EMPTY, metadata);
  }

  @Test
  void rejectsMissingImages() {
    final InvertFilter filter = new InvertFilter();
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, OriginalVideoMetadata.EMPTY));
    assertThrows(NullPointerException.class, () -> VideoFilter.NO_OP.applyFilter(null));
  }

  /**
   * Keeps a copy of the pixels written back into a foreign image, taken while the call runs, because the buffer
   * belongs to a temporary image that is released afterward.
   */
  private static final class WrittenPixels {

    private byte[] bytes = new byte[0];

    /**
     * Copies the pixels an update call receives; the result of the answer is ignored, because the update returns
     * nothing.
     */
    private Object record(final InvocationOnMock invocation) {
      final ByteBuffer data = invocation.getArgument(0);
      final ByteBuffer view = data.duplicate();
      final byte[] copy = new byte[view.remaining()];
      view.get(copy);
      this.bytes = copy;
      return invocation;
    }

    byte[] getBytes() {
      return Arrays.copyOf(this.bytes, this.bytes.length);
    }
  }

  /**
   * A filter that never changes anything.
   */
  private static final class UnchangedFilter extends MatVideoFilter {

    @Override
    protected boolean modifyMat(final Mat mat) {
      return false;
    }
  }
}

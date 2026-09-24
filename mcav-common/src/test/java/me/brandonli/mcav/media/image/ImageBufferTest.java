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
package me.brandonli.mcav.media.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.IntBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

/**
 * Tests the pixel accessors that {@link ImageBuffer} provides on top of the shared {@link ImageBuffer#getPixels()}
 * array: the copying {@link ImageBuffer#copyPixels()} and the read-only {@link ImageBuffer#getReadOnlyPixels()}.
 */
final class ImageBufferTest {

  private static final int[] PIXELS = { 0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC };
  private static final int SIDE = 2;

  @Test
  void closeInvalidatesTheOwnedImage() {
    final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE);
    try {
      image.close();
      assertThrows(IllegalStateException.class, image::getPixels);
    } finally {
      image.release();
    }
  }

  @Test
  void copiesThePixelsIntoAnArrayTheCallerOwns() {
    try (final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE)) {
      final int[] shared = image.getPixels();
      final int[] copy = image.copyPixels();
      assertNotSame(shared, copy);
      assertArrayEquals(PIXELS, copy);

      copy[0] = 0xFF000000;
      final int[] sharedAfterWrite = image.getPixels();
      assertSame(shared, sharedAfterWrite, "writing the copy leaves the cache in place");
      assertArrayEquals(PIXELS, sharedAfterWrite, "writing the copy does not reach the shared pixels");
    }
  }

  @Test
  void copiesThePixelsAgainOnEveryCall() {
    try (final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE)) {
      final int[] first = image.copyPixels();
      final int[] second = image.copyPixels();
      assertNotSame(first, second);
      assertArrayEquals(first, second);
    }
  }

  @Test
  void copiesThePixelsOfTheCurrentImage() {
    try (final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE)) {
      final int[] before = image.copyPixels();
      final int[] replacement = { 0xFF010203, 0xFF040506, 0xFF070809, 0xFF0A0B0C };
      image.updateArgb(replacement, SIDE, SIDE);
      final int[] after = image.copyPixels();
      assertArrayEquals(PIXELS, before, "an earlier copy keeps the old pixels");
      assertArrayEquals(replacement, after);
    }
  }

  @Test
  void viewsThePixelsWithoutAllowingWrites() {
    try (final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE)) {
      final IntBuffer view = image.getReadOnlyPixels();
      final boolean readOnly = view.isReadOnly();
      final int position = view.position();
      final int limit = view.limit();
      final int pixelCount = image.getPixelCount();
      assertTrue(readOnly);
      assertEquals(0, position);
      assertEquals(pixelCount, limit);

      final int[] read = new int[PIXELS.length];
      view.get(read);
      assertArrayEquals(PIXELS, read);
      assertThrows(ReadOnlyBufferException.class, () -> view.put(0, 0xFF000000));
      final int[] shared = image.getPixels();
      assertArrayEquals(PIXELS, shared, "a rejected write leaves the shared pixels intact");
    }
  }

  @Test
  void viewsTheSharedArrayInsteadOfCopyingIt() {
    final int[] shared = PIXELS.clone();
    try (final ImageBuffer image = mock(ImageBuffer.class)) {
      when(image.getPixels()).thenReturn(shared);
      when(image.getReadOnlyPixels()).thenCallRealMethod();
      final IntBuffer view = image.getReadOnlyPixels();

      shared[1] = 0xFF000000;
      final int seen = view.get(1);
      assertEquals(0xFF000000, seen, "the view reads the shared array itself, so building it copies nothing");
    }
  }

  @Test
  void createsAnIndependentViewOnEveryCall() {
    try (final ImageBuffer image = ImageBuffer.buffer(PIXELS, SIDE, SIDE)) {
      final IntBuffer first = image.getReadOnlyPixels();
      final IntBuffer second = image.getReadOnlyPixels();
      first.get();
      final int firstPosition = first.position();
      final int secondPosition = second.position();
      assertNotSame(first, second);
      assertEquals(1, firstPosition);
      assertEquals(0, secondPosition, "reading one view does not move another");
    }
  }
}

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
package me.brandonli.mcav.media.source.frame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FrameSource}, {@link FrameSourceImpl}, {@link ImageSupplier} and {@link SampleSupplier}.
 */
final class FrameSourceTest {

  @Test
  void describesTheFrames() {
    final SampleSupplier supplier = () -> new int[16 * 8];
    final FrameSource source = FrameSource.supplier(supplier, 16, 8, 50.0f);
    final SampleSupplier storedSupplier = source.supplyFrameSamples();
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final float frameRate = source.getFrameRate();
    final String resource = source.getResource();
    final String name = source.getName();
    final boolean isDynamic = source.isDynamic();
    assertSame(supplier, storedSupplier);
    assertEquals(16, width);
    assertEquals(8, height);
    assertEquals(50.0f, frameRate);
    assertEquals("16x8@50.0", resource);
    assertEquals("frame-source", name);
    assertTrue(isDynamic);
  }

  @Test
  void usesTheDefaultFrameRateWhenNoneIsGiven() {
    final SampleSupplier supplier = () -> new int[4];
    final FrameSource source = FrameSource.supplier(supplier, 2, 2);
    final float frameRate = source.getFrameRate();
    assertEquals(FrameSource.DEFAULT_FRAME_RATE, frameRate);
  }

  @Test
  void interfaceDefaultsToTheDefaultFrameRate() {
    final FrameSource minimal = new MinimalFrameSource();
    final float frameRate = minimal.getFrameRate();
    final String resource = minimal.getResource();
    assertEquals(FrameSource.DEFAULT_FRAME_RATE, frameRate);
    assertEquals("1x1@30.0", resource);
  }

  @Test
  void readsThePixelsOfSuppliedImages() {
    final BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(3, 1, 0xFF123456);
    final FrameSource source = FrameSource.image(() -> image, 4, 2);
    final SampleSupplier supplier = source.supplyFrameSamples();
    final int[] pixels = supplier.getFrameSamples();
    final int[] expected = new int[8];
    expected[7] = 0xFF123456;
    final float frameRate = source.getFrameRate();
    assertArrayEquals(expected, pixels);
    assertEquals(FrameSource.DEFAULT_FRAME_RATE, frameRate);
  }

  private static int[] supply(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final FrameSource source = FrameSource.image(() -> image, width, height);
    final SampleSupplier supplier = source.supplyFrameSamples();
    return supplier.getFrameSamples();
  }

  private static int[] readWithGetRgb(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    return image.getRGB(0, 0, width, height, null, 0, width);
  }

  @Test
  void readsEveryImageTypeExactlyLikeGetRgb() {
    final int[] types = { BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR };
    for (final int type : types) {
      final BufferedImage image = new BufferedImage(3, 2, type);
      image.setRGB(0, 0, 0x80123456);
      image.setRGB(2, 1, 0xFFABCDEF);
      final int[] expected = readWithGetRgb(image);
      final int[] pixels = supply(image);
      assertArrayEquals(expected, pixels, "type " + type);
    }
  }

  @Test
  void makesIntegerRgbPixelsOpaqueWhateverTheirUnusedByteHolds() {
    final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    final WritableRaster raster = image.getRaster();
    raster.setDataElements(0, 0, new int[] { 0x12345678 });
    final int[] pixels = supply(image);
    final int[] expected = readWithGetRgb(image);
    assertArrayEquals(new int[] { 0xFF345678 }, pixels);
    assertArrayEquals(expected, pixels);
  }

  @Test
  void readsOnlyThePixelsOfSubImages() {
    final int[] types = { BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB };
    for (final int type : types) {
      final BufferedImage parent = new BufferedImage(4, 4, type);
      parent.setRGB(1, 1, 0xFFFF0000);
      parent.setRGB(2, 2, 0xFF00FF00);
      parent.setRGB(3, 3, 0xFF0000FF);
      final BufferedImage child = parent.getSubimage(1, 1, 2, 2);
      final int[] expected = readWithGetRgb(child);
      final int[] pixels = supply(child);
      assertArrayEquals(expected, pixels, "type " + type);
    }
  }

  @Test
  void rejectsInvalidArguments() {
    final SampleSupplier supplier = () -> new int[1];
    assertThrows(IllegalArgumentException.class, () -> FrameSource.supplier(supplier, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> FrameSource.supplier(supplier, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> FrameSource.supplier(supplier, 1, 1, 0.0f));
    assertThrows(IllegalArgumentException.class, () -> FrameSource.supplier(supplier, 1, 1, Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> FrameSource.supplier(supplier, 1, 1, Float.POSITIVE_INFINITY));
    assertThrows(NullPointerException.class, () -> FrameSource.supplier(null, 1, 1));
    assertThrows(NullPointerException.class, () -> FrameSource.image(null, 1, 1));
  }

  /**
   * A frame source that relies on every default method of the interface.
   */
  private static final class MinimalFrameSource implements FrameSource {

    @Override
    public SampleSupplier supplyFrameSamples() {
      return () -> new int[1];
    }

    @Override
    public int getFrameWidth() {
      return 1;
    }

    @Override
    public int getFrameHeight() {
      return 1;
    }
  }
}

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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;

final class ImageBufferPropertyTest {

  private static final String SEED = "20260925";
  private static final int MAXIMUM_SIDE = 24;
  private static final int CHANNELS = 3;

  private static final String IMAGES_PROVIDER = "images";
  private static final int TRIES = 100;

  private static final int OPAQUE_ALPHA = 0xFF000000;
  private static final int CHANNEL_MASK = 0xFF;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  @Provide
  Arbitrary<ImageCase> images() {
    final Arbitrary<Integer> sides = between(1, MAXIMUM_SIDE);
    final Arbitrary<Integer> margins = between(0, 9);
    final Arbitrary<BufferKind> kinds = Arbitraries.of(BufferKind.DIRECT, BufferKind.HEAP);
    final Arbitrary<ByteOrder> orders = Arbitraries.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN);
    final IntegerArbitrary colors = Arbitraries.integers();
    final ListArbitrary<Integer> colorList = colors.list();
    final ListArbitrary<Integer> pixels = colorList.ofSize(MAXIMUM_SIDE * MAXIMUM_SIDE);
    final Combinators.Combinator7<Integer, Integer, Integer, Integer, BufferKind, ByteOrder, List<Integer>> images = Combinators.combine(
      sides,
      sides,
      margins,
      margins,
      kinds,
      orders,
      pixels
    );
    return images.as(ImageCase::new);
  }

  // Creates integers in a range from a fresh arbitrary: jqwik 1.10 shares the range between an arbitrary and the ones
  // configured from it, so one base configured twice would hand every user the last range.
  private static Arbitrary<Integer> between(final int minimum, final int maximum) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(minimum, maximum);
  }

  @Property(seed = SEED, tries = TRIES)
  void shouldCopyRawPixelsFromEveryBufferLayout(@ForAll(IMAGES_PROVIDER) final ImageCase image) {
    final byte[] bgr = image.createBgr();
    final ByteBuffer source = image.wrap(bgr);
    final int position = source.position();
    final int limit = source.limit();
    final ImageBuffer buffer = ImageBuffer.bytes(source, image.getWidth(), image.getHeight());
    try {
      final int sourcePosition = source.position();
      final int sourceLimit = source.limit();
      assertEquals(position, sourcePosition, "the position of the source is left alone");
      assertEquals(limit, sourceLimit, "the limit of the source is left alone");

      final byte[] held = readData(buffer);
      assertArrayEquals(bgr, held, "the image holds the bytes between position and limit");

      for (int index = position; index < limit; index++) {
        final byte current = source.get(index);
        source.put(index, (byte) ~current);
      }

      final byte[] stillHeld = readData(buffer);
      assertArrayEquals(bgr, stillHeld, "the image does not share memory with the source");
    } finally {
      buffer.release();
    }
  }

  @Property(seed = SEED, tries = TRIES)
  void shouldReturnArgbPixelsOpaqueAndAsTheirBgrBytes(@ForAll(IMAGES_PROVIDER) final ImageCase image) {
    final int[] argb = image.createArgb();
    final ImageBuffer buffer = ImageBuffer.buffer(argb, image.getWidth(), image.getHeight());
    try {
      final int[] pixels = buffer.getPixels();
      final byte[] bgr = readData(buffer);
      assertEquals(argb.length, pixels.length, "one pixel per pixel");

      for (int pixel = 0; pixel < argb.length; pixel++) {
        final int expected = argb[pixel] | OPAQUE_ALPHA;
        final int opaquePixel = pixels[pixel];
        assertEquals(expected, opaquePixel, "pixel %s is its color, opaque".formatted(pixel));
        final int blue = bgr[pixel * CHANNELS] & CHANNEL_MASK;
        final int green = bgr[pixel * CHANNELS + 1] & CHANNEL_MASK;
        final int red = bgr[pixel * CHANNELS + 2] & CHANNEL_MASK;
        final int storedColor = (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
        final int expectedStoredColor = expected & 0xFFFFFF;
        assertEquals(expectedStoredColor, storedColor, "pixel %s as blue, green, red".formatted(pixel));
      }
    } finally {
      buffer.release();
    }
  }

  @Property(seed = SEED, tries = TRIES)
  void shouldShareNothingWithCopiesAndReplaceTheCachedPixelsOnChange(@ForAll(IMAGES_PROVIDER) final ImageCase image) {
    final int[] argb = image.createArgb();
    final int width = image.getWidth();
    final int height = image.getHeight();
    final ImageBuffer original = ImageBuffer.buffer(argb, width, height);
    final ImageBuffer copy = original.copy();
    try {
      final int[] before = original.getPixels();
      final int[] beforeValues = before.clone();
      final int[] owned = original.copyPixels();
      owned[0] = ~owned[0];
      final int[] afterWritingTheCopy = original.getPixels();
      assertArrayEquals(beforeValues, afterWritingTheCopy, "copyPixels belongs to the caller");

      final int[] changed = new int[argb.length];
      for (int pixel = 0; pixel < changed.length; pixel++) {
        changed[pixel] = ~argb[pixel];
      }

      original.updateArgb(changed, width, height);

      final int[] copyPixels = copy.getPixels();
      assertArrayEquals(beforeValues, copyPixels, "a copy does not change with its original");
      assertArrayEquals(beforeValues, before, "the array a caller kept still holds the old pixels");

      final int[] after = original.getPixels();
      for (int pixel = 0; pixel < changed.length; pixel++) {
        final int expected = changed[pixel] | OPAQUE_ALPHA;
        final int afterPixel = after[pixel];
        assertEquals(expected, afterPixel, "the image shows its new pixels");
      }
    } finally {
      copy.release();
      original.release();
    }
  }

  @Property(seed = SEED, tries = TRIES)
  void shouldChangeTheImageOnWritesThroughTheDataView(@ForAll(IMAGES_PROVIDER) final ImageCase image) {
    final int[] argb = image.createArgb();
    final ImageBuffer buffer = ImageBuffer.buffer(argb, image.getWidth(), image.getHeight());
    try {
      final ByteBuffer data = buffer.getData();
      final int start = data.position();
      final byte[] written = image.createBgr();
      for (int index = 0; index < written.length; index++) {
        written[index] = (byte) ~written[index];
        data.put(start + index, written[index]);
      }

      buffer.invalidateCache();

      final int[] pixels = buffer.getPixels();
      for (int pixel = 0; pixel < pixels.length; pixel++) {
        final int blue = written[pixel * CHANNELS] & CHANNEL_MASK;
        final int green = written[pixel * CHANNELS + 1] & CHANNEL_MASK;
        final int red = written[pixel * CHANNELS + 2] & CHANNEL_MASK;
        final int expected = OPAQUE_ALPHA | (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
        final int shownPixel = pixels[pixel];
        assertEquals(expected, shownPixel, "pixel %s shows what was written through the view".formatted(pixel));
      }
    } finally {
      buffer.release();
    }
  }

  private static byte[] readData(final ImageBuffer buffer) {
    final ByteBuffer data = buffer.getData();
    final ByteBuffer view = data.duplicate();
    final byte[] bytes = new byte[view.remaining()];
    view.get(bytes);
    return bytes;
  }

  private static final class ImageCase {

    private final int width;
    private final int height;
    private final int before;
    private final int after;
    private final BufferKind kind;
    private final ByteOrder order;
    private final List<Integer> colors;

    ImageCase(
      final int width,
      final int height,
      final int before,
      final int after,
      final BufferKind kind,
      final ByteOrder order,
      final List<Integer> colors
    ) {
      this.width = width;
      this.height = height;
      this.before = before;
      this.after = after;
      this.kind = kind;
      this.order = order;
      this.colors = colors;
    }

    private int[] createArgb() {
      final int[] argb = new int[this.width * this.height];
      for (int pixel = 0; pixel < argb.length; pixel++) {
        argb[pixel] = this.colors.get(pixel);
      }

      return argb;
    }

    private byte[] createBgr() {
      final int[] argb = this.createArgb();
      final byte[] bgr = new byte[argb.length * CHANNELS];
      for (int pixel = 0; pixel < argb.length; pixel++) {
        bgr[pixel * CHANNELS] = (byte) argb[pixel];
        bgr[pixel * CHANNELS + 1] = (byte) (argb[pixel] >> GREEN_SHIFT);
        bgr[pixel * CHANNELS + 2] = (byte) (argb[pixel] >> RED_SHIFT);
      }

      return bgr;
    }

    ByteBuffer wrap(final byte[] bytes) {
      final int capacity = this.before + bytes.length + this.after;
      final ByteBuffer buffer = this.kind == BufferKind.DIRECT ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
      buffer.order(this.order);
      for (int index = 0; index < capacity; index++) {
        buffer.put(index, (byte) 0x77);
      }

      buffer.put(this.before, bytes);
      buffer.position(this.before);
      buffer.limit(this.before + bytes.length);
      return buffer;
    }

    int getWidth() {
      return this.width;
    }

    int getHeight() {
      return this.height;
    }

    @Override
    public String toString() {
      final String kind = this.kind == BufferKind.DIRECT ? "direct" : "heap";
      return "%sx%s, %s %s, %s before, %s after".formatted(this.width, this.height, kind, this.order, this.before, this.after);
    }
  }

  private enum BufferKind {
    DIRECT,
    HEAP,
  }
}

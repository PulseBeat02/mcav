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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import me.brandonli.mcav.media.image.DynamicImageBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RepeatingFrameSource} and {@link RepeatingFrameSourceImpl}.
 */
final class RepeatingFrameSourceTest {

  private static ImageBuffer frame(final int color) {
    final ImageBuffer frame = mock(ImageBuffer.class);
    when(frame.getWidth()).thenReturn(2);
    when(frame.getHeight()).thenReturn(1);
    when(frame.getPixels()).thenReturn(new int[] { color, color });
    return frame;
  }

  private static DynamicImageBuffer animation(final List<ImageBuffer> frames) {
    final DynamicImageBuffer animation = mock(DynamicImageBuffer.class);
    when(animation.getFrames()).thenReturn(frames);
    when(animation.getFrameRate()).thenReturn(12.5f);
    return animation;
  }

  private static int nextColor(final SampleSupplier supplier) {
    final int[] samples = supplier.getFrameSamples();
    return samples[0];
  }

  @Test
  void describesTheAnimation() {
    final ImageBuffer first = frame(1);
    final List<ImageBuffer> frames = List.of(first);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, 3);
    final int repeatCount = source.getRepeatCount();
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final float frameRate = source.getFrameRate();
    assertEquals(3, repeatCount);
    assertEquals(2, width);
    assertEquals(1, height);
    assertEquals(12.5f, frameRate);
  }

  @Test
  void playsTheFramesTheRequestedNumberOfTimesThenHoldsTheLastFrame() {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(2);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, 2);
    final SampleSupplier supplier = source.supplyFrameSamples();
    final int[] colors = new int[7];
    for (int index = 0; index < colors.length; index++) {
      colors[index] = nextColor(supplier);
    }
    assertArrayEquals(new int[] { 1, 2, 1, 2, 2, 2, 2 }, colors);
  }

  @Test
  void loopsForeverWithoutARepeatCount() {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(2);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation);
    final SampleSupplier supplier = source.supplyFrameSamples();
    final int repeatCount = source.getRepeatCount();
    final int[] colors = new int[6];
    for (int index = 0; index < colors.length; index++) {
      colors[index] = nextColor(supplier);
    }
    assertEquals(Integer.MAX_VALUE, repeatCount);
    assertArrayEquals(new int[] { 1, 2, 1, 2, 1, 2 }, colors);
  }

  @Test
  void handsOutTheCachedPixelsOfTheFramesWithoutCopying() {
    final ImageBuffer only = frame(1);
    final List<ImageBuffer> frames = List.of(only);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, 2);
    final SampleSupplier supplier = source.supplyFrameSamples();
    final int[] firstLoop = supplier.getFrameSamples();
    final int[] secondLoop = supplier.getFrameSamples();
    final int[] held = supplier.getFrameSamples();
    final int[] cached = only.getPixels();
    assertSame(cached, firstLoop, "the cached pixels of the frame are handed out as they are");
    assertSame(firstLoop, secondLoop, "every loop hands out the same array, which is why callers must not modify it");
    assertSame(firstLoop, held);
  }

  @Test
  void rejectsInvalidArguments() {
    final ImageBuffer first = frame(1);
    final List<ImageBuffer> frames = List.of(first);
    final List<ImageBuffer> noFrames = List.of();
    try (final DynamicImageBuffer animation = animation(frames); final DynamicImageBuffer emptyAnimation = animation(noFrames)) {
      assertThrows(IllegalArgumentException.class, () -> RepeatingFrameSource.repeating(animation, 0));
      assertThrows(IllegalArgumentException.class, () -> RepeatingFrameSource.repeating(emptyAnimation, 1));
    }

    assertThrows(NullPointerException.class, () -> RepeatingFrameSource.repeating(null, 1));
    assertThrows(NullPointerException.class, () -> RepeatingFrameSource.repeating(null));
  }
}

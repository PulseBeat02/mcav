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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.brandonli.mcav.media.image.DynamicImageBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.junit.jupiter.api.Test;

final class RepeatingFrameSourceTest {

  private static final int FRAME_WIDTH = 2;

  private static final float FRAME_RATE = 12.5f;

  private static final int SECOND_FRAME_COLOR = 2;

  private static final int REPEAT_COUNT = 2;

  private static final int CALLER_COUNT = 4;
  private static final int CALLS_PER_CALLER = 5_000;
  private static final int CONCURRENT_REPEAT_COUNT = 9_000;

  private static ImageBuffer frame(final int color) {
    final ImageBuffer frame = mock(ImageBuffer.class);
    when(frame.getWidth()).thenReturn(FRAME_WIDTH);
    when(frame.getHeight()).thenReturn(1);
    when(frame.getPixels()).thenReturn(new int[] { color, color });
    return frame;
  }

  private static DynamicImageBuffer animation(final List<ImageBuffer> frames) {
    final DynamicImageBuffer animation = mock(DynamicImageBuffer.class);
    when(animation.getFrames()).thenReturn(frames);
    when(animation.getFrameRate()).thenReturn(FRAME_RATE);
    return animation;
  }

  private static int nextColor(final SampleSupplier supplier) {
    final int[] samples = supplier.getFrameSamples();
    return samples[0];
  }

  @Test
  void shouldDescribeTheAnimation() {
    final ImageBuffer first = frame(1);
    final List<ImageBuffer> frames = List.of(first);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, 3);
    final int repeatCount = source.getRepeatCount();
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final float frameRate = source.getFrameRate();
    assertEquals(3, repeatCount);
    assertEquals(FRAME_WIDTH, width);
    assertEquals(1, height);
    assertEquals(FRAME_RATE, frameRate);
  }

  @Test
  void shouldPlayTheFramesTheRequestedNumberOfTimesThenHoldTheLastFrame() {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(SECOND_FRAME_COLOR);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, REPEAT_COUNT);
    final SampleSupplier supplier = source.getSampleSupplier();
    final int[] colors = new int[7];
    for (int index = 0; index < colors.length; index++) {
      colors[index] = nextColor(supplier);
    }

    final int[] expected = new int[] { 1, 2, 1, 2, 2, 2, 2 };
    assertArrayEquals(expected, colors);
  }

  @Test
  void shouldLoopForeverWithoutARepeatCount() {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(SECOND_FRAME_COLOR);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation);
    final SampleSupplier supplier = source.getSampleSupplier();
    final int repeatCount = source.getRepeatCount();
    final int[] colors = new int[6];
    for (int index = 0; index < colors.length; index++) {
      colors[index] = nextColor(supplier);
    }

    final int[] expected = new int[] { 1, 2, 1, 2, 1, 2 };
    assertEquals(Integer.MAX_VALUE, repeatCount);
    assertArrayEquals(expected, colors);
  }

  @Test
  void shouldKeepOneCursorForEverySupplierOfTheSource() {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(SECOND_FRAME_COLOR);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation);
    final SampleSupplier earlier = source.getSampleSupplier();
    final int earlierColor = nextColor(earlier);
    final SampleSupplier later = source.getSampleSupplier();
    final int laterColor = nextColor(later);
    assertEquals(1, earlierColor);
    assertEquals(SECOND_FRAME_COLOR, laterColor);
  }

  @Test
  void shouldHandOutTheCachedPixelsOfTheFramesWithoutCopying() {
    final ImageBuffer only = frame(1);
    final List<ImageBuffer> frames = List.of(only);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, REPEAT_COUNT);
    final SampleSupplier supplier = source.getSampleSupplier();
    final int[] firstLoop = supplier.getFrameSamples();
    final int[] secondLoop = supplier.getFrameSamples();
    final int[] held = supplier.getFrameSamples();
    final int[] cached = only.getPixels();
    assertSame(cached, firstLoop, "the cached pixels of the frame are handed out as they are");
    assertSame(firstLoop, secondLoop, "every loop hands out the same array, which is why callers must not modify it");
    assertSame(firstLoop, held);
  }

  @Test
  void shouldRejectInvalidArguments() {
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

  @Test
  void shouldRejectAnAnimationWhoseFirstFrameWasReleased() {
    final int[] pixels = { 1, 1 };
    try (final ImageBuffer released = ImageBuffer.buffer(pixels, FRAME_WIDTH, 1)) {
      released.release();
      final List<ImageBuffer> frames = List.of(released);
      final DynamicImageBuffer animation = animation(frames);
      assertThrows(IllegalStateException.class, () -> RepeatingFrameSource.repeating(animation));
    }
  }

  @Test
  void shouldHandOutEachFrameOncePerLoopToConcurrentCallers() throws Exception {
    final ImageBuffer first = frame(1);
    final ImageBuffer second = frame(SECOND_FRAME_COLOR);
    final List<ImageBuffer> frames = List.of(first, second);
    final DynamicImageBuffer animation = animation(frames);
    final RepeatingFrameSource source = RepeatingFrameSource.repeating(animation, CONCURRENT_REPEAT_COUNT);
    final SampleSupplier supplier = source.getSampleSupplier();
    final List<Integer> colors = this.collectColorsConcurrently(supplier);
    final int firstCount = Collections.frequency(colors, 1);
    final int secondCount = Collections.frequency(colors, SECOND_FRAME_COLOR);
    final int loopedCount = frames.size() * CONCURRENT_REPEAT_COUNT;
    final int heldCount = colors.size() - loopedCount;
    assertEquals(CONCURRENT_REPEAT_COUNT, firstCount);
    assertEquals(CONCURRENT_REPEAT_COUNT + heldCount, secondCount);
  }

  private List<Integer> collectColorsConcurrently(final SampleSupplier supplier) throws Exception {
    final CyclicBarrier startTogether = new CyclicBarrier(CALLER_COUNT);
    final Callable<List<Integer>> caller = () -> this.collectColors(startTogether, supplier);
    final List<Callable<List<Integer>>> callers = Collections.nCopies(CALLER_COUNT, caller);
    final List<Integer> colors = new ArrayList<>();
    try (final ExecutorService executor = Executors.newFixedThreadPool(CALLER_COUNT)) {
      final List<Future<List<Integer>>> results = executor.invokeAll(callers);
      for (final Future<List<Integer>> result : results) {
        final List<Integer> callerColors = result.get();
        colors.addAll(callerColors);
      }
    }

    return colors;
  }

  private List<Integer> collectColors(final CyclicBarrier startTogether, final SampleSupplier supplier) throws Exception {
    startTogether.await();

    final List<Integer> colors = new ArrayList<>();
    for (int call = 0; call < CALLS_PER_CALLER; call++) {
      final int color = nextColor(supplier);
      colors.add(color);
    }

    return colors;
  }
}

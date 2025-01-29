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
package me.brandonli.mcav.utils;

import java.nio.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Adaptive buffer copying utility that automatically selects the optimal
 * copying strategy based on buffer size and type.
 */
public final class CopyUtils {

  private static final int SMALL_BUFFER_THRESHOLD = 8 * 1024 * 1024; // 8MB
  private static final int LARGE_BUFFER_THRESHOLD = 32 * 1024 * 1024; // 32MB

  private static final ExecutorService PLATFORM_EXECUTOR = Executors.newWorkStealingPool(4);

  private CopyUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Copy data from source buffer to a new destination buffer using the optimal strategy.
   *
   * @param src Source buffer
   * @param <T> Buffer type
   * @return A new buffer containing the copied data
   * @throws UnsupportedOperationException if buffer type is not supported
   */
  @SuppressWarnings("unchecked")
  public static <T extends Buffer> Buffer copy(final T src) {
    final Buffer dst =
      switch (src) {
        case final ByteBuffer byteBuffer -> ByteBuffer.allocate(byteBuffer.capacity()).order(byteBuffer.order());
        case final IntBuffer intBuffer -> IntBuffer.allocate(intBuffer.capacity());
        case final LongBuffer longBuffer -> LongBuffer.allocate(longBuffer.capacity());
        case final ShortBuffer shortBuffer -> ShortBuffer.allocate(shortBuffer.capacity());
        case final FloatBuffer floatBuffer -> FloatBuffer.allocate(floatBuffer.capacity());
        case final DoubleBuffer doubleBuffer -> DoubleBuffer.allocate(doubleBuffer.capacity());
        case final CharBuffer charBuffer -> CharBuffer.allocate(charBuffer.capacity());
        default -> throw new UnsupportedOperationException("Buffer type not supported");
      };
    copy(src, (T) dst);
    dst.rewind();
    return dst;
  }

  /**
   * Copy data from source buffer to destination buffer using the optimal strategy.
   *
   * @param src Source buffer
   * @param dst Destination buffer
   * @param <T> Buffer type
   * @throws IllegalArgumentException if buffers are incompatible
   * @throws UnsupportedOperationException if buffer type is not supported
   */
  public static <T extends Buffer> void copy(final T src, final T dst) {
    final long bufferSize = getBufferSizeInBytes(src);
    switch (src) {
      case final ByteBuffer byteBuffer when dst instanceof ByteBuffer -> copyByteBuffer(byteBuffer, (ByteBuffer) dst, bufferSize);
      case final IntBuffer intBuffer when dst instanceof IntBuffer -> copyIntBuffer(intBuffer, (IntBuffer) dst, bufferSize);
      case final LongBuffer longBuffer when dst instanceof LongBuffer -> copyLongBuffer(longBuffer, (LongBuffer) dst, bufferSize);
      case final ShortBuffer shortBuffer when dst instanceof ShortBuffer -> copyShortBuffer(shortBuffer, (ShortBuffer) dst, bufferSize);
      case final FloatBuffer floatBuffer when dst instanceof FloatBuffer -> copyFloatBuffer(floatBuffer, (FloatBuffer) dst, bufferSize);
      case final DoubleBuffer doubleBuffer when dst instanceof DoubleBuffer -> copyDoubleBuffer(
        doubleBuffer,
        (DoubleBuffer) dst,
        bufferSize
      );
      case final CharBuffer charBuffer when dst instanceof CharBuffer -> copyCharBuffer(charBuffer, (CharBuffer) dst, bufferSize);
      default -> throw new UnsupportedOperationException("Buffer type not supported or source/destination types don't match");
    }
  }

  private static void copyByteBuffer(final ByteBuffer src, final ByteBuffer dst, final long bufferSize) {
    if (bufferSize < SMALL_BUFFER_THRESHOLD) {
      copyByteBufferStandard(src, dst);
    } else if (bufferSize < LARGE_BUFFER_THRESHOLD) {
      copyByteBufferWithLongs(src, dst);
    } else {
      copyByteBufferParallelLongs(src, dst);
    }
  }

  private static void copyByteBufferStandard(final ByteBuffer src, final ByteBuffer dst) {
    src.rewind();
    dst.clear();
    dst.put(src);
  }

  private static void copyByteBufferWithLongs(final ByteBuffer src, final ByteBuffer dst) {
    src.rewind();
    dst.clear();

    final LongBuffer srcLongs = src.asLongBuffer();
    final LongBuffer dstLongs = dst.asLongBuffer();

    final int longCount = src.capacity() / 8;
    for (int i = 0; i < longCount; i++) {
      dstLongs.put(i, srcLongs.get(i));
    }

    final int remaining = src.capacity() % 8;
    if (remaining > 0) {
      final int offset = longCount * 8;
      for (int i = 0; i < remaining; i++) {
        dst.put(offset + i, src.get(offset + i));
      }
    }
  }

  private static void copyByteBufferParallelLongs(final ByteBuffer src, final ByteBuffer dst) {
    src.rewind();
    dst.clear();

    final int longCount = src.capacity() / 8;
    final int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
    final int longsPerThread = longCount / numThreads;

    final LongBuffer srcLongs = src.asLongBuffer();
    final LongBuffer dstLongs = dst.asLongBuffer();

    final List<CompletableFuture<Void>> futures = IntStream.range(0, numThreads)
      .mapToObj(threadId ->
        CompletableFuture.runAsync(
          () -> {
            final int start = threadId * longsPerThread;
            final int end = (threadId == numThreads - 1) ? longCount : start + longsPerThread;
            for (int i = start; i < end; i++) {
              dstLongs.put(i, srcLongs.get(i));
            }
          },
          PLATFORM_EXECUTOR
        )
      )
      .toList();

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    final int remaining = src.capacity() % 8;
    if (remaining > 0) {
      final int offset = longCount * 8;
      for (int i = 0; i < remaining; i++) {
        dst.put(offset + i, src.get(offset + i));
      }
    }
  }

  private static void copyIntBuffer(final IntBuffer src, final IntBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    if (bufferSize >= LARGE_BUFFER_THRESHOLD) {
      copyIntBufferParallel(src, dst);
    } else {
      dst.put(src);
    }
  }

  private static void copyIntBufferParallel(final IntBuffer src, final IntBuffer dst) {
    final int capacity = src.capacity();
    final int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
    final int elementsPerThread = capacity / numThreads;
    final List<CompletableFuture<Void>> futures = IntStream.range(0, numThreads)
      .mapToObj(threadId ->
        CompletableFuture.runAsync(
          () -> {
            final int start = threadId * elementsPerThread;
            final int end = (threadId == numThreads - 1) ? capacity : start + elementsPerThread;

            for (int i = start; i < end; i++) {
              dst.put(i, src.get(i));
            }
          },
          PLATFORM_EXECUTOR
        )
      )
      .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private static void copyLongBuffer(final LongBuffer src, final LongBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    if (bufferSize >= LARGE_BUFFER_THRESHOLD) {
      copyLongBufferParallel(src, dst);
    } else {
      dst.put(src);
    }
  }

  private static void copyLongBufferParallel(final LongBuffer src, final LongBuffer dst) {
    final int capacity = src.capacity();
    final int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
    final int elementsPerThread = capacity / numThreads;
    final List<CompletableFuture<Void>> futures = IntStream.range(0, numThreads)
      .mapToObj(threadId ->
        CompletableFuture.runAsync(
          () -> {
            final int start = threadId * elementsPerThread;
            final int end = (threadId == numThreads - 1) ? capacity : start + elementsPerThread;

            for (int i = start; i < end; i++) {
              dst.put(i, src.get(i));
            }
          },
          PLATFORM_EXECUTOR
        )
      )
      .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private static void copyShortBuffer(final ShortBuffer src, final ShortBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    dst.put(src);
  }

  private static void copyFloatBuffer(final FloatBuffer src, final FloatBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    if (bufferSize >= LARGE_BUFFER_THRESHOLD) {
      copyFloatBufferParallel(src, dst);
    } else {
      dst.put(src);
    }
  }

  private static void copyFloatBufferParallel(final FloatBuffer src, final FloatBuffer dst) {
    final int capacity = src.capacity();
    final int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
    final int elementsPerThread = capacity / numThreads;
    final List<CompletableFuture<Void>> futures = IntStream.range(0, numThreads)
      .mapToObj(threadId ->
        CompletableFuture.runAsync(
          () -> {
            final int start = threadId * elementsPerThread;
            final int end = (threadId == numThreads - 1) ? capacity : start + elementsPerThread;

            for (int i = start; i < end; i++) {
              dst.put(i, src.get(i));
            }
          },
          PLATFORM_EXECUTOR
        )
      )
      .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private static void copyDoubleBuffer(final DoubleBuffer src, final DoubleBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    if (bufferSize >= LARGE_BUFFER_THRESHOLD) {
      copyDoubleBufferParallel(src, dst);
    } else {
      dst.put(src);
    }
  }

  private static void copyDoubleBufferParallel(final DoubleBuffer src, final DoubleBuffer dst) {
    final int capacity = src.capacity();
    final int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
    final int elementsPerThread = capacity / numThreads;
    final List<CompletableFuture<Void>> futures = IntStream.range(0, numThreads)
      .mapToObj(threadId ->
        CompletableFuture.runAsync(
          () -> {
            final int start = threadId * elementsPerThread;
            final int end = (threadId == numThreads - 1) ? capacity : start + elementsPerThread;

            for (int i = start; i < end; i++) {
              dst.put(i, src.get(i));
            }
          },
          PLATFORM_EXECUTOR
        )
      )
      .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private static void copyCharBuffer(final CharBuffer src, final CharBuffer dst, final long bufferSize) {
    src.rewind();
    dst.clear();
    dst.put(src);
  }

  private static long getBufferSizeInBytes(final Buffer buffer) {
    return switch (buffer) {
      case final IntBuffer ignored -> buffer.capacity() * 4L;
      case final LongBuffer ignored -> buffer.capacity() * 8L;
      case final ShortBuffer ignored -> buffer.capacity() * 2L;
      case final FloatBuffer ignored -> buffer.capacity() * 4L;
      case final DoubleBuffer ignored -> buffer.capacity() * 8L;
      case final CharBuffer ignored -> buffer.capacity() * 2L;
      default -> buffer.capacity();
    };
  }

  /**
   * Initialize thread pools.
   */
  public static void init() {
    // no-op
  }

  /**
   * Shutdown thread pools.
   */
  public static void shutdown() {
    ExecutorUtils.shutdownExecutorGracefully(PLATFORM_EXECUTOR);
  }
}

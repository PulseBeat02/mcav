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
package me.brandonli.mcav.utils.natives;

import com.google.common.base.Preconditions;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import me.brandonli.mcav.media.player.PlayerException;

/**
 * Converts audio sample buffers between byte orders. The audio pipeline carries signed 16-bit samples in
 * little-endian order; sinks that need big-endian samples, such as Discord, convert with
 * {@link #toBigEndian(ByteBuffer)}.
 */
public final class ByteUtils {

  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
  private static final boolean LITTLE_ENDIAN = NATIVE_ORDER.equals(ByteOrder.LITTLE_ENDIAN);
  private static final boolean BIG_ENDIAN = NATIVE_ORDER.equals(ByteOrder.BIG_ENDIAN);

  private ByteUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether the native byte order of this machine is little-endian.
   *
   * @return true on little-endian machines, which includes x86 and most ARM systems
   */
  public static boolean isLittleEndian() {
    return LITTLE_ENDIAN;
  }

  /**
   * Checks whether the native byte order of this machine is big-endian.
   *
   * @return true on big-endian machines
   */
  public static boolean isBigEndian() {
    return BIG_ENDIAN;
  }

  /**
   * Converts a buffer of audio samples into a new little-endian byte buffer. Short buffers are copied sample by
   * sample, float buffers are copied as 32-bit floats, and byte buffers are treated as 16-bit samples whose bytes
   * are swapped when the buffer is big-endian.
   *
   * @param buffer the samples, read from position to limit without changing the buffer
   * @return a new buffer with the samples in little-endian order, ready to read
   * @throws PlayerException if the buffer is not a short, float, or byte buffer
   */
  public static ByteBuffer toLittleEndian(final Buffer buffer) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null");
    return switch (buffer) {
      case final ShortBuffer shorts -> shortsToLittleEndian(shorts);
      case final FloatBuffer floats -> floatsToLittleEndian(floats);
      case final ByteBuffer bytes -> swapSamples(bytes, ByteOrder.LITTLE_ENDIAN);
      default -> {
        final Class<? extends Buffer> type = buffer.getClass();
        throw new PlayerException("Unsupported sample buffer type " + type);
      }
    };
  }

  private static ByteBuffer shortsToLittleEndian(final ShortBuffer shorts) {
    final ShortBuffer source = shorts.duplicate();
    final int remaining = source.remaining();
    final ByteBuffer result = allocate(remaining * Short.BYTES, ByteOrder.LITTLE_ENDIAN);
    while (source.hasRemaining()) {
      final short sample = source.get();
      result.putShort(sample);
    }
    result.flip();
    return result;
  }

  private static ByteBuffer floatsToLittleEndian(final FloatBuffer floats) {
    final FloatBuffer source = floats.duplicate();
    final int remaining = source.remaining();
    final ByteBuffer result = allocate(remaining * Float.BYTES, ByteOrder.LITTLE_ENDIAN);
    while (source.hasRemaining()) {
      final float sample = source.get();
      result.putFloat(sample);
    }
    result.flip();
    return result;
  }

  /**
   * Converts 16-bit samples into a new big-endian byte buffer. The byte order of the source buffer decides
   * whether the bytes are swapped; a buffer that is already big-endian is copied as is.
   *
   * @param samples the samples, read from position to limit without changing the buffer
   * @return a new buffer with the samples in big-endian order, ready to read
   */
  public static ByteBuffer toBigEndian(final ByteBuffer samples) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    return swapSamples(samples, ByteOrder.BIG_ENDIAN);
  }

  private static ByteBuffer swapSamples(final ByteBuffer samples, final ByteOrder target) {
    final ByteBuffer source = samples.duplicate();
    final ByteOrder sourceOrder = samples.order();
    source.order(sourceOrder);
    final int remaining = source.remaining();
    final ByteBuffer result = allocate(remaining, target);
    if (sourceOrder == target) {
      result.put(source);
      result.flip();
      return result;
    }
    while (source.remaining() >= Short.BYTES) {
      final short sample = source.getShort();
      result.putShort(sample);
    }
    if (source.hasRemaining()) {
      final byte odd = source.get();
      result.put(odd);
    }
    result.flip();
    return result;
  }

  private static ByteBuffer allocate(final int capacity, final ByteOrder order) {
    final ByteBuffer buffer = ByteBuffer.allocate(capacity);
    buffer.order(order);
    return buffer;
  }
}

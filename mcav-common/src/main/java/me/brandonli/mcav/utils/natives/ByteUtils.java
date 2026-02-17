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

import java.nio.*;
import me.brandonli.mcav.media.player.PlayerException;

/**
 * Utility class for byte buffer operations involving endianness.
 */
public final class ByteUtils {

  private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  @SuppressWarnings("all") // checker
  private static final ThreadLocal<ByteBuffer> REUSABLE_AUDIO_BUFFER = ThreadLocal.withInitial(() ->
    ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
  );

  private ByteUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks if the system's native byte order is little-endian.
   *
   * @return true if the native byte order is little-endian, false otherwise
   */
  public static boolean isLittleEndian() {
    return LITTLE_ENDIAN;
  }

  /**
   * Checks if the system's native byte order is big-endian.
   *
   * @return true if the native byte order is big-endian, false otherwise
   */
  public static boolean isBigEndian() {
    return !LITTLE_ENDIAN;
  }

  /**
   * Converts audio samples from any Buffer type to a ByteBuffer in little-endian format
   * in a single pass operation.
   *
   * @param buffer the Buffer containing audio samples (FloatBuffer, ShortBuffer, or ByteBuffer)
   * @return a ByteBuffer containing the converted audio samples in little-endian format
   * @throws PlayerException if the buffer type is unsupported
   */
  public static ByteBuffer convertAudioSamplesToLittleEndian(final Buffer buffer) {
    final ByteBuffer result;
    switch (buffer) {
      case final FloatBuffer floatBuffer -> {
        final int capacity = floatBuffer.capacity();
        result = getReusableBuffer(capacity * 4);
        for (int i = 0; i < capacity; i++) {
          result.putFloat(floatBuffer.get(i));
        }
      }
      case final ShortBuffer shortBuffer -> {
        if (LITTLE_ENDIAN) {
          final int byteSize = shortBuffer.capacity() * 2;
          result = getReusableBuffer(byteSize);
          for (int i = 0; i < shortBuffer.capacity(); i++) {
            result.putShort(shortBuffer.get(i));
          }
        } else {
          final int capacity = shortBuffer.capacity();
          result = getReusableBuffer(capacity * 2);
          for (int i = 0; i < capacity; i++) {
            result.putShort(shortBuffer.get(i));
          }
        }
      }
      case final ByteBuffer byteBuffer -> {
        if (byteBuffer.order() == ByteOrder.LITTLE_ENDIAN) {
          return byteBuffer;
        }
        final ByteBuffer duplicate = byteBuffer.duplicate();
        duplicate.rewind();
        result = getReusableBuffer(duplicate.remaining());
        while (duplicate.remaining() >= 2) {
          final short value = duplicate.getShort();
          final short swapped = (short) (((value & 0xFF) << 8) | ((value >> 8) & 0xFF));
          result.putShort(swapped);
        }
        if (duplicate.remaining() > 0) {
          result.put(duplicate.get());
        }
      }
      case null, default -> throw new PlayerException("Unsupported buffer type!");
    }
    result.flip();
    return result;
  }

  private static ByteBuffer getReusableBuffer(final int requiredCapacity) {
    ByteBuffer buf = REUSABLE_AUDIO_BUFFER.get();
    if (buf.capacity() < requiredCapacity) {
      buf = ByteBuffer.allocate(requiredCapacity).order(ByteOrder.LITTLE_ENDIAN);
      REUSABLE_AUDIO_BUFFER.set(buf);
    }
    buf.clear();
    buf.order(ByteOrder.LITTLE_ENDIAN);
    return buf;
  }

  /**
   * Ensures the provided ByteBuffer is in big-endian format. If the system's native byte order
   * is little-endian, the method converts the actual bytes to big-endian order.
   *
   * @param nativeBuffer the ByteBuffer to be converted to big-endian format
   * @return a ByteBuffer containing data in big-endian format
   */
  public static ByteBuffer clampNormalBufferToBigEndian(final ByteBuffer nativeBuffer) {
    final ByteBuffer duplicate = nativeBuffer.duplicate();
    final ByteBuffer result = ByteBuffer.allocate(duplicate.remaining());
    result.order(ByteOrder.BIG_ENDIAN);
    while (duplicate.remaining() >= 2) {
      final short value = duplicate.getShort();
      final short swapped = (short) (((value & 0xFF) << 8) | ((value >> 8) & 0xFF));
      result.putShort(swapped);
    }
    if (duplicate.remaining() > 0) {
      result.put(duplicate.get());
    }
    result.flip();
    return result;
  }

  /**
   * Ensures the provided ByteBuffer is in little-endian format. If the system's native byte order
   * is big-endian, the method converts the actual bytes to little-endian order.
   *
   * @param nativeBuffer the ByteBuffer to be converted to little-endian format
   * @return a ByteBuffer containing data in little-endian format
   */
  public static ByteBuffer clampNativeBufferToLittleEndian(final ByteBuffer nativeBuffer) {
    if (!LITTLE_ENDIAN) {
      final ByteBuffer duplicate = nativeBuffer.duplicate();
      final ByteBuffer result = ByteBuffer.allocate(duplicate.remaining());
      result.order(ByteOrder.LITTLE_ENDIAN);
      while (duplicate.remaining() >= 2) {
        final short value = duplicate.getShort();
        final short swapped = (short) (((value & 0xFF) << 8) | ((value >> 8) & 0xFF));
        result.putShort(swapped);
      }
      if (duplicate.remaining() > 0) {
        result.put(duplicate.get());
      }
      result.flip();
      return result;
    }
    return nativeBuffer.order(ByteOrder.LITTLE_ENDIAN);
  }
}

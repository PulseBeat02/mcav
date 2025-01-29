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
  private static final int LUT_SIZE = 1024;
  private static final float[][] CUBIC_LUT = buildCubicLUT();

  private static float[][] buildCubicLUT() {
    final float[][] lut = new float[ByteUtils.LUT_SIZE][4];
    for (int i = 0; i < ByteUtils.LUT_SIZE; i++) {
      final float t = i / (float) (ByteUtils.LUT_SIZE - 1);
      lut[i][0] = -0.5f * t * t * t + t * t - 0.5f * t;
      lut[i][1] = 1.5f * t * t * t - 2.5f * t * t + 1.0f;
      lut[i][2] = -1.5f * t * t * t + 2.0f * t * t + 0.5f * t;
      lut[i][3] = 0.5f * t * t * t - 0.5f * t * t;
    }
    return lut;
  }

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
   * Converts audio samples from a Buffer (FloatBuffer, ShortBuffer, or ByteBuffer) to a ByteBuffer.
   * If the buffer is already a ByteBuffer, it is returned as is.
   * If the buffer is a FloatBuffer or ShortBuffer, it converts the samples to a ByteBuffer.
   *
   * @param buffer the Buffer containing audio samples
   * @return a ByteBuffer containing the converted audio samples
   * @throws PlayerException if the buffer type is unsupported
   */
  public static ByteBuffer convertAudioSamples(final Buffer buffer) {
    switch (buffer) {
      case final FloatBuffer floatBuffer -> {
        return convertFloatSamples(floatBuffer);
      }
      case final ShortBuffer shortBuffer -> {
        return convertShortSamples(shortBuffer);
      }
      case final ByteBuffer byteBuffer -> {
        return byteBuffer;
      }
      case null, default -> throw new PlayerException("Unsupported buffer type!");
    }
  }

  private static ByteBuffer convertShortSamples(final ShortBuffer shortBuffer) {
    final int capacity = shortBuffer.capacity();
    final ByteBuffer byteBuffer = ByteBuffer.allocate(capacity * 2);
    for (int i = 0; i < capacity; i++) {
      final short sample = shortBuffer.get(i);
      byteBuffer.putShort(sample);
    }
    byteBuffer.flip();
    return byteBuffer;
  }

  private static ByteBuffer convertFloatSamples(final FloatBuffer floatBuffer) {
    final int capacity = floatBuffer.capacity();
    final ByteBuffer byteBuffer = ByteBuffer.allocate(capacity * 4);
    for (int i = 0; i < capacity; i++) {
      final float sample = floatBuffer.get(i);
      byteBuffer.putFloat(sample);
    }
    byteBuffer.flip();
    return byteBuffer;
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
   * Ensures the provided ByteBuffer is in little-endian format for HTTP reads. If the system's native
   * byte order is big-endian, the method converts the actual bytes to match little-endian order.
   * This is useful for network or I/O operations requiring little-endian formatting.
   *
   * @param nativeBuffer the ByteBuffer to be converted to little-endian format for HTTP reads
   * @return a ByteBuffer containing data in little-endian format or the original buffer if no
   * conversion was required
   */
  public static ByteBuffer clampNormalBufferToLittleEndianHttpReads(final ByteBuffer nativeBuffer) {
    if (nativeBuffer.order() == ByteOrder.BIG_ENDIAN) {
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
    return nativeBuffer.order(ByteOrder.BIG_ENDIAN);
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

  /**
   * Converts the provided ByteBuffer to little-endian format by swapping the byte order of each element.
   *
   * @param buffer the ByteBuffer to be converted to little-endian format
   * @return a ByteBuffer containing data in little-endian format
   */
  public static ByteBuffer convertToLittleEndian(final ByteBuffer buffer) {
    final ByteBuffer duplicate = buffer.duplicate();
    duplicate.rewind();
    final ByteBuffer result = ByteBuffer.allocate(duplicate.remaining());
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
   * Resamples audio samples from a ByteBuffer using cubic interpolation.
   * @param samples the ByteBuffer containing audio samples
   * @param sampleRate the original sample rate of the audio
   * @param targetRate the target sample rate to resample to
   * @param frameSize the size of each frame in bytes (e.g., 2 for 16-bit audio)
   * @return a ByteBuffer containing the resampled audio samples
   */
  public static ByteBuffer resampleFast(final ByteBuffer samples, final float sampleRate, final float targetRate, final int frameSize) {
    final ByteOrder order = samples.order();
    if (Math.abs(sampleRate - targetRate) <= 1f) {
      final ByteBuffer dup = samples.duplicate();
      final int valid = dup.remaining() - (dup.remaining() % frameSize);
      final ByteBuffer slice = dup.slice();
      slice.limit(valid);
      return slice.order(order);
    }

    final float ratio = targetRate / sampleRate;
    final int inLen = samples.remaining() / 2;
    final short[] in = new short[inLen];
    samples.order(order);
    for (int i = 0; i < inLen; i++) {
      in[i] = samples.getShort();
    }

    final int outLen = (int) Math.ceil(inLen * ratio);
    final short[] out = new short[outLen];
    for (int i = 0; i < outLen; i++) {
      final float src = i / ratio;
      final int idx = (int) src;
      final float fract = src - idx;
      final int lutIdx = (int) (fract * (LUT_SIZE - 1));
      final float[] w = CUBIC_LUT[lutIdx];
      final short y0 = (idx > 0) ? in[idx - 1] : in[0];
      final short y1 = in[idx];
      final short y2 = (idx < inLen - 1) ? in[idx + 1] : y1;
      final short y3 = (idx < inLen - 2) ? in[idx + 2] : y2;
      final float v = w[0] * y0 + w[1] * y1 + w[2] * y2 + w[3] * y3;
      final int iv = Math.round(v);
      out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, iv));
    }

    final int validBytes = (outLen * 2) - ((outLen * 2) % frameSize);
    final ByteBuffer res = ByteBuffer.allocate(validBytes).order(order);
    for (int i = 0; i < validBytes / 2; i++) {
      res.putShort(out[i]);
    }
    res.flip();

    return res;
  }
}

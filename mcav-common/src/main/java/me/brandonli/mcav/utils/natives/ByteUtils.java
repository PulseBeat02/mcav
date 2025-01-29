/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for byte buffer operations involving endianness.
 * Provides methods to ensure byte buffers are in a specific endianness format.
 */
public final class ByteUtils {

  private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  private ByteUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static boolean isLittleEndian() {
    return LITTLE_ENDIAN;
  }

  public static boolean isBigEndian() {
    return !LITTLE_ENDIAN;
  }

  /**
   * Ensures the provided ByteBuffer is in big-endian format. If the system's native byte order
   * is little-endian, the method converts the actual bytes to big-endian order.
   *
   * @param nativeBuffer the ByteBuffer to be converted to big-endian format
   * @return a ByteBuffer containing data in big-endian format
   */
  public static ByteBuffer clampNormalBufferToBigEndian(final ByteBuffer nativeBuffer) {
    if (LITTLE_ENDIAN && nativeBuffer.order() == ByteOrder.LITTLE_ENDIAN) {
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
   * Resamples the provided audio buffer from its original sample rate to a target sample rate.
   * If the difference between the source and target sample rates is negligible, the original buffer is returned.
   * Otherwise, the audio data is linearly interpolated to match the target sample rate.
   *
   * @param samples          the ByteBuffer containing the audio samples to be resampled
   * @param sampleRate       the sample rate of the original audio data in hertz (Hz)
   * @param targetSampleRate the desired target sample rate in hertz (Hz)
   * @return a new ByteBuffer containing the resampled audio data, or the original ByteBuffer if no resampling was necessary
   */
  public static ByteBuffer resampleBufferCubic(final ByteBuffer samples, final float sampleRate, final float targetSampleRate) {
    final ByteBuffer duplicate = samples.duplicate();
    if (Math.abs(sampleRate - targetSampleRate) <= 1) {
      return duplicate;
    }
    final float ratio = targetSampleRate / sampleRate;
    final short[] inputSamples = new short[duplicate.remaining() / 2];
    for (int i = 0; i < inputSamples.length; i++) {
      inputSamples[i] = duplicate.getShort();
    }
    final int outputLength = (int) Math.ceil(inputSamples.length * ratio);
    final short[] outputSamples = new short[outputLength];
    for (int i = 0; i < outputLength; i++) {
      final float srcPos = i / ratio;
      final int index = (int) srcPos;
      final float fract = srcPos - index;
      final short y0 = (index > 0) ? inputSamples[index - 1] : inputSamples[0];
      final short y1 = inputSamples[index];
      final short y2 = (index < inputSamples.length - 1) ? inputSamples[index + 1] : y1;
      final short y3 = (index < inputSamples.length - 2) ? inputSamples[index + 2] : y2;
      final float a0 = y3 - y2 - y0 + (float) y1;
      final float a1 = y0 - y1 - a0;
      final float a2 = y2 - (float) y0;
      final float a3 = y1;
      final float value = a0 * fract * fract * fract + a1 * fract * fract + a2 * fract + a3;
      outputSamples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }
    final ByteBuffer resampledBuffer = ByteBuffer.allocate(outputSamples.length * 2);
    resampledBuffer.order(duplicate.order());
    for (final short sample : outputSamples) {
      resampledBuffer.putShort(sample);
    }
    resampledBuffer.flip();
    return resampledBuffer;
  }
}

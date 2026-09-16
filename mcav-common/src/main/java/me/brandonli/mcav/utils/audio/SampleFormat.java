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
package me.brandonli.mcav.utils.audio;

import org.bytedeco.ffmpeg.global.avutil;

/**
 * The encoding of a single PCM sample, as understood by {@link AudioResampler}.
 *
 * <p>Every format is packed (interleaved): the samples of all channels of one frame follow each other, so a stereo
 * stream is laid out as {@code left, right, left, right, ...}. Samples are stored in the native byte order of the
 * platform, which is little-endian on every platform mcav runs on (x86 and ARM). Floating point formats use the
 * nominal range {@code -1.0} to {@code 1.0}.
 */
public enum SampleFormat {
  /**
   * Unsigned 8-bit integer samples, where {@code 128} is silence.
   */
  UNSIGNED_8_BIT(avutil.AV_SAMPLE_FMT_U8, 1),

  /**
   * Signed 16-bit integer samples, the format every mcav player hands to audio pipelines.
   */
  SIGNED_16_BIT(avutil.AV_SAMPLE_FMT_S16, 2),

  /**
   * Signed 32-bit integer samples.
   */
  SIGNED_32_BIT(avutil.AV_SAMPLE_FMT_S32, 4),

  /**
   * Signed 64-bit integer samples.
   */
  SIGNED_64_BIT(avutil.AV_SAMPLE_FMT_S64, 8),

  /**
   * 32-bit IEEE 754 floating point samples.
   */
  FLOAT_32_BIT(avutil.AV_SAMPLE_FMT_FLT, 4),

  /**
   * 64-bit IEEE 754 floating point samples.
   */
  FLOAT_64_BIT(avutil.AV_SAMPLE_FMT_DBL, 8);

  private final int ffmpegFormat;
  private final int bytesPerSample;

  SampleFormat(final int ffmpegFormat, final int bytesPerSample) {
    this.ffmpegFormat = ffmpegFormat;
    this.bytesPerSample = bytesPerSample;
  }

  /**
   * Returns the FFmpeg {@code AVSampleFormat} constant of this format, one of the packed
   * {@code avutil.AV_SAMPLE_FMT_*} values, for use with the FFmpeg bindings of JavaCV.
   *
   * @return the FFmpeg sample format
   */
  public int getFfmpegFormat() {
    return this.ffmpegFormat;
  }

  /**
   * Returns the size of one sample of one channel.
   *
   * @return the number of bytes per sample
   */
  public int getBytesPerSample() {
    return this.bytesPerSample;
  }
}

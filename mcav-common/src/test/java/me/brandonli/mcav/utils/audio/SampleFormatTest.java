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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bytedeco.ffmpeg.global.avutil;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SampleFormat}.
 */
final class SampleFormatTest {

  @Test
  void mapsToThePackedFfmpegFormats() {
    final int unsigned8 = SampleFormat.UNSIGNED_8_BIT.getFfmpegFormat();
    final int signed16 = SampleFormat.SIGNED_16_BIT.getFfmpegFormat();
    final int signed32 = SampleFormat.SIGNED_32_BIT.getFfmpegFormat();
    final int signed64 = SampleFormat.SIGNED_64_BIT.getFfmpegFormat();
    final int float32 = SampleFormat.FLOAT_32_BIT.getFfmpegFormat();
    final int float64 = SampleFormat.FLOAT_64_BIT.getFfmpegFormat();
    assertEquals(avutil.AV_SAMPLE_FMT_U8, unsigned8);
    assertEquals(avutil.AV_SAMPLE_FMT_S16, signed16);
    assertEquals(avutil.AV_SAMPLE_FMT_S32, signed32);
    assertEquals(avutil.AV_SAMPLE_FMT_S64, signed64);
    assertEquals(avutil.AV_SAMPLE_FMT_FLT, float32);
    assertEquals(avutil.AV_SAMPLE_FMT_DBL, float64);
  }

  @Test
  void reportsTheSampleSizeFfmpegUses() {
    for (final SampleFormat format : SampleFormat.values()) {
      final int ffmpegFormat = format.getFfmpegFormat();
      final int expected = avutil.av_get_bytes_per_sample(ffmpegFormat);
      final int actual = format.getBytesPerSample();
      final String formatName = format.name();
      assertEquals(expected, actual, formatName);
    }
  }

  @Test
  void everyFormatIsInterleaved() {
    for (final SampleFormat format : SampleFormat.values()) {
      final int ffmpegFormat = format.getFfmpegFormat();
      final int planar = avutil.av_sample_fmt_is_planar(ffmpegFormat);
      final String formatName = format.name();
      assertEquals(0, planar, formatName);
    }
  }
}

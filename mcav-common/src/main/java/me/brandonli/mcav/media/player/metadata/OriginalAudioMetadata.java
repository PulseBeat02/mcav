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
package me.brandonli.mcav.media.player.metadata;

import com.google.common.base.Preconditions;

/**
 * Properties of an audio stream as decoded. Note that the audio pipeline always receives samples converted to
 * the format described by {@link me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter}; this metadata
 * describes the source.
 */
public interface OriginalAudioMetadata extends OriginalMetadata {
  /**
   * The value of a property the source does not report.
   */
  int UNKNOWN = -1;

  /**
   * Creates metadata.
   *
   * @param codec           the name of the codec, such as {@code aac}
   * @param audioBitrate    the bitrate in bits per second, or {@link #UNKNOWN}
   * @param audioSampleRate the sample rate in hertz
   * @param audioChannels   the number of channels
   * @param samplingFormat  the FFmpeg sample format identifier, or {@link #UNKNOWN}
   * @return the metadata
   */
  static OriginalAudioMetadata of(
    final String codec,
    final int audioBitrate,
    final int audioSampleRate,
    final int audioChannels,
    final int samplingFormat
  ) {
    Preconditions.checkNotNull(codec, "Codec must not be null");
    Preconditions.checkArgument(audioSampleRate > 0, "Sample rate must be positive but was %s", audioSampleRate);
    Preconditions.checkArgument(audioChannels > 0, "Channel count must be positive but was %s", audioChannels);
    return new OriginalAudioMetadataImpl(codec, audioBitrate, audioSampleRate, audioChannels, samplingFormat);
  }

  /**
   * Gets the bitrate of the audio stream.
   *
   * @return the bitrate in bits per second, or {@link #UNKNOWN}
   */
  int getAudioBitrate();

  /**
   * Gets the sample rate of the audio stream.
   *
   * @return the sample rate in hertz
   */
  int getAudioSampleRate();

  /**
   * Gets the number of channels of the audio stream.
   *
   * @return the channel count
   */
  int getAudioChannels();

  /**
   * Gets the name of the codec of the audio stream.
   *
   * @return the codec name
   */
  String getAudioCodec();

  /**
   * Gets the FFmpeg sample format identifier of the audio stream.
   *
   * @return the sample format, or {@link #UNKNOWN}
   */
  int getSamplingFormat();
}

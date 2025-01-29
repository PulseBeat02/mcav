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

/**
 * Represents metadata specific to audio files or streams.
 */
public interface OriginalAudioMetadata extends OriginalMetadata {
  /**
   * Retrieves the audio bitrate of the audio file or stream in raw bits per second.
   *
   * @return the audio bitrate as an integer value in raw bits per second.
   */
  int getAudioBitrate();

  /**
   * Retrieves the audio sample rate in hertz (Hz).
   *
   * @return the audio sample rate in Hz
   */
  int getAudioSampleRate();

  /**
   * Retrieves the number of audio channels in the audio metadata.
   *
   * @return the number of audio channels, where a value of 1 represents mono,
   * 2 represents stereo, and higher values indicate the presence of
   * multiple channels.
   */
  int getAudioChannels();

  /**
   * Retrieves the audio codec used for encoding the audio data.
   *
   * @return the audio codec as a string
   */
  String getAudioCodec();

  /**
   * Retrieves the sampling format of the audio data.
   *
   * @return the sampling format as an integer value
   */
  int getSamplingFormat();

  /**
   * Creates a new instance of {@code AudioMetadata} with the specified audio properties.
   *
   * @param codec           the audio codec used for encoding the audio data
   * @param audioBitrate    the audio bitrate in bits per second
   * @param audioSampleRate the audio sample rate in hertz
   * @param audioChannels   the number of audio channels
   * @param samplingFormat  the sampling format of the audio data
   * @return a new {@code AudioMetadata} instance containing the specified audio properties
   */
  static OriginalAudioMetadata of(
    final String codec,
    final int audioBitrate,
    final int audioSampleRate,
    final int audioChannels,
    final int samplingFormat
  ) {
    return new OriginalAudioMetadataImpl(codec, audioBitrate, audioSampleRate, audioChannels, samplingFormat);
  }
}

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
package me.brandonli.mcav.media.player.metadata;

/**
 * Represents metadata related to audio-specific properties.
 * This interface provides methods for retrieving details about an audio file or stream
 * including its bitrate, sample rate, and the number of audio channels.
 */
public interface AudioMetadata extends Metadata {
  /**
   * Retrieves the audio bitrate of the audio file or stream, measured in kilobits per second (kbps).
   *
   * @return the audio bitrate as an integer value in kbps
   */
  int getAudioBitrate();

  /**
   * Retrieves the audio sample rate in hertz (Hz).
   * This value corresponds to the number of samples of audio carried per second
   * and is typically used to define the audio quality.
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
   * Creates a new instance of {@code AudioMetadata} with the specified audio properties.
   *
   * @param audioBitrate    the audio bitrate in bits per second
   * @param audioSampleRate the audio sample rate in hertz
   * @param audioChannels   the number of audio channels
   * @return a new {@code AudioMetadata} instance containing the specified audio properties
   */
  static AudioMetadata of(final int audioBitrate, final int audioSampleRate, final int audioChannels) {
    return new AudioMetadataImpl(audioBitrate, audioSampleRate, audioChannels);
  }
}

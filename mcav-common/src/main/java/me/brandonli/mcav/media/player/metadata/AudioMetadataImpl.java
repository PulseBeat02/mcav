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
 * A concrete implementation of the {@code AudioMetadata} interface.
 * This class encapsulates metadata related to audio-specific properties,
 * including audio bitrate, sample rate, and the number of audio channels.
 * <p>
 * Instances of this class are immutable and provide read-only access to audio-related metadata.
 */
public final class AudioMetadataImpl implements AudioMetadata {

  private final int audioBitrate;
  private final int audioSampleRate;
  private final int audioChannels;

  AudioMetadataImpl(final int audioBitrate, final int audioSampleRate, final int audioChannels) {
    this.audioBitrate = audioBitrate;
    this.audioSampleRate = audioSampleRate;
    this.audioChannels = audioChannels;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getAudioBitrate() {
    return this.audioBitrate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getAudioSampleRate() {
    return this.audioSampleRate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getAudioChannels() {
    return this.audioChannels;
  }
}

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
 * A concrete implementation of the {@code AudioMetadata} interface.
 */
public final class OriginalAudioMetadataImpl implements OriginalAudioMetadata {

  private final String codec;
  private final int audioBitrate;
  private final int audioSampleRate;
  private final int audioChannels;
  private final int samplingFormat;

  OriginalAudioMetadataImpl(
    final String codec,
    final int audioBitrate,
    final int audioSampleRate,
    final int audioChannels,
    final int samplingFormat
  ) {
    this.codec = codec;
    this.audioBitrate = audioBitrate;
    this.audioSampleRate = audioSampleRate;
    this.audioChannels = audioChannels;
    this.samplingFormat = samplingFormat;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getAudioCodec() {
    return this.codec;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getSamplingFormat() {
    return this.samplingFormat;
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

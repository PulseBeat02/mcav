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

import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link OriginalAudioMetadata}.
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

  @Override
  public String getAudioCodec() {
    return this.codec;
  }

  @Override
  public int getSamplingFormat() {
    return this.samplingFormat;
  }

  @Override
  public int getAudioBitrate() {
    return this.audioBitrate;
  }

  @Override
  public int getAudioSampleRate() {
    return this.audioSampleRate;
  }

  @Override
  public int getAudioChannels() {
    return this.audioChannels;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final OriginalAudioMetadataImpl metadata)) {
      return false;
    }
    return (
      this.codec.equals(metadata.codec) &&
      this.audioBitrate == metadata.audioBitrate &&
      this.audioSampleRate == metadata.audioSampleRate &&
      this.audioChannels == metadata.audioChannels &&
      this.samplingFormat == metadata.samplingFormat
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.codec, this.audioBitrate, this.audioSampleRate, this.audioChannels, this.samplingFormat);
  }

  @Override
  public String toString() {
    return (
      "AudioMetadata[" + this.codec + ", " + this.audioSampleRate + " Hz, " + this.audioChannels + " ch, " + this.audioBitrate + " bps]"
    );
  }
}

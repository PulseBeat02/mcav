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
package me.brandonli.mcav.media.player.pipeline.filter.audio;

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;

/**
 * Makes the audio louder or quieter: every sample is multiplied by the volume and clipped to the range of a 16-bit
 * sample, so above 1 quiet media gets louder and loud media clips. The volume may be changed from any thread while
 * the filter runs; a change applies from the next chunk on.
 */
public final class VolumeFilter implements AudioFilter {

  /** The volume that leaves the audio as it is. */
  public static final double UNCHANGED = 1.0;

  /** The largest volume, twice as loud: beyond it most media only clips. */
  public static final double MAX_VOLUME = 2.0;

  private volatile double volume;

  /**
   * Constructs a filter that leaves the audio as it is until {@link #setVolume(double)} is called.
   */
  public VolumeFilter() {
    this.volume = UNCHANGED;
  }

  /**
   * Sets the volume.
   *
   * @param volume the factor every sample is multiplied by, from 0 (silence) to {@link #MAX_VOLUME}
   * @throws IllegalArgumentException if the volume is outside that range or not a number
   */
  public void setVolume(final double volume) {
    Preconditions.checkArgument(volume >= 0 && volume <= MAX_VOLUME, "Volume must be between 0 and %s: %s", MAX_VOLUME, volume);
    this.volume = volume;
  }

  /**
   * Gets the volume.
   *
   * @return the factor every sample is multiplied by
   */
  public double getVolume() {
    return this.volume;
  }

  /**
   * Scales the samples in place, unless the volume leaves them as they are.
   *
   * @param samples  16-bit little-endian samples from the position to the limit of the buffer, which is not consumed
   * @param metadata the metadata of the original stream
   * @return true if the samples were scaled
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    final double factor = this.volume;
    if (factor == UNCHANGED) {
      return false;
    }
    final ByteBuffer view = samples.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    for (int at = view.position(); at + Short.BYTES <= view.limit(); at += Short.BYTES) {
      final long scaled = Math.round(view.getShort(at) * factor);
      view.putShort(at, (short) Math.clamp(scaled, Short.MIN_VALUE, Short.MAX_VALUE));
    }
    return true;
  }
}

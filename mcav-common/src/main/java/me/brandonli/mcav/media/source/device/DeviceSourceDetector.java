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
package me.brandonli.mcav.media.source.device;

import com.google.common.primitives.Ints;
import me.brandonli.mcav.media.source.SourceDetector;

/**
 * Detects device sources. Must be a single integer value for the device identifier.
 */
public class DeviceSourceDetector implements SourceDetector<DeviceSource> {

  /**
   * Constructs a new {@link DeviceSourceDetector}.
   */
  public DeviceSourceDetector() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    return Ints.tryParse(raw) != null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DeviceSource createSource(final String raw) {
    final int deviceId = Integer.parseInt(raw);
    return DeviceSource.device(deviceId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}

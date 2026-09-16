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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.source.DynamicSource;

/**
 * A camera or capture card, identified by its index; {@code 0} is usually the default webcam. Play device sources
 * with {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#device()}.
 */
public interface DeviceSource extends DynamicSource {
  /**
   * Creates a source for a capture device.
   *
   * @param deviceId the index of the device, starting at 0
   * @return the source
   */
  static DeviceSource device(final int deviceId) {
    Preconditions.checkArgument(deviceId >= 0, "Device index must not be negative");
    return new DeviceSourceImpl(deviceId);
  }

  /**
   * Gets the index of the device.
   *
   * @return the device index
   */
  int getDeviceId();

  @Override
  default String getResource() {
    final int deviceId = this.getDeviceId();
    return String.valueOf(deviceId);
  }

  @Override
  default String getName() {
    return "device";
  }
}

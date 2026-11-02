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
import com.google.common.primitives.Ints;
import me.brandonli.mcav.media.source.SourceDetector;

/**
 * Detects non-negative whole numbers as capture device indices.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public class DeviceSourceDetector implements SourceDetector<DeviceSource> {

  private static final String RAW_NULL_MESSAGE = "Raw must not be null!";

  /**
   * Constructs a new detector.
   *
   * @since 1.0.0
   */
  public DeviceSourceDetector() {}

  /**
   * Checks whether the string is a non-negative integer, which names a camera index.
   *
   * @param raw the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @throws NullPointerException if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    final Integer parsed = Ints.tryParse(raw);
    return parsed != null && parsed >= 0;
  }

  /**
   * Creates a device source for the camera index the string holds.
   *
   * @param raw the string entered by a user
   * @return the source
   * @throws NumberFormatException    if the string is not a decimal {@code int}
   * @throws IllegalArgumentException if the parsed index is negative
   * @throws NullPointerException     if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public DeviceSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    final int deviceId = Integer.parseInt(raw);
    return DeviceSource.device(deviceId);
  }

  /**
   * Returns {@link SourceDetector#HIGH_PRIORITY}, because a non-negative integer is never meant as a path or a URI.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}

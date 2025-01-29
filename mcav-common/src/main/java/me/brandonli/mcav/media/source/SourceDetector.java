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
package me.brandonli.mcav.media.source;

/**
 * Interface for detecting the type of media source based on raw input.
 *
 * @param <T> the type of source that this detector can handle.
 */
public interface SourceDetector<T extends Source> {
  /**
   * Normal priority for source detectors that should be checked in a standard order.
   */
  int NORMAL_PRIORITY = 0;

  /**
   * High priority for source detectors that should be checked first.
   */
  int HIGH_PRIORITY = 100;

  /**
   * Low priority for source detectors that should be checked last.
   */
  int LOW_PRIORITY = -100;

  /**
   * Detects the source type based on the provided raw input.
   *
   * @param raw the raw input to detect the source from.
   * @return true if the source type is detected, otherwise false.
   */
  boolean isDetectedSource(final String raw);

  /**
   * Creates a source instance based on the provided raw input.
   *
   * @param raw the raw input to create the source from.
   * @return a new instance of the source type, or null if the source cannot be created.
   */
  T createSource(final String raw);

  /**
   * Gets the priority of this source detector.
   * @return the priority of this source detector, where a higher value indicates a higher priority.
   */
  int getPriority();
}

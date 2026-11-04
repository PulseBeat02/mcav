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
 * Recognizes one kind of source in a user-supplied string, such as a file path or a URL, and creates it. Detectors
 * are consulted by {@link SourceDetectionHelper}; when several detectors accept a string, the one with the highest
 * priority wins.
 *
 * <p>Implementations in this library are stateless and thread-safe.
 *
 * @param <SourceType> the type of source the detector creates
 * @since 1.0.0
 */
public interface SourceDetector<SourceType extends Source> {
  /**
   * The priority of ordinary detectors.
   *
   * @since 1.0.0
   */
  int NORMAL_PRIORITY = 0;

  /**
   * The priority of detectors that recognize very specific strings and should win over others.
   *
   * @since 1.0.0
   */
  int HIGH_PRIORITY = 100;

  /**
   * The priority of fallback detectors that accept almost anything.
   *
   * @since 1.0.0
   */
  int LOW_PRIORITY = -100;

  /**
   * Returns whether the string describes a source of this kind.
   *
   * @param raw the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @since 1.0.0
   */
  boolean isDetectedSource(final String raw);

  /**
   * Creates the source described by the string, which was accepted by {@link #isDetectedSource(String)}.
   *
   * @param raw the string entered by a user
   * @return the source
   * @since 1.0.0
   */
  SourceType createSource(final String raw);

  /**
   * Returns the priority of this detector.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  int getPriority();
}

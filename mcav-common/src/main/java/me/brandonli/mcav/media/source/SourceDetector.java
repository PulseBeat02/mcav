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
 * @param <T> the type of source the detector creates
 */
public interface SourceDetector<T extends Source> {
  /**
   * The priority of ordinary detectors.
   */
  int NORMAL_PRIORITY = 0;

  /**
   * The priority of detectors that recognize very specific strings and should win over others.
   */
  int HIGH_PRIORITY = 100;

  /**
   * The priority of fallback detectors that accept almost anything.
   */
  int LOW_PRIORITY = -100;

  /**
   * Checks whether the string describes a source of this kind.
   *
   * @param raw the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   */
  boolean isDetectedSource(final String raw);

  /**
   * Creates the source described by the string, which was accepted by {@link #isDetectedSource(String)}.
   *
   * @param raw the string entered by a user
   * @return the source
   */
  T createSource(final String raw);

  /**
   * Gets the priority of this detector.
   *
   * @return the priority, higher wins
   */
  int getPriority();
}

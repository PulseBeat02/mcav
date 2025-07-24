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
package me.brandonli.mcav.svc;

import me.brandonli.mcav.media.player.pipeline.filter.audio.FunctionalAudioFilter;

/**
 * Represents a filter for Simple Voice Chat (SVC) audio processing.
 */
public interface SVCFilter extends FunctionalAudioFilter {
  /**
   * Creates a new SVCFilter instance with the specified player UUIDs.
   *
   * @param players the objects of the players to filter
   * @return a new SVCFilter instance
   */
  static SVCFilter svc(final Object... players) {
    return new SVCFilterImpl(players);
  }
}

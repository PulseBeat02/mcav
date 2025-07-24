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
package me.brandonli.mcav.media.player.pipeline.filter.video;

/**
 * Represents a functional video filter that can be started and released. Extends the
 * {@link VideoFilter} interface to provide additional functionality for managing the lifecycle of the filter.
 */
public interface FunctionalVideoFilter extends VideoFilter {
  /**
   * Starts the video filter, initializing any necessary resources or processes.
   * This method should be called before applying the filter to the pipeline.
   */
  void start();

  /**
   * Releases any resources or processes associated with the video filter.
   * This method should be called when the filter is no longer needed to prevent memory leaks.
   */
  void release();
}

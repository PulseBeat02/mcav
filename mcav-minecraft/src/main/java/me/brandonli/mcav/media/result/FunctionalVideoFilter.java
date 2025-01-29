/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.result;

import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;

/**
 * Represents a functional video filter that extends {@link VideoFilter} and provides
 * additional lifecycle methods for managing the filter's behavior during a video processing
 * pipeline.
 * <p>
 * FunctionalVideoFilter adds control over the initialization and cleanup phases of a video
 * filter through the {@code start()} and {@code release()} methods, providing enhanced
 * flexibility for resource management or configuration during the filter's usage.
 * <p>
 * Implementations of this interface are expected to implement both VideoFilter's core filtering
 * functionality as well as the extended lifecycle capabilities defined here.
 */
public interface FunctionalVideoFilter extends VideoFilter {
  /**
   * Starts the functional video filter. This method initiates any processes
   * necessary for the filter to begin its operation. The specifics of the start
   * behavior are defined by the implementing class.
   */
  void start();

  /**
   * Releases all resources and performs cleanup related to this video filter.
   * This method should be called when the filter is no longer needed to free
   * any allocated resources or handles.
   */
  void release();
}

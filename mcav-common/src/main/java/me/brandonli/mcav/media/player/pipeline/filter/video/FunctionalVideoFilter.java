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
 * A video filter that owns resources, such as a window, a texture, or a network connection.
 *
 * <p>Call {@link #start()} before the first frame is processed and {@link #release()} after the last one. The
 * pipeline steps do not call these methods automatically, because a filter may be shared between pipelines.
 */
public interface FunctionalVideoFilter extends VideoFilter {
  /**
   * Acquires the resources of the filter. Must be called before frames are processed.
   */
  void start();

  /**
   * Releases the resources of the filter. The filter must not be used afterward unless it is started again.
   */
  void release();
}

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
package me.brandonli.mcav.media.player.attachable;

/**
 * Represents a callback for attaching a custom dimension to a media player.
 */
public interface DimensionAttachableCallback extends AttachableCallback<DimensionAttachableCallback.Dimension> {
  /**
   * Creates a new instance of {@link DimensionAttachableCallback}.
   *
   * @return a new instance of {@link DimensionAttachableCallback}
   */
  static DimensionAttachableCallback create() {
    return new DimensionAttachableCallbackImpl();
  }

  /**
   * A record representing the width and height dimensions.
   *
   * @param width  the width dimension
   * @param height the height dimension
   */
  record Dimension(int width, int height) {
    /**
     * A constant representing no dimension.
     */
    public static final Dimension NONE = new Dimension(0, 0);
  }
}

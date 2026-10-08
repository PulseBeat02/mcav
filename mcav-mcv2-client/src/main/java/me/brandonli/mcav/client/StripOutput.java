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
package me.brandonli.mcav.client;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

/** Where the transport strip goes once the frame is rendered: the top of the game's main target. */
interface StripOutput {
  /** The width of the game's main target in pixels. */
  int width();

  /** The height of the game's main target in pixels. */
  int height();

  /**
   * Writes the strip over the top rows of the main target and runs the pack's post chain over it, which decodes the
   * frame and draws the screens.
   *
   * @param strip     the strip's rows, the top one first, RGBA
   * @param rows      how many rows the strip has
   * @param allocator the frame's pool of render targets, which the chain's own targets come from
   * @return false when the pack has no post chain loaded, and nothing was written
   */
  boolean decode(byte[] strip, int rows, GraphicsResourceAllocator allocator);
}

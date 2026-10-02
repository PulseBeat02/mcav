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
/**
 * Selects color-reduction algorithms and defines serial and parallel dithering contracts.
 *
 * <p>In-place dithering replaces ARGB pixels in the caller's array; palette-index methods leave the image
 * unchanged and return a new byte array owned by the caller. Built-in algorithms ignore alpha. Widths are
 * positive and must divide the array length; an empty array is permitted by the in-place methods.
 *
 * <p>Palettes are borrowed and their tables must remain read-only. Stateless algorithms can be shared, but
 * images and output arrays must not be modified concurrently. Temporal algorithms hold the history of one
 * stream and need serialized calls and explicit reset after a seek or source change. A supplied fork/join pool
 * remains caller-owned; parallel calls wait for submitted work and never shut down the pool.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

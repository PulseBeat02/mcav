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
 * Owns decoded still images and animations in native, tightly packed 8-bit BGR memory.
 *
 * <p>Use {@link me.brandonli.mcav.media.image.ImageBuffer} factories for a single frame and
 * {@link me.brandonli.mcav.media.image.DynamicImageBuffer} for an animation decoded entirely into memory.
 * Both support try-with-resources. An animation owns its frames; close the animation instead of individual frames.
 * A copied still image has independent ownership and must be closed separately.
 *
 * <p>Images and their mutable native views are not thread-safe. Keep a frame confined to a processing thread or
 * serialize all access, including release. Raw data views borrow native memory and become invalid after resize,
 * transformation or release. Invalidate the ARGB cache after writing through a native view. Shared ARGB arrays
 * are read-only by contract; use {@link me.brandonli.mcav.media.image.ImageBuffer#copyPixels()} for an owned copy.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.image;

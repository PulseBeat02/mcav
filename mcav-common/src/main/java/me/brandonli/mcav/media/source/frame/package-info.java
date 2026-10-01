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
 * Supplies generated ARGB frames to the image player, including loops over decoded animations.
 *
 * <p>Create a {@link me.brandonli.mcav.media.source.frame.FrameSource} from a pixel or Java-image supplier.
 * The player invokes it on its worker thread at the configured frame rate. Return a full row-major frame,
 * matching the source dimensions, or an empty pixel array to skip a frame. Returned arrays may be reused once
 * the call has been consumed; do not modify them concurrently while the player copies them.
 *
 * <p>{@link me.brandonli.mcav.media.source.frame.RepeatingFrameSource} borrows an animation and maintains a
 * shared playback cursor. Its supplier calls are serialized, but callers must keep the animation open and
 * avoid modifying its images during playback. Creating another supplier view does not reset that cursor.
 * The application releases the player before closing the animation; a source does not close either resource.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source.frame;

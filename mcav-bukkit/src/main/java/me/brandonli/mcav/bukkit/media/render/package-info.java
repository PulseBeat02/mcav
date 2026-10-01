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
 * Tick-driven block, text-entity and sidebar renderers shared by still-image displays and video results.
 *
 * <p>Show a renderer, submit caller-owned images, then hide it. Conversion occurs in the calling thread;
 * the latest converted frame is applied on the main thread at most once per tick. Viewer membership is also
 * checked each tick. Hiding cancels rendering and removes/restores display state; perform shutdown cleanup on
 * the main thread. Input buffers are never owned by the renderer and must not be mutated during conversion.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.render;

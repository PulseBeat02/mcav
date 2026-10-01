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
 * Provides replaceable pipeline and dimension slots on players.
 *
 * <p>Default slots publish values through an atomic reference. Attach, detach and retrieve are individually
 * thread-safe, but a sequence such as {@code isAttached()} followed by {@code retrieve()} is not atomic.
 * Retrieve once when a consistent value is needed. A detached audio or video slot supplies an empty pipeline;
 * a detached dimension slot supplies {@link me.brandonli.mcav.utils.immutable.Dimension#NONE}.
 *
 * <p>Slots retain references without copying, starting or releasing values. Replacing a pipeline does not stop
 * an invocation already using the old reference. The caller remains responsible for filter thread safety and
 * cleanup after all in-flight invocations finish. Dimension attachment requires a nonempty size.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.attachable;

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
 * Defines terminal player cleanup and failures reported by playback backends.
 *
 * <p>A {@link me.brandonli.mcav.media.player.ReleasablePlayer} owns its workers and decoder resources.
 * Release it when playback is no longer needed; a released player cannot be restarted. Concrete players describe
 * how long they wait for workers and whether callbacks may outlive that wait. Sources, attached pipelines and
 * external output devices generally remain caller-owned.
 *
 * <p>Asynchronous helpers submit the same synchronous operation and preserve its result or failure in a future.
 * An explicitly supplied executor stays caller-owned. Cancelling that future does not release the player and
 * does not guarantee interruption of native work. Use the player's release operation to request shutdown.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player;

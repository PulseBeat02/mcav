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
 * Main-thread text-display holograms for video metadata and an independent progress clock.
 *
 * <p>Create a {@link me.brandonli.mcav.bukkit.hologram.Hologram#basic() basic hologram}, populate its metadata,
 * start its timer and call {@link me.brandonli.mcav.bukkit.hologram.Hologram#kill()} when finished. The hologram
 * owns its current entity and scheduled task. All access, including getters and cleanup, belongs on the server
 * main thread. Its timer does not observe player pause, seek or playback-speed changes.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.hologram;

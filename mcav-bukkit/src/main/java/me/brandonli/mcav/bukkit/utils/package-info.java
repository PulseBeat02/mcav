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
 * Text conversion, server addressing, packet delivery and main-thread renderer support.
 *
 * <p>{@link me.brandonli.mcav.bukkit.utils.PacketUtils} uses connection references managed by BukkitModule so
 * media workers can send packets without accessing world state. Write callbacks run on connection threads and
 * do not acknowledge client rendering. {@link me.brandonli.mcav.bukkit.utils.MainThreadRenderer} transfers only
 * the latest submitted frame to the server thread. Release displays there during plugin shutdown. Address
 * discovery may block and should be performed away from the main thread before its result is cached.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.utils;

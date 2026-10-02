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
 * Hosts resource packs through HTTP connections accepted on the Minecraft server's existing port.
 *
 * <p>{@link me.brandonli.mcav.bukkit.resourcepack.provider.netty.NettyHosting} registers an initializer with
 * Paper and gives each hosting instance a distinct download path. The caller owns the zip file; the host owns
 * only its registration and pack snapshot. Shutdown removes the registration for new connections, leaving
 * existing handlers active. A proxy that terminates or rewrites connections may require another hosting strategy.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

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
 * Hosts a caller-owned resource-pack file on a dedicated HTTP listener.
 *
 * <p>{@link me.brandonli.mcav.bukkit.resourcepack.provider.http.ServerPackHosting} owns its listener and event
 * loops, binds the configured port on local interfaces and uses the supplied host name only in its public URL.
 * Start before advertising the URL and shut down when finished. Lifecycle operations wait for network resources;
 * do not run them from their own event loops. Each download reads the file again, allowing archive replacement.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

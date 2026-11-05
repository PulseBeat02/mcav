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
 * Listeners for server events: a jukebox that boots virtual machines, and the players online.
 *
 * <p>{@link me.brandonli.mcav.plugin.listener.JukeboxListener} boots a virtual machine when a player puts a renamed
 * music disc into a jukebox, and {@link me.brandonli.mcav.plugin.listener.OnlinePlayers} keeps the players online as
 * they join and leave.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.listener;

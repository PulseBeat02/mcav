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
package me.brandonli.mcav.bukkit.media.result;

import org.bukkit.Location;
import org.bukkit.block.BlockState;

/**
 * Represents a data structure that holds a {@link Location} and its corresponding {@link BlockState}.
 * This is used to store location-related data in the Bukkit environment.
 *
 * @param location The Bukkit {@link Location} object representing the position in the world.
 * @param blockState The Bukkit {@link BlockState} object representing the state of the block at the given location.
 */
public record LocationData(Location location, BlockState blockState) {}

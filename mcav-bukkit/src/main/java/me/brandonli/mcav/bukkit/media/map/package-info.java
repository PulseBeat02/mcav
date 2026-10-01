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
 * Map-grid placement, immutable patch descriptions and stateful delta encoding of palette-indexed images.
 *
 * <p>Create a {@link me.brandonli.mcav.bukkit.media.map.MapLayout} for grid geometry, extract full patches or
 * feed successive frames to {@link me.brandonli.mcav.bukkit.media.map.DeltaMapEncoder}, and send returned patches
 * with {@link me.brandonli.mcav.bukkit.media.map.MapPacketFactory}. The encoder assumes every returned patch is
 * delivered to all synchronized viewers; send a snapshot to late viewers. Serialize encoder access. Patch color
 * arrays are shared read-only by contract, while layout extraction copies pixels out of the caller's image.
 * Budgets are estimates and clearing is not paced by the delta budget.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.map;

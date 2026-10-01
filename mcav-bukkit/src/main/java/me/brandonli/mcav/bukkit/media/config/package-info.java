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
 * Builder-based settings for map, block, chat, text-entity and sidebar displays.
 *
 * <p>Choose a configuration matching the target image or video result. Builders validate required fields
 * and dimensions when built. They are mutable and not thread-safe. Viewer collections and locations are
 * retained by reference; collections may be concurrent when membership changes during playback. Entity, block
 * and sidebar renderers check viewer membership each tick; map and chat delivery reads viewers for each frame.
 * Creating settings does not create display entities, reserve maps or acquire player ownership.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.config;

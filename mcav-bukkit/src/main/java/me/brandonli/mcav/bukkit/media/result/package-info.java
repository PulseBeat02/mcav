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
 * Video pipeline outputs for maps, chat, block walls, text entities and sidebar scoreboards.
 *
 * <p>Construct an output from the matching configuration, start it before frame delivery and release it when
 * playback ends. Pipeline attachment does not manage output lifecycle. Map outputs implement the dither result
 * step; {@link me.brandonli.mcav.bukkit.media.result.CompressedMapResult} tracks changed pixels and late viewers,
 * while {@link me.brandonli.mcav.bukkit.media.result.MapResult} sends every covered tile each frame. Other outputs
 * resize and convert caller-owned images, with entity/block/sidebar changes applied on the server main thread.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.result;

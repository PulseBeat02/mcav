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
 * Reusable still-image displays for maps, chat, text entities, block walls and sidebar scoreboards.
 *
 * <p>Use {@link me.brandonli.mcav.bukkit.media.image.DisplayableImage} factories with matching configurations.
 * The caller owns each display and each input image. Display calls consume images synchronously and may resize
 * them in place; scheduled renderers retain converted data. Release displays when finished and use the server
 * main thread for shutdown cleanup. Maps/chat are cleared rather than restored to their earlier contents.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.image;

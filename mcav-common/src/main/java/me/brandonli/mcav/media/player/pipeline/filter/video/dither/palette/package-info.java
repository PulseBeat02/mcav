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
 * Provides Minecraft and custom palettes with precomputed 7-bit-per-channel lookup tables.
 *
 * <p>A palette contains 1 through 256 entries, with optional leading transparent indices. Construction computes
 * 2,097,152 lookup entries for both palette bytes and full colors and can be costly; reuse palette instances.
 * Custom factories copy supplied colors and force matchable colors opaque. Default palette arrays and tables
 * are shared for fast lookup and must never be modified; clone them when mutable copies are needed.
 *
 * <p>Palettes are safe to share while their exposed arrays remain read-only. Byte indices are unsigned when
 * used for array lookup. {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPaletteLoader}
 * loads bundled colors once and returns copied color arrays. Palette construction and loading acquire no
 * caller-managed resource, and palettes have no close operation.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

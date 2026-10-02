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
 * Connects video frames to palette-based displays and supplies fast palette lookup helpers.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter} passes a borrowed frame
 * and an algorithm to a result step, allowing the display to select serial or parallel dithering. It delegates
 * startup and release to that result step; attaching it does not perform either operation. Stop producers before
 * release and follow the result step's thread requirements. A filter does not synchronize or reset its algorithm.
 *
 * <p>Lookup helpers interpret RGB channels as unsigned values and palette bytes with {@code index & 0xFF}.
 * Ordinary dithering ignores alpha. Only the explicitly transparent lookup maps alpha zero to index zero,
 * which is transparent in the Minecraft map palette but may be an ordinary color in a custom palette.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

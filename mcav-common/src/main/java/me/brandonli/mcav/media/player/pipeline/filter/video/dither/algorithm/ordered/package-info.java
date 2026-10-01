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
 * Adds a repeating spatial threshold pattern before palette lookup.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix}
 * is an immutable copied matrix. {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper}
 * normalizes entries around zero and scales them by a finite nonnegative strength. Its returned normalized array
 * is shared and must be treated as read-only. The legacy maximum-level argument is checked for positivity but
 * does not determine normalization; the actual integer minimum and maximum do.
 *
 * <p>The ordered algorithm snapshots integer offsets at construction and has no temporal state. It supports
 * concurrent independent frames, and parallel and serial output agree. Offset spread is estimated from the count
 * of usable palette colors rather than measured distances between actual colors. Caller-owned pools are never
 * closed by the algorithm. See {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither}
 * for patterns or generate one with its power-of-two factory.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered;

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
 * Reduces colors with serpentine error diffusion and optional temporal reuse between video frames.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.DiffusionKernel}
 * describes immutable weighted taps into later pixels. Ordinary algorithms keep error rows per invocation and
 * can be shared. Temporal algorithms keep the previous frame's palette indices: serialize frames for one stream
 * and reset history after seeking, changing source or changing the image layout. Returned byte arrays are
 * independent of the retained history and may be modified by the caller.
 *
 * <p>Parallel temporal dithering divides one frame into horizontal strips with warm-up rows. It can differ from
 * serial diffusion near strip boundaries. Parallelizing one call does not make simultaneous calls on the same
 * temporal instance safe. The caller owns any supplied pool and keeps the image stable until the call returns.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

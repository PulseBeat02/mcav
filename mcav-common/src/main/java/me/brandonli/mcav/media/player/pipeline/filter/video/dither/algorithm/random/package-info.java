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
 * Adds bounded per-channel noise before mapping pixels to palette colors.
 *
 * <p>Weights range from 0 through 255 and bound an inclusive integer noise interval on each channel. Weight zero
 * reduces to nearest-color mapping. The default implementation keeps a generator per thread, so it can be shared;
 * with an explicit provider, all threads call that same provider and its thread-safety requirements apply.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.XoroshiroRandomProvider}
 * is mutable and not thread-safe. A fixed seed gives repeatable sequences for tests and reproducible images.
 * These generators are intended for visual noise, not security-sensitive random values. Returned index arrays
 * belong to callers, while in-place dithering modifies their supplied pixel arrays.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

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
 * Configures dithering algorithms through mutable fluent builders.
 *
 * <p>Factories on {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm}
 * provide defaults for each algorithm family. Set a palette and family-specific parameters, then build a new
 * algorithm. Builders are not thread-safe and keep their configuration after building. Palette instances remain
 * shared; building a temporal algorithm creates fresh history for one player.
 *
 * <p>Setters validate inputs immediately, including temporal settings even when another algorithm is selected.
 * The error threshold is a nonnegative sum of absolute RGB channel errors, and strengths are unitless factors.
 * Builders and algorithms have no explicit resource-close operation; caller-supplied pools remain caller-owned.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder;

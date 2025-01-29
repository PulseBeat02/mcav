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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;

/**
 * The SimpleDiffusionDitherBuilder interface defines a fluent API for constructing
 * instances of classes that implement the {@code NearestDither} algorithm with
 * support for the Simple Diffusion technique. This builder interface extends
 * {@code DitherAlgorithmBuilder}, inheriting methods for palette configuration and
 * dither algorithm construction.
 * <p>
 * Implementing classes are responsible for providing additional configuration
 * options specific to Simple Diffusion dithering while maintaining compliance
 * with the broader {@code DitherAlgorithmBuilder} contract.
 *
 * @param <T> the type parameter representing an implementation of {@code NearestDither}
 *            to be built.
 * @param <B> the type parameter representing the concrete builder implementation
 *            extending {@code SimpleDiffusionDitherBuilder}.
 */
public interface NearestDitherBuilder<T extends NearestDither, B extends NearestDitherBuilder<T, B>> extends DitherAlgorithmBuilder<T, B> {}

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
 * Builds ordered audio and video filter chains.
 *
 * <p>Start with {@link me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder#audio()} or
 * {@link me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder#video()}, append filters and build a chain.
 * Builders are mutable and not thread-safe. Building neither clears the builder nor clones filters; later builds
 * create new steps using the same filter instances, while an existing chain retains its original structure.
 *
 * <p>The application starts and releases resource-owning filters. Building or attaching a chain does neither.
 * Sharing a chain or building several chains from the same builder requires filters that support concurrent use.
 * An empty builder returns the corresponding no-op step.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.builder;

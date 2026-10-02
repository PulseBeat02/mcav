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
 * Defines in-place processing of a media sample with its original metadata.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.Filter#applyFilter(Object, Object)} returns whether
 * it changed or may have changed the sample. That boolean is a copy-back hint, not a request to drop a frame.
 * Pipeline steps continue regardless of the boolean; an exception propagates and stops the current chain call.
 *
 * <p>Samples are borrowed for an invocation. A filter must copy data it retains or sends to another thread and
 * must not release a player's image. Metadata describes the original source, not preceding transformations.
 * Thread safety and native-resource ownership depend on the concrete filter. Resource-owning audio and video
 * filters expose explicit lifecycle methods that the caller invokes outside the pipeline.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter;

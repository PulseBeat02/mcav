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
 * Represents linked audio and video pipelines, including empty no-op chains.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.step.PipelineStep#process(Object, Object)} invokes only one
 * step; {@link me.brandonli.mcav.media.player.pipeline.step.PipelineStep#processAll(Object, Object)} walks the chain.
 * All steps receive the same borrowed sample and original metadata. Filter return values do not stop the chain;
 * exceptions propagate immediately and later steps are skipped for that invocation. Custom chains must terminate.
 *
 * <p>Audio traversal rewinds the buffer before each filter. It does not restore the limit, byte order or contents,
 * and does not restore the final position after the last filter. Default step links are immutable, but their
 * filters may be stateful. Callers own filter startup, synchronization and cleanup; steps never close filters.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.step;

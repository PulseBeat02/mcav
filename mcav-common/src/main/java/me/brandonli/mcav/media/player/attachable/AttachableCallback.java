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
package me.brandonli.mcav.media.player.attachable;

import me.brandonli.mcav.media.player.pipeline.step.PipelineStep;

/**
 * Represents a callback interface for audio and video that can be attached to a player.
 *
 * @param <T> The type of pipeline the callback is associated with.
 */
public interface AttachableCallback<T extends PipelineStep<A, B, C>, A, B, C> {
  void attach(final T pipeline);

  void detach();

  boolean isAttached();

  T getPipeline();
}

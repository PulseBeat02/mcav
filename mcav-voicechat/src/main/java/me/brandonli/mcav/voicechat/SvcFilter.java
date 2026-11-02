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
package me.brandonli.mcav.voicechat;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.audio.FunctionalAudioFilter;

/**
 * Plays the audio of a pipeline through Simple Voice Chat, as if it came from one or more entities: every listed
 * entity becomes a speaker that nearby players hear with positional audio.
 *
 * <p>The {@link SvcModule} must have received the voice chat API before a filter is created. Call
 * {@link #start()} before attaching the filter and {@link #release()} when playback ends. The filter owns its
 * voice chat audio players and Opus encoders; it does not own the injected API or the supplied entities.
 * Release all filters before stopping the module.
 *
 * <p>Filters from {@link #svc(Object...)} and {@link #withDistance(float, Object...)} are thread-safe: they serialize
 * start, release and incoming audio calls, and each speaker has a separate synchronized queue of at most 25 complete
 * 20 millisecond frames. A slow speaker loses its oldest frames; an empty queue supplies silence. Input buffers must
 * remain stable for the duration of a filter call.
 *
 * {@snippet :
 * final SvcFilter speakers = SvcFilter.svc(player);
 * speakers.start();
 *
 * final AudioAttachableCallback audio = videoPlayer.getAudioAttachableCallback();
 * final AudioPipelineStep step = AudioPipelineStep.of(speakers);
 * audio.attach(step);
 * }
 *
 * @since 1.0.0
 */
public interface SvcFilter extends FunctionalAudioFilter {
  /**
   * The distance in blocks the audio can be heard from when none is specified.
   *
   * @since 1.0.0
   */
  float DEFAULT_DISTANCE = 32.0f;

  /**
   * Creates a filter that plays from the specified entities.
   *
   * @param entities a non-null, nonempty array of non-null platform entities, such as Bukkit {@code Player}s;
   *                 the array is copied, while the entities are borrowed
   * @return a new stopped filter using {@link #DEFAULT_DISTANCE}
   * @throws IllegalStateException    if the voice chat API was not injected into {@link SvcModule}
   * @throws NullPointerException     if the array or any entity is null
   * @throws IllegalArgumentException if no entities are supplied
   * @since 1.0.0
   */
  static SvcFilter svc(final Object... entities) {
    return withDistance(DEFAULT_DISTANCE, entities);
  }

  /**
   * Creates a filter that plays from the specified entities with a hearing distance.
   *
   * @param distance the finite, strictly positive hearing distance in blocks
   * @param entities a non-null, nonempty array of non-null platform entities, such as Bukkit {@code Player}s;
   *                 the array is copied, while the entities are borrowed
   * @return a new stopped filter; call {@link #start()} to allocate and start its speakers
   * @throws IllegalStateException    if the voice chat API was not injected into {@link SvcModule}
   * @throws NullPointerException     if the array or any entity is null
   * @throws IllegalArgumentException if the distance is nonpositive or nonfinite, or no entities are supplied
   * @since 1.0.0
   */
  static SvcFilter withDistance(final float distance, final Object... entities) {
    final boolean finiteDistance = Float.isFinite(distance);
    final boolean validDistance = distance > 0 && finiteDistance;
    Preconditions.checkArgument(validDistance, "Distance %s must be finite and positive!", distance);

    Preconditions.checkNotNull(entities, "Entities must not be null!");
    Preconditions.checkArgument(entities.length > 0, "At least one entity is required!");
    for (final Object entity : entities) {
      Preconditions.checkNotNull(entity, "Entities must not contain null!");
    }

    return new EntityChannelSvcFilter(distance, entities);
  }

  /**
   * Returns the number of 20 millisecond frames waiting to be played by the slowest speaker.
   *
   * @return the largest complete-frame queue length, from 0 to 25, or 0 while stopped; a partial frame
   *         is excluded, and consumers may drain the queues while this snapshot is collected
   * @since 1.0.0
   */
  int getQueuedFrames();
}

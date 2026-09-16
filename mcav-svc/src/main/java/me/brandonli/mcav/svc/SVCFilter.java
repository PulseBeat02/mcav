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
package me.brandonli.mcav.svc;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.audio.FunctionalAudioFilter;

/**
 * Plays the audio of a pipeline through Simple Voice Chat, as if it came from one or more entities: every listed
 * entity becomes a speaker that nearby players hear with positional audio.
 *
 * <p>The {@link SVCModule} must have received the voice chat API before a filter is created. Call
 * {@link #start()} before attaching the filter and {@link #release()} when playback ends.
 *
 * <pre><code>
 *   final SVCFilter speakers = SVCFilter.svc(player);
 *   speakers.start();
 *   final AudioAttachableCallback audio = videoPlayer.getAudioAttachableCallback();
 *   final AudioPipelineStep step = AudioPipelineStep.of(speakers);
 *   audio.attach(step);
 * </code></pre>
 */
public interface SVCFilter extends FunctionalAudioFilter {
  /**
   * The distance in blocks the audio can be heard from when none is specified.
   */
  float DEFAULT_DISTANCE = 32.0f;

  /**
   * Creates a filter that plays from the specified entities.
   *
   * @param entities the platform entities the audio comes from, such as Bukkit {@code Player}s
   * @return the filter
   * @throws IllegalStateException if the voice chat API was not injected into {@link SVCModule}
   */
  static SVCFilter svc(final Object... entities) {
    return withDistance(DEFAULT_DISTANCE, entities);
  }

  /**
   * Creates a filter that plays from the specified entities with a hearing distance.
   *
   * @param distance the distance in blocks the audio can be heard from
   * @param entities the platform entities the audio comes from, such as Bukkit {@code Player}s
   * @return the filter
   * @throws IllegalStateException if the voice chat API was not injected into {@link SVCModule}
   */
  static SVCFilter withDistance(final float distance, final Object... entities) {
    final boolean finiteDistance = Float.isFinite(distance);
    final boolean validDistance = distance > 0 && finiteDistance;
    Preconditions.checkArgument(validDistance, "Distance must be positive but was %s", distance);

    Preconditions.checkNotNull(entities, "Entities must not be null");
    Preconditions.checkArgument(entities.length > 0, "At least one entity is required");
    for (final Object entity : entities) {
      Preconditions.checkNotNull(entity, "Entities must not contain null");
    }

    return new SVCFilterImpl(distance, entities);
  }

  /**
   * Gets the number of 20 millisecond frames waiting to be played by the slowest speaker.
   *
   * @return the queued frame count
   */
  int getQueuedFrames();
}

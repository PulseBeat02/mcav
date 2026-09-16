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
package me.brandonli.mcav.media.player.pipeline.step;

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link AudioPipelineStep}.
 */
public final class AudioPipelineStepImpl implements AudioPipelineStep {

  private final AudioFilter filter;
  private final @Nullable AudioPipelineStep next;

  AudioPipelineStepImpl(final @Nullable AudioPipelineStep next, final AudioFilter filter) {
    this.next = next;
    this.filter = filter;
  }

  /**
   * Gets the step that follows this one.
   *
   * @return the next step, or null if this is the last step
   */
  @Override
  public @Nullable AudioPipelineStep next() {
    return this.next;
  }

  /**
   * Gets the filter this step applies.
   *
   * @return the filter
   */
  @Override
  public AudioFilter getFilter() {
    return this.filter;
  }

  /**
   * Applies the filter of this step to the samples. Whatever the filter returns, the samples are passed on.
   *
   * @param buffer   the samples
   * @param metadata the metadata of the original audio stream
   * @throws NullPointerException if the samples or the metadata are null
   */
  @Override
  public void process(final ByteBuffer buffer, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    this.filter.applyFilter(buffer, metadata);
  }
}

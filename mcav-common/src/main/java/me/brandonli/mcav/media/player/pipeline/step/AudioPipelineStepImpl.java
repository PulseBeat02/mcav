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

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of the {@link AudioPipelineStep} interface that represents
 * a step in the audio processing pipeline.
 */
public final class AudioPipelineStepImpl implements AudioPipelineStep {

  private final AudioFilter filter;
  private final @Nullable AudioPipelineStep next;

  AudioPipelineStepImpl(final @Nullable AudioPipelineStep next, final AudioFilter filter) {
    this.next = next;
    this.filter = filter;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @Nullable AudioPipelineStep next() {
    return this.next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void process(final ByteBuffer buffer, final OriginalAudioMetadata metadata) {
    this.filter.applyFilter(buffer, metadata);
  }
}

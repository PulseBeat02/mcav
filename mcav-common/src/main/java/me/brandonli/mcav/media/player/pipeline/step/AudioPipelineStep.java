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
import java.util.Optional;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A link of an audio pipeline. Each chunk of samples passes through the filters of the chain in order; the
 * buffer is rewound before every filter, so filters may read it freely.
 *
 * <p>Implementations in this library are immutable; they are as thread-safe as their filters.
 *
 * @since 1.0.0
 */
public interface AudioPipelineStep extends PipelineStep<ByteBuffer, OriginalAudioMetadata, AudioPipelineStep> {
  /**
   * A pipeline that does nothing, attached while no pipeline is set. It is the only audio step whose
   * {@link #isNoOperation()} returns true.
   *
   * @since 1.0.0
   */
  AudioPipelineStep NO_OPERATION = new NoOperationStep();

  /**
   * Creates a step that applies a filter and continues with another step.
   *
   * @param next   the step that follows, or null to end the chain
   * @param filter the filter of the step
   * @return the step
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  static AudioPipelineStep of(final @Nullable AudioPipelineStep next, final AudioFilter filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null!");

    return new DefaultAudioPipelineStep(next, filter);
  }

  /**
   * Creates a single-step pipeline.
   *
   * @param filter the filter of the step
   * @return the step
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  static AudioPipelineStep of(final AudioFilter filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null!");

    return new DefaultAudioPipelineStep(null, filter);
  }

  /**
   * Returns the filter of this step.
   *
   * @return the filter
   * @since 1.0.0
   */
  AudioFilter getFilter();

  /**
   * Returns this step typed as an audio step.
   *
   * @return this step
   * @since 1.0.0
   */
  @Override
  default AudioPipelineStep self() {
    return this;
  }

  /**
   * Applies the filter of this step and of every step that follows, in order. The buffer is rewound before every
   * filter, so each filter sees the samples from the start, whatever the filters before it read. Every filter runs,
   * whatever the filters before it returned.
   *
   * @param buffer   the samples
   * @param metadata the metadata of the original audio stream
   * @throws NullPointerException if the samples or the metadata are null
   * @throws RuntimeException     if a step or filter fails; subsequent steps are not invoked
   * @since 1.0.0
   */
  @Override
  default void processAll(final ByteBuffer buffer, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

    @Nullable AudioPipelineStep step = this;
    while (step != null) {
      buffer.rewind();
      step.process(buffer, metadata);
      final Optional<AudioPipelineStep> next = step.next();
      step = next.orElse(null);
    }
  }

  /**
   * The step behind {@link #NO_OPERATION}. Its constructor is private, so {@link #NO_OPERATION} is its only instance, and the class
   * can only be initialized together with this interface, which rules out a deadlock between the initialization of the
   * two classes.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  final class NoOperationStep implements AudioPipelineStep {

    private NoOperationStep() {}

    /**
     * Returns an empty optional, because the empty audio pipeline has no following step.
     *
     * @return an empty optional
     * @since 1.0.0
     */
    @Override
    public Optional<AudioPipelineStep> next() {
      return Optional.empty();
    }

    /**
     * Leaves the samples untouched.
     *
     * @param buffer   the samples
     * @param metadata the metadata of the original audio stream
     * @throws NullPointerException if the samples or the metadata are null
     * @since 1.0.0
     */
    @Override
    public void process(final ByteBuffer buffer, final OriginalAudioMetadata metadata) {
      Preconditions.checkNotNull(buffer, "Buffer must not be null!");
      Preconditions.checkNotNull(metadata, "Metadata must not be null!");
    }

    /**
     * Returns {@link AudioFilter#NO_OPERATION}, the filter of the empty audio pipeline.
     *
     * @return {@link AudioFilter#NO_OPERATION}
     * @since 1.0.0
     */
    @Override
    public AudioFilter getFilter() {
      return AudioFilter.NO_OPERATION;
    }

    /**
     * Returns {@code true}, because this step is the empty audio pipeline.
     *
     * @return true
     * @since 1.0.0
     */
    @Override
    public boolean isNoOperation() {
      return true;
    }
  }
}

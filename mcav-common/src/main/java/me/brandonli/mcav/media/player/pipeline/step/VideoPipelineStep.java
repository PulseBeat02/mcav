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
import java.util.Optional;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A link of a video pipeline. Each frame passes through the filters of the chain in order; filters may modify
 * the frame in place for the filters that follow.
 *
 * <p>Implementations in this library are immutable; they are as thread-safe as their filters.
 *
 * @since 1.0.0
 */
public interface VideoPipelineStep extends PipelineStep<ImageBuffer, OriginalVideoMetadata, VideoPipelineStep> {
  /**
   * A pipeline that does nothing, attached while no pipeline is set. It is the only video step whose
   * {@link #isNoOperation()} returns true.
   *
   * @since 1.0.0
   */
  VideoPipelineStep NO_OPERATION = new NoOperationStep();

  /**
   * Creates a step that applies a filter and continues with another step.
   *
   * @param next   the step that follows, or null to end the chain
   * @param filter the filter of the step
   * @return the step
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  static VideoPipelineStep of(final @Nullable VideoPipelineStep next, final VideoFilter filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null!");

    return new DefaultVideoPipelineStep(next, filter);
  }

  /**
   * Creates a single-step pipeline.
   *
   * @param filter the filter of the step
   * @return the step
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  static VideoPipelineStep of(final VideoFilter filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null!");

    return new DefaultVideoPipelineStep(null, filter);
  }

  /**
   * Returns the filter of this step.
   *
   * @return the filter
   * @since 1.0.0
   */
  VideoFilter getFilter();

  /**
   * Returns this step typed as a video step.
   *
   * @return this step
   * @since 1.0.0
   */
  @Override
  default VideoPipelineStep self() {
    return this;
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
  final class NoOperationStep implements VideoPipelineStep {

    private NoOperationStep() {}

    /**
     * Returns an empty optional, because the empty video pipeline has no following step.
     *
     * @return an empty optional
     * @since 1.0.0
     */
    @Override
    public Optional<VideoPipelineStep> next() {
      return Optional.empty();
    }

    /**
     * Leaves the frame untouched.
     *
     * @param buffer   the frame
     * @param metadata the metadata of the original video stream
     * @throws NullPointerException if the frame or the metadata is null
     * @since 1.0.0
     */
    @Override
    public void process(final ImageBuffer buffer, final OriginalVideoMetadata metadata) {
      Preconditions.checkNotNull(buffer, "Buffer must not be null!");
      Preconditions.checkNotNull(metadata, "Metadata must not be null!");
    }

    /**
     * Returns {@link VideoFilter#NO_OPERATION}, the filter of the empty video pipeline.
     *
     * @return {@link VideoFilter#NO_OPERATION}
     * @since 1.0.0
     */
    @Override
    public VideoFilter getFilter() {
      return VideoFilter.NO_OPERATION;
    }

    /**
     * Returns {@code true}, because this step is the empty video pipeline.
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

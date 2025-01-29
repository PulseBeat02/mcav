/*
 * This file is part of mcav, a media playback library for Minecraft
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

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a concrete implementation of the {@link VideoPipelineStep} interface.
 * This class is a step in a video processing pipeline that applies a specific {@link VideoFilter}
 * to the given {@link StaticImage} and optionally delegates further processing to the next step.
 * <p>
 * Instances of this class are immutable and support chaining in video processing pipelines.
 */
public final class VideoPipelineStepImpl implements VideoPipelineStep {

  private final VideoFilter filter;
  private final @Nullable VideoPipelineStep next;

  VideoPipelineStepImpl(final @Nullable VideoPipelineStep next, final VideoFilter filter) {
    this.next = next;
    this.filter = filter;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @Nullable VideoPipelineStep next() {
    return this.next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void process(final StaticImage buffer, final VideoMetadata metadata) {
    this.filter.applyFilter(buffer, metadata);
  }
}

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
package me.brandonli.mcav.media.player.pipeline.builder;

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * Concrete implementation of a pipeline step builder for video processing.
 * This class extends the {@link AbstractPipelineStepBuilder} to construct
 * {@link VideoPipelineStep} instances by chaining together a sequence of
 * {@link VideoFilter} filters. Each filter in the chain processes the output
 * of the previous filter, forming a backward-linked chain.
 * <p>
 * This builder is used to define a sequence of video processing steps where each
 * step applies a {@code VideoFilter} to an input. If no filters are added, a no-operation
 * instance of {@code VideoPipelineStep} is returned upon invocation of the {@code build} method.
 */
public final class VideoPipelineStepBuilderImpl
  extends AbstractPipelineStepBuilder<StaticImage, VideoMetadata, VideoFilter, VideoPipelineStep> {

  VideoPipelineStepBuilderImpl() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoPipelineStep build() {
    VideoPipelineStep chain = null;
    for (int i = this.filters.size() - 1; i >= 0; i--) {
      final VideoFilter filter = this.filters.get(i);
      if (chain == null) {
        chain = VideoPipelineStep.of(filter);
      } else {
        chain = VideoPipelineStep.of(chain, filter);
      }
    }
    return chain == null ? VideoPipelineStep.NO_OP : chain;
  }
}

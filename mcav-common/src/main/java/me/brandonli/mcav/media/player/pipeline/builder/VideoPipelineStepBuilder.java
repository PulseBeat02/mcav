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
package me.brandonli.mcav.media.player.pipeline.builder;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * Builds a video pipeline from {@link VideoFilter}s; create one with {@link PipelineBuilder#video()}.
 */
public abstract class VideoPipelineStepBuilder
  extends AbstractPipelineStepBuilder<ImageBuffer, OriginalVideoMetadata, VideoFilter, VideoPipelineStep, VideoPipelineStepBuilder> {

  /**
   * Constructs an empty builder.
   */
  protected VideoPipelineStepBuilder() {
    // nothing else to set up
  }
}

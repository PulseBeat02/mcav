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

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * A utility interface for creating pipeline builders for different data processing contexts.
 */
public interface PipelineBuilder {
  /**
   * Creates a pipeline step builder specifically designed for constructing audio processing pipelines.
   *
   * @return an instance of {@link AbstractPipelineStepBuilder} configured for audio processing, allowing
   * chaining of {@link AudioFilter}s to build a custom {@link AudioPipelineStep}.
   */
  static AbstractPipelineStepBuilder<ByteBuffer, OriginalAudioMetadata, AudioFilter, AudioPipelineStep> audio() {
    return new AudioPipelineStepBuilderImpl();
  }

  /**
   * Creates and returns a builder for constructing a video processing pipeline.
   *
   * @return a new instance of a video pipeline step builder for configuring and creating
   * a video processing pipeline.
   */
  static AbstractPipelineStepBuilder<ImageBuffer, OriginalVideoMetadata, VideoFilter, VideoPipelineStep> video() {
    return new VideoPipelineStepBuilderImpl();
  }
}

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

/**
 * Entry point for building pipelines. Filters run in the order they are added.
 *
 * {@snippet :
 * VideoPipelineStep createPipeline(final VideoFilter resize, final VideoFilter dither, final VideoFilter display) {
 *   final VideoPipelineStepBuilder builder = PipelineBuilder.video();
 *   builder.then(resize);
 *   builder.then(dither);
 *   builder.then(display);
 *   return builder.build();
 * }
 * }
 *
 * <p>Builders are not thread-safe; use one per thread.
 *
 * @since 1.0.0
 */
public interface PipelineBuilder {
  /**
   * Starts an audio pipeline.
   *
   * @return a new builder
   * @since 1.0.0
   */
  static AudioPipelineStepBuilder audio() {
    return new DefaultAudioPipelineStepBuilder();
  }

  /**
   * Starts a video pipeline.
   *
   * @return a new builder
   * @since 1.0.0
   */
  static VideoPipelineStepBuilder video() {
    return new DefaultVideoPipelineStepBuilder();
  }
}

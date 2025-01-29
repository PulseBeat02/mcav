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
package me.brandonli.mcav.media.player.combined;

import me.brandonli.mcav.media.player.combined.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;

/**
 * The {@code VideoPlayerMultiplexer} interface extends the functionalities of
 * {@link VideoPlayer} and {@link ControllablePlayer}, providing a unified interface
 * for handling video playback with support for controlling playback operations.
 * <p>
 * This interface enables advanced playback features such as managing separate
 * audio and video pipelines, starting playback with distinct audio and video sources,
 * and performing seek, pause, and resume operations. It is designed for use in scenarios
 * requiring integration with complex multimedia workflows.
 */
public interface VideoPlayerMultiplexer extends VideoPlayer, ControllablePlayer {
  /**
   * Starts the video player multiplexer with the provided audio and video processing pipelines,
   * as well as the corresponding audio and video sources.
   *
   * @param audioPipeline the audio processing pipeline used for audio data; must not be null
   * @param videoPipeline the video processing pipeline used for video data; must not be null
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @return true if the player started successfully, false otherwise
   * @throws Exception if an error occurs while starting the player
   */
  boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source video, final Source audio)
    throws Exception;
}

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
package me.brandonli.mcav.media.player.image;

import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FrameSource;

/**
 * The {@code ImagePlayer} interface represents a specialized player that processes frames
 * from a {@code FrameSource} and applies video pipeline steps for video processing.
 * This player can be controlled to start or release video playback and processing tasks.
 * It extends the {@code ReleasablePlayer} interface, which defines a method for releasing resources.
 * <p>
 * Implementations of this interface manage video processing pipelines and carry out
 * operations on static images derived from frame sources.
 */
public interface ImagePlayer extends ReleasablePlayer {
  /**
   * Initiates the video processing pipeline for a given video frame source.
   * <p>
   * This method initializes the specified {@code VideoPipelineStep} to process video frames
   * retrieved from the provided {@code FrameSource}. It sets up the necessary execution context
   * for handling video frames and applying the processing steps in sequence. This method also
   * ensures that the player starts only if it is not already running.
   *
   * @param videoPipeline the initial {@code VideoPipelineStep} in the processing pipeline
   *                      that applies transformations to video frames. Must not be null.
   * @param source        the {@code FrameSource} providing video frames for processing.
   *                      Must not be null.
   * @return {@code true} if the player was successfully started, {@code false} if the player
   * is already running.
   * @throws Exception if an error occurs during initialization or if resources
   *                   required to start the pipeline cannot be acquired.
   */
  boolean start(final VideoPipelineStep videoPipeline, final FrameSource source) throws Exception;

  /**
   * Creates and returns a new instance of {@code ImagePlayer}.
   *
   * @return a new {@code ImagePlayer} instance, typically an {@code ImagePlayerImpl},
   * which is capable of processing frames from a {@code FrameSource} and applying
   * video pipeline steps for video processing.
   */
  static ImagePlayer player() {
    return new ImagePlayerImpl();
  }
}

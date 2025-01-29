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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
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
   */
  boolean start(final VideoPipelineStep videoPipeline, final FrameSource source);

  /**
   * Starts the video processing pipeline asynchronously using the specified video pipeline step,
   * frame source, and execution service.
   * <p>
   * The method initializes the video processing pipeline by invoking the {@code start} method
   * within a {@code CompletableFuture}, utilizing the provided {@code ExecutorService} for
   * asynchronous execution. This enables non-blocking behavior for initiating the video
   * processing workflow.
   *
   * @param videoPipeline the {@code VideoPipelineStep} that defines the initial step of the
   *                      video processing pipeline. Must not be null.
   * @param source        the {@code FrameSource} responsible for providing video frames to
   *                      the pipeline. Must not be null.
   * @param service       the {@code ExecutorService} used for executing the asynchronous
   *                      processing task. Must not be null.
   * @return a {@code CompletableFuture<Boolean>} representing the asynchronous computation.
   * The future completes with {@code true} if the video processing pipeline was
   * successfully started, or {@code false} if it was already running.
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final FrameSource source,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, source), service);
  }

  /**
   * Initiates the video processing pipeline asynchronously for a given video frame source.
   * This method uses the common pool for asynchronous task execution.
   *
   * @param videoPipeline the initial {@code VideoPipelineStep} in the processing pipeline
   *                      that applies transformations to video frames. Must not be null.
   * @param source        the {@code FrameSource} providing video frames for processing.
   *                      Must not be null.
   * @return a {@code CompletableFuture<Boolean>} representing the result of starting the
   * video processing pipeline. The future resolves to {@code true} if the player
   * started successfully, and {@code false} if the player is already running.
   */
  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final FrameSource source) {
    return this.startAsync(videoPipeline, source, ForkJoinPool.commonPool());
  }

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

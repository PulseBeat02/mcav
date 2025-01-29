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
package me.brandonli.mcav.media.player.multimedia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.multimedia.cv.FFmpegPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.OpenCVPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.VideoInputPlayer;
import me.brandonli.mcav.media.player.multimedia.vlc.VLCPlayer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;

/**
 * Represents a video player interface for processing and playing video streams.
 */
public interface VideoPlayer {
  /**
   * Starts the video playback process using the specified audio and video pipeline steps
   * and a combined source for media playback.
   *
   * @param audioPipeline the audio processing pipeline step to handle audio data during playback
   * @param videoPipeline the video processing pipeline step to handle video data during playback
   * @param combined      the source providing the combined media data for playback
   * @return {@code true} if the playback starts successfully, {@code false} otherwise
   */
  boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined);

  /**
   * Starts the video playback process asynchronously using the specified audio and video pipeline steps,
   * a combined source, and an executor service for managing asynchronous execution.
   *
   * @param audioPipeline the audio processing pipeline step responsible for handling audio data
   * @param videoPipeline the video processing pipeline step responsible for handling video data
   * @param combined      the source that provides combined audio and video media data
   * @param service       the executor service used to execute the asynchronous playback operation
   * @return a {@link CompletableFuture} that completes with {@code true} if the playback starts successfully
   * or {@code false} if the start operation fails
   */
  default CompletableFuture<Boolean> startAsync(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source combined,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(audioPipeline, videoPipeline, combined), service);
  }

  /**
   * Asynchronously starts the video playback process using the specified audio and video
   * pipeline steps along with a combined source for media playback.
   *
   * @param audioPipeline the audio processing pipeline step to handle audio data during playback
   * @param videoPipeline the video processing pipeline step to handle video data during playback
   * @param combined      the source providing the combined media data for playback
   * @return a {@link CompletableFuture} that completes with {@code true} if the playback
   * starts successfully, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source combined
  ) {
    return this.startAsync(audioPipeline, videoPipeline, combined, ForkJoinPool.commonPool());
  }

  /**
   * Creates an instance of a VLC-based VideoPlayerMultiplexer using the provided arguments.
   *
   * @param args the arguments to configure the VLC player, such as configuration flags or playback options
   * @return an instance of VideoPlayerMultiplexer configured to use the VLC player
   */
  static VideoPlayerMultiplexer vlc(final String... args) {
    return new VLCPlayer(args);
  }

  /**
   * Creates a new instance of {@link FFmpegPlayer}, which utilizes FFmpeg for processing
   * video and audio frames.
   *
   * @return a {@link VideoPlayerMultiplexer} implementation backed by FFmpeg technology,
   * enabling robust playback and multimedia pipeline integration.
   */
  static VideoPlayerMultiplexer ffmpeg() {
    return new FFmpegPlayer();
  }

  /**
   * Creates a new instance of the OpenCV-based video player.
   * This method provides an implementation of the {@link VideoPlayerMultiplexer}
   * using the OpenCV library for video playback functionality.
   *
   * @return an instance of {@link VideoPlayerMultiplexer} backed by the OpenCVPlayer.
   */
  static VideoPlayerMultiplexer opencv() {
    return new OpenCVPlayer();
  }

  /**
   * Returns an instance of the {@link VideoInputPlayer}, which is a concrete implementation
   * of the {@link VideoPlayerMultiplexer}. The VideoInputPlayer is used to handle video input
   * from a device and provides functionality for processing frames from the video device.
   *
   * @return a new instance of {@link VideoInputPlayer} configured for video input handling.
   */
  static VideoPlayerMultiplexer device() {
    return new VideoInputPlayer();
  }

  /**
   * Returns an instance of a deprecated video player implementation using the JCodec library.
   * This method is marked as {@code @Deprecated} and will throw an
   * {@link UnsupportedOperationException} when invoked.
   *
   * @return nothing, as this method always throws an {@link UnsupportedOperationException}.
   * @throws UnsupportedOperationException as the video player implementation is deprecated and unsupported.
   */
  @Deprecated
  static VideoPlayer jcodec() {
    throw new UnsupportedOperationException("Deprecated Video Player");
  }
}

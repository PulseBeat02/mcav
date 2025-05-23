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
package me.brandonli.mcav.media.player.multimedia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;

/**
 * The {@code VideoPlayerMultiplexer} interface extends the functionality of {@code VideoPlayer},
 * {@code ControllablePlayer}, {@code SeekablePlayer}, and {@code ReleasablePlayer} interfaces to
 * support advanced video playback and control mechanisms. It integrates functionalities for
 * multiplexing audio and video streams, ensuring synchronized playback and providing control
 * over playback operations such as pausing, resuming, seeking, and releasing resources.
 * <p>
 * This interface provides an essential method to start playback using audio and video pipeline
 * steps along with corresponding audio and video sources, allowing precise and configurable setup
 * of media processing and playback.
 */
public interface VideoPlayerMultiplexer extends VideoPlayer, ControllablePlayer, SeekablePlayer, ReleasablePlayer {
  /**
   * Starts the video player multiplexer with the provided audio and video processing pipelines,
   * as well as the corresponding audio and video sources.
   *
   * @param audioPipeline the audio processing pipeline used for audio data; must not be null
   * @param videoPipeline the video processing pipeline used for video data; must not be null
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @return true if the player started successfully, false otherwise
   */
  boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source video, final Source audio);

  /**
   * Initiates the video player multiplexer asynchronously using the specified audio and video
   * processing pipelines, audio and video sources, and an executor service to handle the
   * asynchronous execution.
   *
   * @param audioPipeline the audio processing pipeline used for audio data; must not be null
   * @param videoPipeline the video processing pipeline used for video data; must not be null
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @param service       the executor service used to run the asynchronous operation; must not be null
   * @return a {@code CompletableFuture} that resolves to {@code true} if the player started successfully, or to {@code false} otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(audioPipeline, videoPipeline, video, audio), service);
  }

  /**
   * Initiates asynchronous playback of audio and video using the specified processing pipelines
   * and sources. The method uses a common pool of threads for executing the asynchronous task.
   *
   * @param audioPipeline the audio processing pipeline used for audio data; must not be null
   * @param videoPipeline the video processing pipeline used for video data; must not be null
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @return a CompletableFuture that resolves to true if the player starts successfully,
   * or false if it fails
   */
  default CompletableFuture<Boolean> startAsync(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    return this.startAsync(audioPipeline, videoPipeline, video, audio, ForkJoinPool.commonPool());
  }
}

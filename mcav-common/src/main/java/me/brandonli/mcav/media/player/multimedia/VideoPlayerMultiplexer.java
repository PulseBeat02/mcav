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
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.source.Source;

/**
 * A multiplexer interface for video players that combines functionalities of many video player interfaces.
 */
public interface VideoPlayerMultiplexer extends VideoPlayer, ControllablePlayer, SeekablePlayer, ReleasablePlayer {
  /**
   * Starts the video player multiplexer with the provided audio and video processing pipelines,
   * as well as the corresponding audio and video sources.
   *
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @return true if the player started successfully, false otherwise
   */
  boolean start(final Source video, final Source audio);

  /**
   * Initiates the video player multiplexer asynchronously using the specified audio and video
   * processing pipelines, audio and video sources, and an executor service to handle the
   * asynchronous execution.
   *
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @param service       the executor service used to run the asynchronous operation; must not be null
   * @return a {@code CompletableFuture} that resolves to {@code true} if the player started successfully, or to {@code false} otherwise
   */
  default CompletableFuture<Boolean> startAsync(final Source video, final Source audio, final ExecutorService service) {
    return CompletableFuture.supplyAsync(() -> this.start(video, audio), service);
  }

  /**
   * Initiates asynchronous playback of audio and video using the specified processing pipelines
   * and sources. The method uses a common pool of threads for executing the asynchronous task.
   *
   * @param video         the video source to be played; must not be null
   * @param audio         the audio source to be played; must not be null
   * @return a CompletableFuture that resolves to true if the player starts successfully,
   * or false if it fails
   */
  default CompletableFuture<Boolean> startAsync(final Source video, final Source audio) {
    return this.startAsync(video, audio, ForkJoinPool.commonPool());
  }
}

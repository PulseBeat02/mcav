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

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.source.Source;

/**
 * A full-featured player that can play, pause, resume, seek, and release, and that can take its video and audio
 * from two different sources. Every backend created by the factories of {@link VideoPlayer} is a multiplexer.
 */
public interface VideoPlayerMultiplexer extends VideoPlayer, ControllablePlayer, SeekablePlayer, ReleasablePlayer {
  /**
   * Starts playing video from one source and audio from another, keeping them in sync. This is how
   * separate video and audio streams, as resolved by yt-dlp for high-quality YouTube videos, are played together.
   *
   * @param video the source of the video
   * @param audio the source of the audio
   * @return true if playback started, false if it could not start, for example because a source cannot be opened;
   * the reason is passed to the exception handler of the player
   */
  boolean start(final Source video, final Source audio);

  /**
   * Starts playback from two sources on an executor.
   *
   * @param video    the source of the video
   * @param audio    the source of the audio
   * @param executor the executor that opens the sources
   * @return a future that completes with the result of {@link #start(Source, Source)}
   */
  default CompletableFuture<Boolean> startAsync(final Source video, final Source audio, final ExecutorService executor) {
    Preconditions.checkNotNull(video, "Video source must not be null");
    Preconditions.checkNotNull(audio, "Audio source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(video, audio), executor);
  }

  /**
   * Starts playback from two sources on the common pool.
   *
   * @param video the source of the video
   * @param audio the source of the audio
   * @return a future that completes with the result of {@link #start(Source, Source)}
   */
  default CompletableFuture<Boolean> startAsync(final Source video, final Source audio) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(video, audio, pool);
  }
}

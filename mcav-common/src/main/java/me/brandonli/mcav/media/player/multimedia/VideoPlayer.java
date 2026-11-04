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
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.vlc.VlcInstallationKit;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.cv.FfmpegPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.OpenCvPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.VideoInputPlayer;
import me.brandonli.mcav.media.player.multimedia.vlc.VlcPlayer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.device.DeviceSource;

/**
 * Plays video and audio from a {@link Source} through the attached pipelines.
 *
 * <p>Pick a backend with one of the static factories, attach a video pipeline, an audio pipeline, and optionally
 * a target size, then start the player:
 *
 * {@snippet :
 * final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
 * final VideoAttachableCallback video = player.getVideoAttachableCallback();
 * video.attach(videoPipeline);
 *
 * final AudioAttachableCallback audio = player.getAudioAttachableCallback();
 * audio.attach(audioPipeline);
 *
 * final String fileName = "movie.mp4";
 * final Path movie = Path.of(fileName);
 * final FileSource source = FileSource.path(movie);
 * player.start(source);
 * }
 *
 * <p>Frames are delivered on the player's own threads; the pipelines must not block for long.
 *
 * <p>Implementations in this library are thread-safe.
 *
 * @since 1.0.0
 */
public interface VideoPlayer extends ExceptionHandler {
  /**
   * Creates a player backed by VLC. VLC must be installed or installable; see {@link VlcInstallationKit}. VLC handles
   * the widest range of formats and streams.
   *
   * <p>{@link MCAVApi#install(Class[])} prepares VLC in the background, which can take several minutes when VLC has to
   * be downloaded first. Wait for {@link MCAVApi#whenCapabilityReady(Capability)} with {@link Capability#VLC} before
   * creating the player, or use {@link #ffmpeg()}, which needs no installation.
   *
   * @param mediaOptions the VLC media options applied to every source, such as {@code :network-caching=1000}
   * @return the player
   * @throws IllegalStateException if VLC is still being prepared in the background, or its preparation found that VLC
   *                               is not available on this system; the message says which
   * @throws NullPointerException  if {@code mediaOptions} or any option is null
   * @since 1.0.0
   */
  static VideoPlayerMultiplexer vlc(final String... mediaOptions) {
    Preconditions.checkNotNull(mediaOptions, "Media options must not be null!");

    return new VlcPlayer(mediaOptions);
  }

  /**
   * Creates a player backed by the FFmpeg libraries bundled with JavaCV. No installation is needed; this is the
   * recommended default.
   *
   * @return the player
   * @since 1.0.0
   */
  static VideoPlayerMultiplexer ffmpeg() {
    return new FfmpegPlayer();
  }

  /**
   * Creates a player backed by the video reader of OpenCV, which reads files and streams on every platform.
   *
   * <p>The OpenCV build bundled with JavaCV differs per platform: the Windows build reads files through FFmpeg and
   * Media Foundation and the macOS build through AVFoundation, while the Linux build has no file backend at all and
   * only captures from cameras through V4L2. Where OpenCV cannot read files, the player reads them with the FFmpeg
   * reader JavaCV bundles, so the same code plays the same file everywhere; audio then arrives as well, where the
   * reader of OpenCV delivers video only.
   *
   * @return the player
   * @since 1.0.0
   */
  static VideoPlayerMultiplexer opencv() {
    return new OpenCvPlayer();
  }

  /**
   * Creates a player for cameras and capture cards. Start it with a {@link DeviceSource}.
   *
   * @return the player
   * @since 1.0.0
   */
  static VideoPlayerMultiplexer device() {
    return new VideoInputPlayer();
  }

  /**
   * Starts playing a source that contains both video and audio. Starting a player that is already playing stops
   * the current playback and plays the new source instead.
   *
   * @param combined the source
   * @return true if the backend accepted playback (VLC may still be opening); false if the player is released,
   * another start is in progress, or the source could not be opened;
   * the reason is passed to the exception handler of the player
   * @throws NullPointerException if {@code combined} is null
   * @since 1.0.0
   */
  boolean start(final Source combined);

  /**
   * Starts playback on an executor.
   *
   * @param combined the source
   * @param executor the executor that opens the source
   * @return a future that completes with the result of {@link #start(Source)}
   * @throws java.util.concurrent.RejectedExecutionException if the executor rejects the task
   * @throws NullPointerException                            if {@code combined} or {@code executor} is null
   * @see java.util.concurrent.CompletableFuture#supplyAsync(java.util.function.Supplier, java.util.concurrent.Executor)
   * @since 1.0.0
   */
  default CompletableFuture<Boolean> startAsync(final Source combined, final ExecutorService executor) {
    Preconditions.checkNotNull(combined, "Source must not be null!");
    Preconditions.checkNotNull(executor, "Executor must not be null!");

    return CompletableFuture.supplyAsync(() -> this.start(combined), executor);
  }

  /**
   * Starts playback on the common pool.
   *
   * @param combined the source
   * @return a future that completes with the result of {@link #start(Source)}
   * @throws NullPointerException if {@code combined} is null
   * @see java.util.concurrent.CompletableFuture#supplyAsync(java.util.function.Supplier, java.util.concurrent.Executor)
   * @since 1.0.0
   */
  default CompletableFuture<Boolean> startAsync(final Source combined) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(combined, pool);
  }

  /**
   * Returns the slot that holds the video pipeline. Attach a pipeline before starting; it can be swapped while
   * playing.
   *
   * @return the video pipeline slot
   * @since 1.0.0
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Returns the slot that holds the audio pipeline. Attach a pipeline before starting; it can be swapped while
   * playing.
   *
   * @return the audio pipeline slot
   * @since 1.0.0
   */
  AudioAttachableCallback getAudioAttachableCallback();

  /**
   * Returns the slot that holds the size frames are scaled to before they reach the video pipeline. Leave it
   * detached to receive frames at their original size.
   *
   * @return the target size slot
   * @since 1.0.0
   */
  DimensionAttachableCallback getDimensionAttachableCallback();
}

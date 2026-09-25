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
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.cv.FFmpegPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.OpenCVPlayer;
import me.brandonli.mcav.media.player.multimedia.cv.VideoInputPlayer;
import me.brandonli.mcav.media.player.multimedia.vlc.VLCPlayer;
import me.brandonli.mcav.media.source.Source;

/**
 * Plays video and audio from a {@link Source} through the attached pipelines.
 *
 * <p>Pick a backend with one of the static factories, attach a video pipeline, an audio pipeline, and optionally
 * a target size, then start the player:
 *
 * <pre><code>
 *   final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
 *   final VideoAttachableCallback video = player.getVideoAttachableCallback();
 *   video.attach(videoPipeline);
 *   final AudioAttachableCallback audio = player.getAudioAttachableCallback();
 *   audio.attach(audioPipeline);
 *   final Path movie = Path.of("movie.mp4");
 *   final FileSource source = FileSource.path(movie);
 *   player.start(source);
 * </code></pre>
 *
 * <p>Frames are delivered on the player's own threads; the pipelines must not block for long.
 */
public interface VideoPlayer extends ExceptionHandler {
  /**
   * Creates a player backed by VLC. VLC must be installed or installable; see
   * {@link me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit}. VLC handles the widest range of
   * formats and streams.
   *
   * <p>{@link me.brandonli.mcav.MCAVApi#install(Class[])} prepares VLC in the background, which can take several
   * minutes when VLC has to be downloaded first. Wait for
   * {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(me.brandonli.mcav.capability.Capability)} with
   * {@link me.brandonli.mcav.capability.Capability#VLC} before creating the player, or use {@link #ffmpeg()}, which
   * needs no installation.
   *
   * @param args VLC media options applied to every source, such as {@code :network-caching=1000}
   * @return the player
   * @throws IllegalStateException if VLC is still being prepared in the background, or its preparation found that VLC
   *                               is not available on this system; the message says which
   */
  static VideoPlayerMultiplexer vlc(final String... args) {
    Preconditions.checkNotNull(args, "Arguments must not be null");
    return new VLCPlayer(args);
  }

  /**
   * Creates a player backed by the FFmpeg libraries bundled with JavaCV. No installation is needed; this is the
   * recommended default.
   *
   * @return the player
   */
  static VideoPlayerMultiplexer ffmpeg() {
    return new FFmpegPlayer();
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
   */
  static VideoPlayerMultiplexer opencv() {
    return new OpenCVPlayer();
  }

  /**
   * Creates a player for cameras and capture cards. Start it with a
   * {@link me.brandonli.mcav.media.source.device.DeviceSource}.
   *
   * @return the player
   */
  static VideoPlayerMultiplexer device() {
    return new VideoInputPlayer();
  }

  /**
   * Starts playing a source that contains both video and audio. Starting a player that is already playing stops
   * the current playback and plays the new source instead.
   *
   * @param combined the source
   * @return true if playback started, false if it could not start, for example because the source cannot be opened;
   * the reason is passed to the exception handler of the player
   */
  boolean start(final Source combined);

  /**
   * Starts playback on an executor.
   *
   * @param combined the source
   * @param executor the executor that opens the source
   * @return a future that completes with the result of {@link #start(Source)}
   */
  default CompletableFuture<Boolean> startAsync(final Source combined, final ExecutorService executor) {
    Preconditions.checkNotNull(combined, "Source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(combined), executor);
  }

  /**
   * Starts playback on the common pool.
   *
   * @param combined the source
   * @return a future that completes with the result of {@link #start(Source)}
   */
  default CompletableFuture<Boolean> startAsync(final Source combined) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(combined, pool);
  }

  /**
   * Gets the slot that holds the video pipeline. Attach a pipeline before starting; it can be swapped while
   * playing.
   *
   * @return the video pipeline slot
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Gets the slot that holds the audio pipeline. Attach a pipeline before starting; it can be swapped while
   * playing.
   *
   * @return the audio pipeline slot
   */
  AudioAttachableCallback getAudioAttachableCallback();

  /**
   * Gets the slot that holds the size frames are scaled to before they reach the video pipeline. Leave it
   * detached to receive frames at their original size.
   *
   * @return the target size slot
   */
  DimensionAttachableCallback getDimensionAttachableCallback();
}

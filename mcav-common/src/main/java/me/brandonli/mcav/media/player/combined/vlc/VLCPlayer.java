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
package me.brandonli.mcav.media.player.combined.vlc;

import com.sun.jna.Pointer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ByteUtils;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import uk.co.caprica.vlcj.factory.MediaPlayerApi;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.media.MediaSlavePriority;
import uk.co.caprica.vlcj.media.MediaSlaveType;
import uk.co.caprica.vlcj.media.SlaveApi;
import uk.co.caprica.vlcj.player.base.*;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.*;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * VLCPlayer is a fully featured video and audio player implementation utilizing an embedded VLC media player.
 * It supports playback of media files with separate or combined audio and video sources. This class also
 * provides functionality for managing playback control, seeking, and managing audio and video processing pipelines.
 * <p>
 * This class implements the {@link VideoPlayerMultiplexer} interface, which includes methods to control playback,
 * handle processing steps for both audio and video, and release resources.
 * <p>
 * The `VLCPlayer` class operates by using asynchronous task processing to handle media rendering and playback.
 * All playback operations are synchronized to ensure thread safety. Media playback events are handled through
 * event listeners that respond to playback completion or errors.
 */
public final class VLCPlayer implements VideoPlayerMultiplexer {

  private final EmbeddedMediaPlayer player;
  private final String[] args;

  private CompletableFuture<Void> playbackCompletionFuture;
  private VideoCallback videoCallback;
  private AudioCallback audioCallback;
  private CallbackVideoSurface videoSurface;

  private Source video;
  private Source audio;

  public VLCPlayer(final String[] args) {
    final MediaPlayerFactory factory = MediaPlayerFactoryProvider.getPlayerFactory();
    final MediaPlayerApi mediaPlayerApi = factory.mediaPlayers();
    this.player = mediaPlayerApi.newEmbeddedMediaPlayer();
    this.playbackCompletionFuture = new CompletableFuture<>();
    this.args = args;
    this.setupEventListeners(this.player);
  }

  private void setupEventListeners(@UnderInitialization VLCPlayer this, final MediaPlayer player) {
    player
      .events()
      .addMediaPlayerEventListener(
        new MediaPlayerEventAdapter() {
          @Override
          public void finished(final MediaPlayer mediaPlayer) {
            VLCPlayer.this.playbackCompletionFuture.complete(null);
          }

          @Override
          public void error(final MediaPlayer mediaPlayer) {
            VLCPlayer.this.playbackCompletionFuture.completeExceptionally(new AssertionError("Media player error"));
          }
        }
      );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    if (this.playbackCompletionFuture.isDone()) {
      this.playbackCompletionFuture = new CompletableFuture<>();
    }

    final MediaApi mediaApi = this.player.media();
    final String audioResource = audio.getResource();
    final String videoResource = video.getResource();

    this.video = video;
    this.audio = audio;

    this.addCallbacks(audioPipeline, videoPipeline);
    if (this.args != null && this.args.length > 0) {
      mediaApi.play(videoResource, this.args);
    } else {
      mediaApi.play(videoResource);
    }

    final URI audioUri = URI.create(audioResource);
    final String result = audioUri.toString();
    final SlaveApi slaveApi = mediaApi.slaves();
    slaveApi.add(MediaSlaveType.AUDIO, MediaSlavePriority.LOWEST, result);

    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined) {
    if (this.playbackCompletionFuture.isDone()) {
      this.playbackCompletionFuture = new CompletableFuture<>();
    }

    final MediaApi mediaApi = this.player.media();
    final String resource = combined.getResource();
    this.video = combined;
    this.audio = combined;

    this.addCallbacks(audioPipeline, videoPipeline);
    if (this.args != null && this.args.length > 0) {
      mediaApi.play(resource, this.args);
    } else {
      mediaApi.play(resource);
    }

    return true;
  }

  private void addCallbacks(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline) {
    final BufferFormatCallback callback = new BufferCallback();
    final VideoMetadata videoMetadata = MetadataUtils.parseVideoMetadata(this.video);
    this.videoCallback = new VideoCallback(videoPipeline, videoMetadata);
    this.videoSurface = new CallbackVideoSurface(callback, this.videoCallback, true, this.getAdapter());
    final VideoSurfaceApi surfaceApi = this.player.videoSurface();
    surfaceApi.set(this.videoSurface);

    // audio sample specialization
    // PCM S16LE
    // 2 Channels
    // 48 kHz
    final AudioMetadata audioMetadata = MetadataUtils.parseAudioMetadata(this.audio);
    this.audioCallback = new AudioCallback(audioPipeline, audioMetadata);
    final AudioApi audioApi = this.player.audio();
    audioApi.callback("S16N", 48000, 2, this.audioCallback);
  }

  private VideoSurfaceAdapter getAdapter() {
    switch (OSUtils.getOS()) {
      case WINDOWS:
        return new WindowsVideoSurfaceAdapter();
      case MAC:
        return new OsxVideoSurfaceAdapter();
      default:
        return new LinuxVideoSurfaceAdapter();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    final ControlsApi controls = this.player.controls();
    controls.pause();
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    final ControlsApi controls = this.player.controls();
    controls.start();
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) {
    final ControlsApi controls = this.player.controls();
    controls.setTime(time);
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    this.pause();
    if (!this.playbackCompletionFuture.isDone()) {
      this.playbackCompletionFuture.complete(null);
    }
    this.player.release();
    return true;
  }

  private static final class BufferCallback implements BufferFormatCallback {

    @Override
    public BufferFormat getBufferFormat(final int sourceWidth, final int sourceHeight) {
      return new RV32BufferFormat(sourceWidth, sourceHeight);
    }

    @Override
    public void newFormatSize(final int bufferWidth, final int bufferHeight, final int displayWidth, final int displayHeight) {}

    @Override
    public void allocatedBuffers(final ByteBuffer[] buffers) {}
  }

  /**
   * A callback class for handling audio playback within the {@link VLCPlayer}.
   * This class processes audio samples through a series of {@link AudioPipelineStep} and manages
   * asynchronous execution using an {@link ExecutorService}.
   *
   * <ul>
   * Main responsibilities:
   * - Intercepts audio samples during playback.
   * - Converts raw audio sample data into a {@link ByteBuffer}.
   * - Processes the audio data through the configured audio pipeline.
   * - Executes tasks asynchronously to avoid blocking the playback thread.
   * - Ensures robust handling of completion events and task lifecycle management.
   * </ul>
   * <p>
   * Fields:
   * - step: Represents the starting point of an audio processing pipeline.
   * - metadata: Encapsulates metadata for the audio being processed, such as sample rate or bitrate.
   * - executor: Provides a thread pool to execute tasks asynchronously.
   * <p>
   * Overrides:
   * - play(MediaPlayer mediaPlayer, Pointer samples, int sampleCount, long pts):
   *   Transfers audio sample data to a buffer, processes the audio through the pipeline, and manages
   *   the task lifecycle using {@link CompletableFuture}.
   * <p>
   * Lifecycle:
   * - Processing continues through the linked steps of {@link AudioPipelineStep}.
   * - Each processing step applies its transformation or filter and delegates to the next step.
   * - Task completion ensures proper cleanup and management of pending tasks in the containing {@link VLCPlayer}.
   * <p>
   * Thread-Safety:
   * - The play method handles concurrency via an executor service, isolating audio processing
   *   from the main playback thread.
   */
  private static final class AudioCallback extends AudioCallbackAdapter {

    private static final int BLOCK_SIZE = 4;

    private final AudioPipelineStep step;
    private final AudioMetadata metadata;

    /**
     * Constructs an AudioCallback instance with the specified audio processing step, metadata, and executor service.
     *
     * @param step     The audio processing step to be executed.
     * @param metadata The metadata associated with the audio processing.
     */
    public AudioCallback(final AudioPipelineStep step, final AudioMetadata metadata) {
      this.step = step;
      this.metadata = metadata;
    }

    /**
     * Plays the provided audio samples using the given media player and processes them
     * through the audio pipeline.
     *
     * @param mediaPlayer The media player instance to play the samples.
     * @param samples     A pointer to the audio samples that need to be played.
     * @param sampleCount The number of audio samples to process.
     * @param pts         The presentation timestamp associated with the audio samples.
     */
    @Override
    public void play(final MediaPlayer mediaPlayer, final Pointer samples, final int sampleCount, final long pts) {
      final int bufferSize = sampleCount * BLOCK_SIZE;
      final byte[] bytes = samples.getByteArray(0, bufferSize);
      final ByteBuffer buffer = ByteBuffer.wrap(bytes);
      final ByteBuffer converted = ByteUtils.clampNativeBufferToLittleEndian(buffer);
      AudioPipelineStep current = this.step;
      while (current != null) {
        current.process(converted, this.metadata);
        current = current.next();
      }
    }
  }

  /**
   * VideoCallback is a private implementation of the {@code RenderCallback} interface,
   * designed to handle video rendering callbacks for the {@code VLCPlayer}. This class
   * processes raw video frames and applies a video processing pipeline to transform
   * and analyze the frames before they are displayed or utilized further.
   * <p>
   * The {@code VideoCallback} operates in conjunction with a {@code VideoPipelineStep},
   * which applies a series of transformations through a chain of processing steps.
   * The associated {@code VideoMetadata} provides metadata about the video, such as
   * its dimensions and bitrate. An {@code ExecutorService} is used to run the processing
   * steps asynchronously.
   * <p>
   * Responsibilities of the class:
   * - Process video frame data provided by the native video library.
   * - Delegate frame processing to a pipeline of video filters using {@code VideoPipelineStep}.
   * - Manage asynchronous execution of video processing tasks.
   * - Add tasks to a list for tracking purposes and handle task cleanup upon completion.
   * <p>
   * Constructor Parameters:
   * - {@code step}: The initial step in the video processing pipeline. This step processes
   * the video frames and may delegate additional processing to subsequent chained steps.
   * - {@code metadata}: Metadata describing the video, including its resolution, bitrate,
   * and frame rate.
   * - {@code executor}: An {@code ExecutorService} for managing asynchronous task execution.
   * <p>
   * Implemented RenderCallback Methods:
   * - {@code lock}: Called when the video frame rendering process begins. This method currently
   * performs no operations, but it may be overridden or extended for custom behavior.
   * - {@code display}: Processes the raw video frame data received from the media player.
   * Converts the frame data into an image buffer and invokes the video processing pipeline.
   * - {@code unlock}: Called when the video frame rendering process ends. This method currently
   * performs no operations, but it may be overridden or extended for custom behavior.
   * <p>
   * Thread Safety:
   * The {@code VideoCallback} class is designed to handle concurrent access by using
   * asynchronous task execution and leveraging thread-safe constructs to manage shared
   * resources, such as the task list.
   */
  private static final class VideoCallback implements RenderCallback {

    private final VideoPipelineStep step;
    private final VideoMetadata metadata;

    /**
     * Constructor for the VideoCallback class.
     *
     * @param step     the video pipeline step associated with this callback
     * @param metadata the metadata related to the video being processed
     */
    public VideoCallback(final VideoPipelineStep step, final VideoMetadata metadata) {
      this.step = step;
      this.metadata = metadata;
    }

    /**
     * Locks the specified MediaPlayer to prevent further modifications or interactions.
     *
     * @param mediaPlayer the MediaPlayer instance to be locked
     */
    @Override
    public void lock(final MediaPlayer mediaPlayer) {}

    /**
     * Displays the video frame using the given media player and native buffers.
     *
     * @param mediaPlayer   The media player instance managing video playback.
     * @param nativeBuffers An array of native ByteBuffer objects that contain the raw frame data.
     * @param bufferFormat  The format of the buffer, containing details like pixel format and dimensions.
     * @param displayWidth  The width of the display area for the video frame.
     * @param displayHeight The height of the display area for the video frame.
     */
    @Override
    public void display(
      final MediaPlayer mediaPlayer,
      final ByteBuffer[] nativeBuffers,
      final BufferFormat bufferFormat,
      final int displayWidth,
      final int displayHeight
    ) {
      final int width = bufferFormat.getWidth();
      final int height = bufferFormat.getHeight();
      final VideoMetadata updated = VideoMetadata.of(width, height, this.metadata.getVideoBitrate(), this.metadata.getVideoFrameRate());
      final int[] buffer = new int[width * height];
      nativeBuffers[0].asIntBuffer().get(buffer, 0, width * height);
      final StaticImage image = StaticImage.buffer(buffer, width, height);
      VideoPipelineStep current = this.step;
      while (current != null) {
        current.process(image, updated);
        current = current.next();
      }
      image.release();
    }

    /**
     * Unlocks the given MediaPlayer instance, allowing it to resume or continue its operations.
     *
     * @param mediaPlayer the MediaPlayer instance to be unlocked
     */
    @Override
    public void unlock(final MediaPlayer mediaPlayer) {}
  }
}

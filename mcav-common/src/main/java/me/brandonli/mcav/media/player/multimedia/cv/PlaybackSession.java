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
package me.brandonli.mcav.media.player.multimedia.cv;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// One playback: decoding threads fill bounded queues, render threads hand frames on when the PlaybackClock says; never restarted.
final class PlaybackSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlaybackSession.class);

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private static final long END_OFFER_TIMEOUT_MILLISECONDS = 50L;

  private static final String VIDEO_DECODER_THREAD_NAME = "mcav-decode-video";
  private static final String AUDIO_DECODER_THREAD_NAME = "mcav-decode-audio";
  private static final String VIDEO_RENDERER_THREAD_NAME = "mcav-render-video";
  private static final String AUDIO_RENDERER_THREAD_NAME = "mcav-render-audio";

  private static final int VIDEO_QUEUE_CAPACITY = 4;
  private static final int IMAGES_OUTSIDE_THE_QUEUE = 2;
  private static final int IMAGE_POOL_CAPACITY = VIDEO_QUEUE_CAPACITY + IMAGES_OUTSIDE_THE_QUEUE;
  private static final int AUDIO_QUEUE_CAPACITY = 64;

  private static final long PAUSE_POLL_MILLISECONDS = 50L;

  // How late a frame may be before players drop it instead of showing it.
  static final long MAXIMUM_VIDEO_LAG_NANOSECONDS = 100_000_000L;

  static final long AUDIO_LEAD_NANOSECONDS = 50_000_000L;
  private static final long VIDEO_LEAD_NANOSECONDS = 0L;

  private static final long MAXIMUM_SLEEP_NANOSECONDS = 500_000_000L;
  private static final long JOIN_TIMEOUT_MILLISECONDS = 5_000L;

  private static final OriginalAudioMetadata DEFAULT_AUDIO_METADATA = OriginalAudioMetadata.of(
    "",
    OriginalAudioMetadata.UNKNOWN,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    OriginalAudioMetadata.UNKNOWN
  );

  private static final DecodedVideoFrame END_OF_VIDEO = new DecodedVideoFrame(null, Long.MIN_VALUE);
  private static final ByteBuffer NO_SAMPLES = ByteBuffer.allocate(0);
  private static final DecodedAudioChunk END_OF_AUDIO = new DecodedAudioChunk(NO_SAMPLES, Long.MIN_VALUE);

  private final GrabberFactory videoGrabberFactory;
  private final @Nullable GrabberFactory audioGrabberFactory;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final BiConsumer<String, Throwable> exceptionHandler;
  private final PlaybackClock clock;
  private final long startMicroseconds;
  private final long audioLeadNanoseconds;
  private final long maximumVideoLagNanoseconds;
  private final LongSupplier nanosecondClock;
  private final BlockingQueue<DecodedVideoFrame> videoQueue;
  private final BlockingQueue<DecodedAudioChunk> audioQueue;
  private final ImagePool imagePool;
  private final VideoFrameCopier frameCopier;
  private final LateFrameCounter lateFrames;
  private final SpeedResampler resampler;
  private final AtomicBoolean running;
  private final List<Thread> threads;

  private volatile long positionMicroseconds;
  private volatile OriginalVideoMetadata videoMetadata;
  private volatile OriginalAudioMetadata audioMetadata;
  private volatile boolean seekable;
  private @Nullable FrameGrabber videoGrabber;

  // Nothing is opened until open() or start() is called.
  PlaybackSession(
    final GrabberFactory videoGrabberFactory,
    final @Nullable GrabberFactory audioGrabberFactory,
    final VideoAttachableCallback videoCallback,
    final AudioAttachableCallback audioCallback,
    final DimensionAttachableCallback dimensionCallback,
    final BiConsumer<String, Throwable> exceptionHandler,
    final long startMicroseconds,
    final StartState startState,
    final long audioLeadNanoseconds,
    final long maximumVideoLagNanoseconds,
    final LongSupplier nanosecondClock
  ) {
    this(
      videoGrabberFactory,
      audioGrabberFactory,
      videoCallback,
      audioCallback,
      dimensionCallback,
      exceptionHandler,
      startMicroseconds,
      startState,
      audioLeadNanoseconds,
      maximumVideoLagNanoseconds,
      nanosecondClock,
      PlaybackSession::logFallingBehind
    );
  }

  PlaybackSession(
    final GrabberFactory videoGrabberFactory,
    final @Nullable GrabberFactory audioGrabberFactory,
    final VideoAttachableCallback videoCallback,
    final AudioAttachableCallback audioCallback,
    final DimensionAttachableCallback dimensionCallback,
    final BiConsumer<String, Throwable> exceptionHandler,
    final long startMicroseconds,
    final StartState startState,
    final long audioLeadNanoseconds,
    final long maximumVideoLagNanoseconds,
    final LongSupplier nanosecondClock,
    final LagListener lagListener
  ) {
    this.videoGrabberFactory = videoGrabberFactory;
    this.audioGrabberFactory = audioGrabberFactory;
    this.videoCallback = videoCallback;
    this.audioCallback = audioCallback;
    this.exceptionHandler = exceptionHandler;
    this.clock = new PlaybackClock(nanosecondClock);
    this.startMicroseconds = startMicroseconds;
    this.audioLeadNanoseconds = audioLeadNanoseconds;
    this.maximumVideoLagNanoseconds = maximumVideoLagNanoseconds;
    this.nanosecondClock = nanosecondClock;
    this.videoQueue = new ArrayBlockingQueue<>(VIDEO_QUEUE_CAPACITY);
    this.audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    this.imagePool = new ImagePool(IMAGE_POOL_CAPACITY);
    this.frameCopier = new VideoFrameCopier(this.imagePool, dimensionCallback);
    this.lateFrames = new LateFrameCounter(lagListener);
    this.resampler = new SpeedResampler();
    this.running = new AtomicBoolean(true);
    this.threads = new ArrayList<>();
    this.positionMicroseconds = startMicroseconds;
    this.videoMetadata = OriginalVideoMetadata.EMPTY;
    this.audioMetadata = DEFAULT_AUDIO_METADATA;

    if (startState == StartState.PAUSED) {
      this.clock.pause();
    }
  }

  // Opens the video without threads, so a player knows it plays before it stops the old one; media without a length cannot seek.
  void open() throws FrameGrabber.Exception {
    final FrameGrabber grabber = this.videoGrabberFactory.create();
    try {
      final long length = grabber.getLengthInTime();
      this.seekable = length > 0;
      this.videoGrabber = grabber;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.closeAfterFailedOpen(grabber, exception);
      throw exception;
    }
  }

  private void closeAfterFailedOpen(final FrameGrabber grabber, final Throwable failure) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception | RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);

      final boolean sameFailure = FAILURE_IDENTITY.equivalent(failure, exception);
      if (!sameFailure) {
        failure.addSuppressed(exception);
      }
    }
  }

  // The video opens synchronously unless open() did already, so errors such as a missing file are reported at once.
  void start() throws FrameGrabber.Exception {
    if (this.videoGrabber == null) {
      this.open();
    }

    this.startThreads();
  }

  void startThreads() {
    final @Nullable FrameGrabber grabber = this.videoGrabber;
    if (grabber == null) {
      final String message = "The session must be opened first!";
      throw new IllegalStateException(message);
    }

    final @Nullable GrabberFactory audioFactory = this.audioGrabberFactory;
    final boolean hasSeparateAudio = audioFactory != null;
    final DecodedStreams videoStreams = hasSeparateAudio ? DecodedStreams.VIDEO : DecodedStreams.VIDEO_AND_AUDIO;
    final Thread videoDecoder = this.createThread(VIDEO_DECODER_THREAD_NAME, () -> this.decode(grabber, videoStreams));
    final Thread videoRenderer = this.createThread(VIDEO_RENDERER_THREAD_NAME, this::renderVideo);
    final Thread audioRenderer = this.createThread(AUDIO_RENDERER_THREAD_NAME, this::renderAudio);
    // A stop during startup waits for every thread to start, or one could start after the stop returned.
    synchronized (this.threads) {
      this.threads.add(videoDecoder);
      if (audioFactory != null) {
        final Thread audioDecoder = this.createThread(AUDIO_DECODER_THREAD_NAME, () -> this.decodeSeparateAudio(audioFactory));
        this.threads.add(audioDecoder);
      }

      this.threads.add(videoRenderer);
      this.threads.add(audioRenderer);
      this.threads.forEach(Thread::start);
    }
  }

  private Thread createThread(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private void decodeSeparateAudio(final GrabberFactory factory) {
    final FrameGrabber grabber;
    try {
      grabber = factory.create();
    } catch (final FrameGrabber.Exception | RuntimeException | Error exception) {
      try {
        ThrowableUtils.throwIfFatal(exception);
        this.reportIfRunning("Failed to start the audio source.", exception);
      } finally {
        this.audioQueue.offer(END_OF_AUDIO);
      }

      return;
    }

    this.decode(grabber, DecodedStreams.AUDIO);
  }

  private void decode(final FrameGrabber grabber, final DecodedStreams streams) {
    try {
      this.decodeFrames(grabber, streams);
    } catch (final FrameGrabber.Exception exception) {
      this.reportIfRunning("Failed to decode media.", exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.reportIfRunning("Unexpected error while decoding media.", exception);
    } finally {
      this.finishDecoding(grabber, streams);
    }
  }

  private void finishDecoding(final FrameGrabber grabber, final DecodedStreams streams) {
    try {
      this.closeQuietly(grabber);
    } finally {
      this.finishDecodedStreams(streams);
    }
  }

  private void finishDecodedStreams(final DecodedStreams streams) {
    final boolean wantsVideo = streams.includesVideo();
    final boolean wantsAudio = streams.includesAudio();
    try {
      if (wantsVideo) {
        this.frameCopier.release();
      }
    } finally {
      if (wantsVideo) {
        this.signalEnd(this.videoQueue, END_OF_VIDEO);
      }

      if (wantsAudio) {
        this.signalEnd(this.audioQueue, END_OF_AUDIO);
      }
    }
  }

  // Video timestamps come from VideoTimestamps, because some decoders, such as OpenCV's video reader, stamp every frame with zero.
  private void decodeFrames(final FrameGrabber grabber, final DecodedStreams streams) throws FrameGrabber.Exception, InterruptedException {
    this.seekToStart(grabber);

    final boolean wantsVideo = streams.includesVideo();
    final boolean wantsAudio = streams.includesAudio();
    final OriginalVideoMetadata decodedVideoMetadata = this.createVideoMetadata(grabber);
    final double frameRate = grabber.getFrameRate();
    final long length = grabber.getLengthInTime();
    final VideoTimestamps timestamps = this.createTimestamps(frameRate, length);
    final OriginalAudioMetadata decodedAudioMetadata = this.createAudioMetadata(grabber);
    @Nullable Frame frame = this.grab(grabber, streams);
    while (frame != null && this.running.get()) {
      if (wantsVideo && this.hasImage(frame)) {
        this.enqueueVideo(frame, decodedVideoMetadata, timestamps);
      }
      if (wantsAudio && this.hasSamples(frame)) {
        this.enqueueAudio(frame, decodedAudioMetadata);
      }
      frame = this.grab(grabber, streams);
    }
  }

  // A source that refuses to seek plays from where it is, and the failure is reported.
  private void seekToStart(final FrameGrabber grabber) {
    if (this.startMicroseconds <= 0) {
      return;
    }

    try {
      grabber.setTimestamp(this.startMicroseconds);
    } catch (final FrameGrabber.Exception exception) {
      this.reportIfRunning("Failed to seek, playing from the current position instead.", exception);
    }
  }

  // Media of a known length advances by its frame rate; live sources report no length.
  private VideoTimestamps createTimestamps(final double frameRate, final long length) {
    if (length <= 0) {
      return VideoTimestamps.live(this.nanosecondClock);
    }

    return VideoTimestamps.recorded(frameRate, this.nanosecondClock);
  }

  private boolean hasImage(final Frame frame) {
    final Buffer[] image = frame.image;
    return image != null && image.length > 0;
  }

  private boolean hasSamples(final Frame frame) {
    final Buffer[] samples = frame.samples;
    return samples != null && samples.length > 0;
  }

  // Grabs the next frame. FFmpeg grabbers that decode only one stream skip the other stream entirely.
  private @Nullable Frame grab(final FrameGrabber grabber, final DecodedStreams streams) throws FrameGrabber.Exception {
    if (!(grabber instanceof final FFmpegFrameGrabber ffmpeg)) {
      return grabber.grab();
    }

    return switch (streams) {
      case VIDEO -> ffmpeg.grabImage();
      case AUDIO -> ffmpeg.grabSamples();
      case VIDEO_AND_AUDIO -> ffmpeg.grab();
    };
  }

  // Sources without a video track, such as audio files, report no picture size and get the empty metadata.
  private OriginalVideoMetadata createVideoMetadata(final FrameGrabber grabber) {
    final int width = grabber.getImageWidth();
    final int height = grabber.getImageHeight();
    final int smallerSide = Math.min(width, height);
    if (smallerSide <= 0) {
      return OriginalVideoMetadata.EMPTY;
    }

    final int bitrate = grabber.getVideoBitrate();
    final float frameRate = (float) grabber.getFrameRate();
    return OriginalVideoMetadata.of(width, height, bitrate, frameRate);
  }

  // Sources without an audio track get the metadata of the output format every grabber resamples to.
  private OriginalAudioMetadata createAudioMetadata(final FrameGrabber grabber) {
    final int sampleRate = grabber.getSampleRate();
    final int channels = grabber.getAudioChannels();
    final int smaller = Math.min(sampleRate, channels);
    if (smaller <= 0) {
      return DEFAULT_AUDIO_METADATA;
    }

    final String codec = grabber.getAudioCodecName();
    final String codecName = Objects.requireNonNullElse(codec, "");
    final int bitrate = grabber.getAudioBitrate();
    final int format = grabber.getSampleFormat();
    return OriginalAudioMetadata.of(codecName, bitrate, sampleRate, channels, format);
  }

  // The picture is copied only when a pipeline wants it; otherwise the frame travels without it, so the renderer still keeps time.
  private void enqueueVideo(final Frame frame, final OriginalVideoMetadata metadata, final VideoTimestamps timestamps)
    throws InterruptedException {
    final Buffer plane = frame.image[0];
    if (!(plane instanceof ByteBuffer)) {
      return;
    }

    final @Nullable MatImageBuffer image = this.copyPictureIfWanted(frame);
    final long timestamp = timestamps.next(frame.timestamp);
    final DecodedVideoFrame decoded = new DecodedVideoFrame(image, timestamp);
    this.videoMetadata = metadata;
    this.queueFrame(this.videoQueue, decoded, this.running::get);
  }

  private @Nullable MatImageBuffer copyPictureIfWanted(final Frame frame) {
    final VideoPipelineStep step = this.videoCallback.retrieve();
    final boolean empty = step.isNoOp();
    if (empty) {
      return null;
    }

    return this.frameCopier.copy(frame);
  }

  // A put can land after the last drain, so a decoder that finds the session stopped takes its frame back and releases the picture.
  @VisibleForTesting
  void queueFrame(final BlockingQueue<DecodedVideoFrame> queue, final DecodedVideoFrame frame, final BooleanSupplier running)
    throws InterruptedException {
    try {
      queue.put(frame);
    } catch (final InterruptedException exception) {
      this.releaseImage(frame);
      throw exception;
    }

    final boolean stopped = !running.getAsBoolean();
    if (stopped && queue.remove(frame)) {
      this.releaseImage(frame);
    }
  }

  private void enqueueAudio(final Frame frame, final OriginalAudioMetadata metadata) throws InterruptedException {
    final Buffer samples = frame.samples[0];
    if (!(samples instanceof final ShortBuffer shortSamples)) {
      return;
    }

    final ByteBuffer bytes = this.copySamples(shortSamples);
    final DecodedAudioChunk chunk = new DecodedAudioChunk(bytes, frame.timestamp);
    this.audioMetadata = metadata;
    this.audioQueue.put(chunk);
  }

  private ByteBuffer copySamples(final ShortBuffer samples) {
    final ShortBuffer view = samples.duplicate();
    view.rewind();
    final int count = view.remaining();
    final ByteBuffer bytes = ByteBuffer.allocate(count * Short.BYTES);
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    final ShortBuffer target = bytes.asShortBuffer();
    target.put(view);
    return bytes;
  }

  // An interrupt must not lose the end marker; once stopping begins, stop() supplies the markers and this bounded wait ends.
  @VisibleForTesting
  <ElementType extends @NonNull Object> void signalEnd(final BlockingQueue<ElementType> queue, final ElementType marker) {
    final boolean interruptedBefore = Thread.interrupted();
    final AtomicBoolean interrupted = new AtomicBoolean(interruptedBefore);
    try {
      this.offerEndUntilStopped(queue, marker, interrupted);
    } finally {
      if (interrupted.get()) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }
  }

  private <ElementType extends @NonNull Object> void offerEndUntilStopped(
    final BlockingQueue<ElementType> queue,
    final ElementType marker,
    final AtomicBoolean interrupted
  ) {
    boolean offered = false;
    while (!offered && this.running.get()) {
      offered = this.offerEnd(queue, marker, interrupted);
    }
  }

  private <ElementType extends @NonNull Object> boolean offerEnd(
    final BlockingQueue<ElementType> queue,
    final ElementType marker,
    final AtomicBoolean interrupted
  ) {
    try {
      return queue.offer(marker, END_OFFER_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      interrupted.set(true);

      return false;
    }
  }

  private void renderVideo() {
    try {
      DecodedVideoFrame frame = this.videoQueue.take();
      while (frame != END_OF_VIDEO) {
        this.renderVideoFrame(frame);
        frame = this.videoQueue.take();
      }
    } catch (final RuntimeException | Error exception) {
      // A failed error handler or fatal filter must not leave producers blocked behind this renderer.
      this.stop();
      throw exception;
    } catch (final InterruptedException exception) {
      this.stop();
      final Thread current = Thread.currentThread();
      current.interrupt();
    } finally {
      // A decoder racing the end may have queued one more frame.
      final List<DecodedVideoFrame> leftovers = new ArrayList<>();
      this.videoQueue.drainTo(leftovers);
      leftovers.forEach(this::releaseImage);
      this.imagePool.close();
    }
  }

  private void renderVideoFrame(final DecodedVideoFrame frame) throws InterruptedException {
    final @Nullable MatImageBuffer image = frame.getImage();
    try {
      this.presentVideoFrame(frame, image);
    } finally {
      if (image != null) {
        this.imagePool.recycle(image);
      }
    }
  }

  private void presentVideoFrame(final DecodedVideoFrame frame, final @Nullable MatImageBuffer image) throws InterruptedException {
    final long timestamp = frame.getTimestampMicroseconds();
    final long lateNanoseconds = this.awaitDue(timestamp, VIDEO_LEAD_NANOSECONDS);
    final boolean late = lateNanoseconds > this.maximumVideoLagNanoseconds;
    if (late) {
      this.lateFrames.countDropped(timestamp);
      return;
    }

    this.lateFrames.countShown(timestamp);
    this.positionMicroseconds = timestamp;

    final VideoPipelineStep step = this.videoCallback.retrieve();
    final boolean empty = step.isNoOp();
    if (image == null || empty) {
      return;
    }

    this.runVideoPipeline(step, image);
  }

  private void runVideoPipeline(final VideoPipelineStep first, final MatImageBuffer image) {
    final OriginalVideoMetadata metadata = this.videoMetadata;
    try {
      this.processVideoIfRunning(first, image, metadata);
    } catch (final RuntimeException | Error exception) {
      // filters are user code that may throw a LinkageError; only a virtual machine error, which a report would hide, ends the thread
      ThrowableUtils.throwIfFatal(exception);
      this.reportIfRunning("Video filter failed.", exception);
    }
  }

  private void processVideoIfRunning(final VideoPipelineStep first, final MatImageBuffer image, final OriginalVideoMetadata metadata) {
    synchronized (this.videoCallback) {
      // The callback slot survives replacement sessions, including a replacement started by this pipeline.
      if (this.running.get()) {
        first.processAll(image, metadata);
      }
    }
  }

  @VisibleForTesting
  static void logFallingBehind(final int droppedFrames, final int frames) {
    LOGGER.warn(
      "Playback falls behind: {} of the last {} frames came too late to show; decoding or the video pipeline is slower than the video.",
      droppedFrames,
      frames
    );
  }

  private void releaseImage(final DecodedVideoFrame frame) {
    final @Nullable MatImageBuffer image = frame.getImage();
    if (image != null) {
      image.release();
    }
  }

  private void renderAudio() {
    try {
      DecodedAudioChunk chunk = this.audioQueue.take();
      while (chunk != END_OF_AUDIO) {
        this.renderAudioChunk(chunk);
        chunk = this.audioQueue.take();
      }
    } catch (final RuntimeException | Error exception) {
      // A failed error handler or fatal filter must not leave producers blocked behind this renderer.
      this.stop();
      throw exception;
    } catch (final InterruptedException exception) {
      this.stop();
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private void renderAudioChunk(final DecodedAudioChunk chunk) throws InterruptedException {
    final long timestamp = chunk.getTimestampMicroseconds();
    this.awaitDue(timestamp, this.audioLeadNanoseconds);

    final AudioPipelineStep step = this.audioCallback.retrieve();
    final boolean empty = step.isNoOp();
    if (empty) {
      return;
    }

    final ByteBuffer samples = this.resampler.resample(chunk.getSamples(), this.clock.getSpeed());
    this.runAudioPipeline(step, samples);
  }

  private void runAudioPipeline(final AudioPipelineStep first, final ByteBuffer samples) {
    final OriginalAudioMetadata metadata = this.audioMetadata;
    try {
      this.processAudioIfRunning(first, samples, metadata);
    } catch (final RuntimeException | Error exception) {
      // filters are user code that may throw a LinkageError; only a virtual machine error, which a report would hide, ends the thread
      ThrowableUtils.throwIfFatal(exception);
      this.reportIfRunning("Audio filter failed.", exception);
    }
  }

  private void processAudioIfRunning(final AudioPipelineStep first, final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    synchronized (this.audioCallback) {
      if (this.running.get()) {
        first.processAll(samples, metadata);
      }
    }
  }

  private long awaitDue(final long timestampMicroseconds, final long leadNanoseconds) throws InterruptedException {
    while (true) {
      if (this.clock.isPaused()) {
        this.clock.awaitResume(PAUSE_POLL_MILLISECONDS);
        continue;
      }
      final long dueNanoseconds = this.clock.schedule(timestampMicroseconds);
      final long startNanoseconds = dueNanoseconds - leadNanoseconds;
      final long now = this.nanosecondClock.getAsLong();
      final long remaining = startNanoseconds - now;
      if (remaining <= 0) {
        return -remaining;
      }
      final long sleep = Math.min(remaining, MAXIMUM_SLEEP_NANOSECONDS);
      LockSupport.parkNanos(sleep);

      if (Thread.interrupted()) {
        final String message = "Interrupted while waiting for media to be due!";
        throw new InterruptedException(message);
      }
    }
  }

  // Pauses the clock, so the renderers stop at their next frame.
  void pause() {
    this.clock.pause();
  }

  void resume() {
    this.clock.resume();
  }

  boolean isActive() {
    if (!this.running.get()) {
      return false;
    }

    final List<Thread> threads = this.copyThreads();
    for (final Thread thread : threads) {
      if (thread.isAlive()) {
        return true;
      }
    }

    return false;
  }

  private List<Thread> copyThreads() {
    synchronized (this.threads) {
      return List.copyOf(this.threads);
    }
  }

  // A repeated call waits for the threads unless made by one of them; joins are bounded, as native decoders see interrupts late.
  void stop() {
    final boolean wasRunning = this.running.getAndSet(false);
    final Thread currentThread = Thread.currentThread();
    final List<Thread> threads = this.copyThreads();
    if (!wasRunning) {
      final boolean ownThread = threads.contains(currentThread);
      if (!ownThread) {
        this.joinThreadsExcept(threads, currentThread);
      }

      return;
    }

    if (threads.isEmpty()) {
      final @Nullable FrameGrabber prepared = this.videoGrabber;
      this.videoGrabber = null;

      if (prepared != null) {
        this.closeQuietly(prepared);
      }

      return;
    }

    this.interruptThreadsExcept(threads, currentThread);
    // A renderer that stops its own session finishes through this marker instead of an interrupt.
    replaceWithEndMarker(this.videoQueue, END_OF_VIDEO, this::releaseImage);
    replaceWithEndMarker(this.audioQueue, END_OF_AUDIO, this::discardChunk);
    this.joinThreadsExcept(threads, currentThread);
  }

  private void interruptThreadsExcept(final List<Thread> threads, final Thread excluded) {
    for (final Thread thread : threads) {
      final boolean isExcluded = thread.equals(excluded);
      if (!isExcluded) {
        thread.interrupt();
      }
    }
  }

  // An interrupt ends the wait early and stays set.
  private void joinThreadsExcept(final List<Thread> threads, final Thread excluded) {
    for (final Thread thread : threads) {
      final boolean isExcluded = thread.equals(excluded);
      if (isExcluded) {
        continue;
      }
      try {
        thread.join(JOIN_TIMEOUT_MILLISECONDS);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
        return;
      }
    }
  }

  private void discardChunk(final DecodedAudioChunk chunk) {}

  // A running decoder may refill the queue between draining and offering, so draining repeats until the marker fits.
  @VisibleForTesting
  static <ElementType extends @NonNull Object> void replaceWithEndMarker(
    final BlockingQueue<ElementType> queue,
    final ElementType marker,
    final Consumer<? super ElementType> discarded
  ) {
    final List<ElementType> removed = new ArrayList<>();
    do {
      queue.drainTo(removed);
    } while (!queue.offer(marker));
    removed.forEach(discarded);
  }

  private void reportIfRunning(final String message, final Throwable error) {
    if (!this.running.get()) {
      return;
    }

    this.exceptionHandler.accept(message, error);
  }

  private void closeQuietly(final FrameGrabber grabber) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception | RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      // the session is done with the grabber, so a failed close changes nothing
    }
  }

  void setSpeed(final double speed) {
    this.clock.setSpeed(speed);
  }

  boolean isPaused() {
    return this.clock.isPaused();
  }

  boolean isSeekable() {
    return this.seekable;
  }

  long getPositionMicroseconds() {
    return this.positionMicroseconds;
  }

  // None may remain once the session ended: nobody would release them.
  @VisibleForTesting
  long getQueuedPictureCount() {
    final Stream<DecodedVideoFrame> frames = this.videoQueue.stream();
    final Stream<@Nullable MatImageBuffer> pictures = frames.map(DecodedVideoFrame::getImage);
    final Stream<@Nullable MatImageBuffer> present = pictures.filter(Objects::nonNull);
    return present.count();
  }

  // The decoder must release this copy once decoding ended.
  @VisibleForTesting
  boolean hasUnscaledCopy() {
    return this.frameCopier.hasUnscaledCopy();
  }

  @FunctionalInterface
  interface GrabberFactory {
    FrameGrabber create() throws FrameGrabber.Exception;
  }

  @FunctionalInterface
  interface LagListener {
    void fellBehind(final int droppedFrames, final int frames);
  }

  private enum DecodedStreams {
    VIDEO(true, false),
    AUDIO(false, true),
    VIDEO_AND_AUDIO(true, true);

    private final boolean video;
    private final boolean audio;

    DecodedStreams(final boolean video, final boolean audio) {
      this.video = video;
      this.audio = audio;
    }

    boolean includesVideo() {
      return this.video;
    }

    boolean includesAudio() {
      return this.audio;
    }
  }
}

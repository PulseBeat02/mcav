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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serial;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link PlaybackSession} with scripted grabbers, so every timing and failure case is deterministic.
 */
final class PlaybackSessionTest {

  private static final long FRAME_MICROS = 33_333L;
  private static final long TIMEOUT_MILLIS = 10_000L;
  private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MILLIS);

  private final VideoAttachableCallback videoCallback = VideoAttachableCallback.create();
  private final AudioAttachableCallback audioCallback = AudioAttachableCallback.create();
  private final DimensionAttachableCallback dimensionCallback = DimensionAttachableCallback.create();
  private final List<String> reports = Collections.synchronizedList(new ArrayList<>());

  /**
   * Records the message of a failure the session reports.
   */
  private void report(final String message, final Throwable cause) {
    this.reports.add(message);
  }

  /**
   * Creates a session on the wall clock that never drops a frame as late, so the outcome of the test does not depend
   * on the load of the machine.
   */
  private PlaybackSession session(
    final PlaybackSession.GrabberFactory video,
    final PlaybackSession.GrabberFactory audio,
    final long startMicros,
    final boolean paused
  ) {
    return this.session(video, audio, this.videoCallback, Timing.NEVER_DROPPING, startMicros, paused);
  }

  /**
   * Creates a session with the given video pipeline and timing.
   */
  private PlaybackSession session(
    final PlaybackSession.GrabberFactory video,
    final PlaybackSession.GrabberFactory audio,
    final VideoAttachableCallback videoPipeline,
    final Timing timing,
    final long startMicros,
    final boolean paused
  ) {
    return new PlaybackSession(
      video,
      audio,
      videoPipeline,
      this.audioCallback,
      this.dimensionCallback,
      this::report,
      startMicros,
      paused,
      timing.audioLeadNanos,
      timing.maxVideoLagNanos,
      timing.nanoClock
    );
  }

  /**
   * Creates a session that plays one grabber without a separate audio source.
   */
  private PlaybackSession session(final ScriptedFrameGrabber grabber, final long startMicros, final boolean paused) {
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    return this.session(factory, null, startMicros, paused);
  }

  /**
   * Creates a factory that hands out the grabber, which the session under test starts, drains, and closes.
   */
  private static PlaybackSession.GrabberFactory factoryOf(final ScriptedFrameGrabber grabber) {
    return () -> grabber;
  }

  /**
   * Creates a factory that hands out the grabber and counts how often it was asked for it.
   */
  private static PlaybackSession.GrabberFactory countingOpens(final ScriptedFrameGrabber grabber, final AtomicInteger opened) {
    return () -> {
      opened.incrementAndGet();
      return grabber;
    };
  }

  private void onVideo(final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    this.videoCallback.attach(step);
  }

  private void onAudio(final AudioFilter filter) {
    final AudioPipelineStep step = AudioPipelineStep.of(filter);
    this.audioCallback.attach(step);
  }

  /**
   * Loads the OpenCV natives before any timed test, because the first image buffer of the JVM loads them on the
   * rendering thread, where the loading cannot be interrupted and takes seconds.
   */
  @BeforeAll
  static void loadTheNativeLibrariesAheadOfTheTimedTests() {
    try (final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1)) {
      final int width = warmUp.getWidth();
      assertEquals(1, width, "the natives are loaded and work");
    }
  }

  private static void awaitCondition(final String description, final BooleanSupplier condition) throws InterruptedException {
    Polling.awaitCondition(description, TIMEOUT, condition);
  }

  private static void awaitEnd(final PlaybackSession session) throws InterruptedException {
    awaitCondition("the session finished", () -> !session.isActive());
  }

  private static List<Object> frames(final int count, final long spacingMicros) {
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      final Frame frame = ScriptedFrameGrabber.video(index * spacingMicros);
      script.add(frame);
    }
    return script;
  }

  /**
   * Creates a grabber whose script holds one video frame per timestamp.
   */
  private static ScriptedFrameGrabber videoAt(final long... timestamps) {
    final List<Object> script = new ArrayList<>();
    for (final long timestamp : timestamps) {
      final Frame frame = ScriptedFrameGrabber.video(timestamp);
      script.add(frame);
    }
    return new ScriptedFrameGrabber(4, 2, false, script);
  }

  /**
   * Creates a grabber that delivers a picture, a chunk of sound and another picture one frame later.
   */
  private static ScriptedFrameGrabber pictureSoundPicture() {
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final Frame secondPicture = ScriptedFrameGrabber.video(FRAME_MICROS);
    return ScriptedFrameGrabber.of(firstPicture, sound, secondPicture);
  }

  /**
   * Creates a video filter that records the size of every frame and the metadata passed along with it.
   */
  private static VideoFilter recordingSizes(
    final List<String> imageSizes,
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicInteger frames
  ) {
    return (image, metadata) -> {
      final int width = image.getWidth();
      final int height = image.getHeight();
      imageSizes.add(width + "x" + height);
      videoMetadata.set(metadata);
      return frames.incrementAndGet() > 0;
    };
  }

  /**
   * Attaches a video pipeline that records the size, the metadata and the number of the frames, and an audio pipeline
   * that records the metadata and the number of the chunks.
   */
  private void recordFramesAndChunks(
    final List<String> imageSizes,
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicInteger frames,
    final AtomicReference<OriginalAudioMetadata> audioMetadata,
    final AtomicInteger chunks
  ) {
    final VideoFilter recorder = recordingSizes(imageSizes, videoMetadata, frames);
    this.onVideo(recorder);
    this.onAudio((_, metadata) -> {
        audioMetadata.set(metadata);
        return chunks.incrementAndGet() > 0;
      });
  }

  /**
   * Creates a grabber with an audio track of 44.1 kHz that interleaves three pictures, one frame apart, with two chunks
   * of sound.
   */
  private static ScriptedFrameGrabber threePicturesWithSound() {
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame secondPicture = ScriptedFrameGrabber.video(FRAME_MICROS);
    final Frame secondSound = ScriptedFrameGrabber.audio(20_000L);
    final Frame thirdPicture = ScriptedFrameGrabber.video(2 * FRAME_MICROS);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstPicture, firstSound, secondPicture, secondSound, thirdPicture);
    grabber.setSampleRate(44_100);
    return grabber;
  }

  /**
   * Checks that the pipelines received the metadata of the picture and of the audio track of the grabber.
   */
  private static void assertMetadataOfTheGrabber(
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicReference<OriginalAudioMetadata> audioMetadata
  ) {
    final OriginalVideoMetadata video = videoMetadata.get();
    final OriginalAudioMetadata audio = audioMetadata.get();
    final int sampleRate = audio.getAudioSampleRate();
    final int videoWidth = video.getVideoWidth();
    assertEquals(4, videoWidth);
    assertEquals(44_100, sampleRate, "the metadata describes the audio track of the grabber");
  }

  /**
   * Checks that a session started at the beginning did not seek, cannot seek media without a length, and closed its
   * grabber.
   */
  private static void assertPlayedFromTheBeginningAndClosed(final ScriptedFrameGrabber grabber, final PlaybackSession session) {
    final long requested = grabber.getRequestedTimestamp();
    final boolean seekable = session.isSeekable();
    final boolean closed = grabber.isClosed();
    assertEquals(-1L, requested, "starting at the beginning does not seek");
    assertFalse(seekable, "media without a length cannot be seeked");
    assertTrue(closed);
  }

  @Test
  void rendersVideoAndAudioOfOneGrabber() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();
    final AtomicReference<OriginalVideoMetadata> videoMetadata = new AtomicReference<>();
    final AtomicReference<OriginalAudioMetadata> audioMetadata = new AtomicReference<>();
    final List<String> imageSizes = Collections.synchronizedList(new ArrayList<>());
    this.recordFramesAndChunks(imageSizes, videoMetadata, frames, audioMetadata, chunks);

    final ScriptedFrameGrabber grabber = threePicturesWithSound();
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final int chunkCount = chunks.get();
    final long position = session.getPositionMicros();
    final List<String> sizes = List.copyOf(imageSizes);
    final List<String> expectedSizes = List.of("4x2", "4x2", "4x2");
    assertEquals(3, frameCount);
    assertEquals(2, chunkCount);
    assertEquals(2 * FRAME_MICROS, position);
    assertMetadataOfTheGrabber(videoMetadata, audioMetadata);
    assertEquals(expectedSizes, sizes, "every frame keeps its width and height, the first one included");
    assertPlayedFromTheBeginningAndClosed(grabber, session);
    final boolean noReports = this.reports.isEmpty();
    assertTrue(noReports, this.reports::toString);
  }

  /**
   * Creates a grabber without a picture size whose script holds a chunk of sound and a picture the session must
   * ignore.
   */
  private static ScriptedFrameGrabber soundWithAnIgnoredPicture() {
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final Frame ignoredPicture = ScriptedFrameGrabber.video(0L);
    final List<Object> audioScript = List.of(sound, ignoredPicture);
    return new ScriptedFrameGrabber(0, 0, false, audioScript);
  }

  @Test
  void takesTheAudioFromASeparateGrabber() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    this.onAudio((_, _) -> chunks.incrementAndGet() > 0);

    final Frame picture = ScriptedFrameGrabber.video(0L);
    final Frame ignoredSound = ScriptedFrameGrabber.audio(0L);
    final ScriptedFrameGrabber video = ScriptedFrameGrabber.of(picture, ignoredSound);
    final ScriptedFrameGrabber audio = soundWithAnIgnoredPicture();
    final PlaybackSession session = this.session(() -> video, () -> audio, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final int chunkCount = chunks.get();
    final boolean audioClosed = audio.isClosed();
    final boolean videoClosed = video.isClosed();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(1, frameCount, "the video grabber only delivers pictures");
    assertEquals(1, chunkCount, "the audio grabber only delivers sound");
    assertTrue(audioClosed);
    assertTrue(noReports, "an audio-only grabber has no picture size, which must not fail: " + this.reports);
    assertTrue(videoClosed);
  }

  @Test
  void reportsAudioSourcesThatCannotBeStarted() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber video = videoAt(0L);
    final PlaybackSession.GrabberFactory videoFactory = factoryOf(video);
    final PlaybackSession.GrabberFactory failingAudio = () -> {
      throw new FrameGrabber.Exception("no audio");
    };
    final PlaybackSession session = this.session(videoFactory, failingAudio, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final List<String> expected = List.of("Failed to start the audio source");
    assertEquals(1, frameCount);
    assertEquals(expected, this.reports);
  }

  @Test
  void reportsDecodingFailures() throws Exception {
    final FrameGrabber.Exception corrupt = new FrameGrabber.Exception("corrupt");
    final ScriptedFrameGrabber failing = ScriptedFrameGrabber.of(corrupt);
    final PlaybackSession first = this.session(failing, 0L, false);
    first.start();
    awaitEnd(first);

    final List<Object> crash = List.of(new IllegalStateException("bug"));
    final ScriptedFrameGrabber crashing = new ScriptedFrameGrabber(4, 2, true, crash);
    final PlaybackSession second = this.session(crashing, 0L, false);
    second.start();
    awaitEnd(second);

    final List<String> expected = List.of("Failed to decode media", "Unexpected error while decoding media");
    assertEquals(expected, this.reports);
  }

  @Test
  void skipsFramesItCannotUse() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    this.onAudio((_, _) -> chunks.incrementAndGet() > 0);

    final Frame shortImage = ScriptedFrameGrabber.video(0L);
    shortImage.image = new Buffer[] { ShortBuffer.allocate(24) };
    final Frame floatSamples = ScriptedFrameGrabber.audio(0L);
    floatSamples.samples = new Buffer[] { FloatBuffer.allocate(8) };
    final Frame empty = new Frame();
    final Frame noPlanes = new Frame();
    noPlanes.image = new Buffer[0];
    noPlanes.samples = new Buffer[0];
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(shortImage, floatSamples, empty, noPlanes);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final int chunkCount = chunks.get();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(0, frameCount);
    assertEquals(0, chunkCount);
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void startsAtTheRequestedPosition() throws Exception {
    final ScriptedFrameGrabber grabber = videoAt(5_000_000L);
    final PlaybackSession session = this.session(grabber, 5_000_000L, false);
    final long initialPosition = session.getPositionMicros();
    session.start();
    awaitEnd(session);

    final long requested = grabber.getRequestedTimestamp();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(5_000_000L, initialPosition);
    assertEquals(5_000_000L, requested);
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void playsFromTheCurrentPositionWhenTheSourceCannotSeek() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROS);
    grabber.failOnSeek();
    final PlaybackSession session = this.session(grabber, 5_000_000L, false);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicros();
    final int frameCount = frames.get();
    final List<String> expected = List.of("Failed to seek, playing from the current position instead");
    assertEquals(expected, this.reports);
    assertEquals(2, frameCount, "the media still plays");
    assertEquals(FRAME_MICROS, position);
  }

  @Test
  void opensTheSourceBeforeItStartsAnyThread() throws Exception {
    final ScriptedFrameGrabber grabber = videoAt(0L);
    grabber.setLength(10_000_000L);
    final AtomicInteger opened = new AtomicInteger();
    final PlaybackSession.GrabberFactory counting = countingOpens(grabber, opened);
    final PlaybackSession session = this.session(counting, null, 0L, false);

    final boolean seekableBeforeOpening = session.isSeekable();
    session.open();
    final boolean seekable = session.isSeekable();
    final boolean activeWhileOnlyOpened = session.isActive();
    session.start();
    awaitEnd(session);

    final int openCount = opened.get();
    assertFalse(seekableBeforeOpening);
    assertTrue(seekable, "media of a known length can be seeked");
    assertFalse(activeWhileOnlyOpened, "opening starts no thread");
    assertEquals(1, openCount, "starting an opened session does not open the source again");
  }

  @Test
  void refusesToStartThreadsBeforeTheSourceIsOpened() {
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of();
    final PlaybackSession session = this.session(grabber, 0L, false);
    assertThrows(IllegalStateException.class, session::startThreads);
    final boolean active = session.isActive();
    assertFalse(active);
  }

  @Test
  void reportsFailingFiltersAndKeepsPlaying() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> {
        frames.incrementAndGet();
        throw new IllegalStateException("video filter bug");
      });
    this.onAudio((_, _) -> {
        throw new AssertionError("audio filter bug");
      });
    final ScriptedFrameGrabber grabber = pictureSoundPicture();
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final boolean videoReported = this.reports.contains("Video filter failed");
    final boolean audioReported = this.reports.contains("Audio filter failed");
    assertEquals(2, frameCount);
    assertTrue(videoReported);
    assertTrue(audioReported);
  }

  @Test
  void skipsTheWorkWhenNoPipelineIsAttached() throws Exception {
    final ScriptedFrameGrabber grabber = pictureSoundPicture();
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicros();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(FRAME_MICROS, position, "the position advances even when nobody watches");
    assertTrue(noReports);
  }

  @Test
  void doesNotCopyPicturesWhileNoPipelineIsAttached() throws Exception {
    // the rows of this frame are shorter than its width, so copying its picture would fail
    final Frame unreadable = ScriptedFrameGrabber.video(0L);
    unreadable.imageStride = 3;
    final Frame last = ScriptedFrameGrabber.video(400_000L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(unreadable, last);
    final PlaybackSession session = this.session(grabber, 0L, false);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsedNanos = end - start;
    final long position = session.getPositionMicros();
    final boolean noReports = this.reports.isEmpty();
    assertTrue(noReports, "the picture was never copied: " + this.reports);
    assertEquals(400_000L, position, "the frames still move the position");
    assertTrue(elapsedNanos >= 350_000_000L, "the frames still keep time, took " + elapsedNanos);
  }

  @Test
  void copiesPicturesOnlyForAnAttachedPipeline() throws Exception {
    this.onVideo((_, _) -> true);
    final Frame unreadable = ScriptedFrameGrabber.video(0L);
    unreadable.imageStride = 3;
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(unreadable);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final List<String> expected = List.of("Unexpected error while decoding media");
    assertEquals(expected, this.reports);
  }

  @Test
  void reusesPooledImagesForLaterFrames() throws Exception {
    final Map<ImageBuffer, Boolean> seen = Collections.synchronizedMap(new IdentityHashMap<>());
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((image, _) -> {
        seen.put(image, Boolean.TRUE);
        return frames.incrementAndGet() > 0;
      });
    final List<Object> script = frames(24, 5_000L);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(4, 2, false, script);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final Set<ImageBuffer> distinct = seen.keySet();
    final int images = distinct.size();
    final int frameCount = frames.get();
    assertEquals(24, frameCount);
    assertTrue(images <= 6, "at most as many images as frames can be in flight are allocated, got " + images);
  }

  @Test
  void scalesFramesToTheAttachedSizeWhenTheDecoderDidNot() throws Exception {
    final List<String> sizes = Collections.synchronizedList(new ArrayList<>());
    this.onVideo((image, _) -> {
        final int width = image.getWidth();
        final int height = image.getHeight();
        return sizes.add(width + "x" + height);
      });
    final Dimension size = Dimension.of(2, 1);
    this.dimensionCallback.attach(size);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROS);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final List<String> expected = List.of("2x1", "2x1");
    assertEquals(expected, sizes);
  }

  @Test
  void pacesFramesWithoutTimestampsByTheFrameRate() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    // the video reader of OpenCV stamps every frame with zero
    final List<Object> script = frames(5, 0L);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(4, 2, false, script);
    grabber.setFrameRate(10.0);
    grabber.setLength(500_000L);
    final PlaybackSession session = this.session(grabber, 0L, false);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsedNanos = end - start;
    final long position = session.getPositionMicros();
    final int frameCount = frames.get();
    assertEquals(5, frameCount, "every frame is shown");
    assertEquals(400_000L, position, "every frame is one period of 10 frames per second after the previous one");
    assertTrue(elapsedNanos >= 350_000_000L, "the frames are paced instead of racing by, took " + elapsedNanos);
  }

  @Test
  void showsFramesOfLiveSourcesWithoutTimestampsAsTheyArrive() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      final Frame frame = ScriptedFrameGrabber.video(0L);
      script.add(frame);
      script.add(new ScriptedFrameGrabber.Delay(40L));
    }
    final ScriptedFrameGrabber camera = new ScriptedFrameGrabber(4, 2, false, script);
    camera.setFrameRate(30.0);
    final PlaybackSession session = this.session(camera, 0L, false);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicros();
    final int frameCount = frames.get();
    assertEquals(5, frameCount, "every frame that arrives in real time is shown");
    assertTrue(position >= 120_000L, "the position follows the time the frames arrived at, got " + position);
  }

  /**
   * Creates a session that plays one grabber from the beginning and takes its video pipeline from the given callback
   * instead of the callback of the test.
   */
  private PlaybackSession sessionWithVideoCallback(final ScriptedFrameGrabber grabber, final VideoAttachableCallback callback) {
    final PlaybackSession.GrabberFactory videoFactory = factoryOf(grabber);
    return this.session(videoFactory, null, callback, Timing.NEVER_DROPPING, 0L, false);
  }

  @Test
  void handsThePictureBackWhenThePipelineIsDetachedBeforeTheFrameIsShown() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final VideoFilter counter = (_, _) -> frames.incrementAndGet() > 0;
    final VideoPipelineStep counting = VideoPipelineStep.of(counter);
    final VideoAttachableCallback detaching = mock(VideoAttachableCallback.class);
    // the decoder still sees the pipeline, but the renderer finds it detached
    when(detaching.retrieve()).thenReturn(counting).thenReturn(VideoPipelineStep.NO_OP);
    final ScriptedFrameGrabber grabber = videoAt(FRAME_MICROS);
    final PlaybackSession session = this.sessionWithVideoCallback(grabber, detaching);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicros();
    final int frameCount = frames.get();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(0, frameCount, "a detached pipeline is not called");
    assertEquals(FRAME_MICROS, position);
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void dropsFramesThatAreTooLate() throws Exception {
    // the session runs on a clock only the filter moves, so the first frame is exactly on time and the others are
    // late by exactly the time the filter took, whatever the load of the machine
    final AtomicLong now = new AtomicLong();
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> {
        final int frameNumber = frames.incrementAndGet();
        if (frameNumber == 1) {
          now.addAndGet(300_000_000L);
        }
        return true;
      });
    final Timing controlled = new Timing(PlaybackSession.AUDIO_LEAD_NANOS, PlaybackSession.MAX_VIDEO_LAG_NANOS, now::get);
    final List<Object> script = frames(6, FRAME_MICROS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(4, 2, false, script);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    final PlaybackSession session = this.session(factory, null, this.videoCallback, controlled, 0L, false);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    assertEquals(1, frameCount, "every frame after the filter that took 300 ms is more than 100 ms late");
  }

  @Test
  void jumpsInTheTimestampsDoNotStallPlayback() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, 30_000_000L);
    final PlaybackSession session = this.session(grabber, 0L, false);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsed = end - start;
    final int frameCount = frames.get();
    assertEquals(2, frameCount);
    assertTrue(elapsed < 3_000_000_000L, "a jump of 30 seconds is not waited out");
  }

  @Test
  void startsPausedAndResumesOnRequest() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROS);
    final PlaybackSession session = this.session(grabber, 0L, true);
    session.start();
    // once the decoder is done, both frames wait in the queue, and only the pause keeps them from being shown
    awaitCondition("the decoder queued every frame", grabber::isClosed);

    final int whilePaused = frames.get();
    final boolean paused = session.isPaused();
    session.resume();
    awaitEnd(session);
    session.pause();
    final int frameCount = frames.get();
    final boolean pausedAgain = session.isPaused();
    assertEquals(0, whilePaused);
    assertTrue(paused);
    assertEquals(2, frameCount);
    assertTrue(pausedAgain);
  }

  @Test
  void stopsWhileWaitingForTheNextFrame() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    this.onAudio((_, _) -> true);
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame latePicture = ScriptedFrameGrabber.video(1_500_000L);
    final Frame lateSound = ScriptedFrameGrabber.audio(1_500_000L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstPicture, firstSound, latePicture, lateSound);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitCondition("the first frame was rendered", () -> frames.get() == 1);
    awaitCondition("the decoder queued the second frame", grabber::isClosed);

    final boolean activeBeforeStop = session.isActive();
    final long stopStart = System.nanoTime();
    session.stop();
    final long stopEnd = System.nanoTime();
    final long stopTime = stopEnd - stopStart;
    session.stop();
    final boolean activeAfterStop = session.isActive();
    final int frameCount = frames.get();
    assertTrue(activeBeforeStop);
    assertFalse(activeAfterStop);
    assertEquals(1, frameCount);
    assertTrue(stopTime < 1_000_000_000L, "stopping interrupts the waiting threads");
  }

  /**
   * Creates a video filter that counts down the latch and then blocks until its thread is interrupted, keeping the
   * interrupt.
   */
  private static VideoFilter blockingUntilInterrupted(final CountDownLatch rendering) {
    final CountDownLatch never = new CountDownLatch(1);
    return (_, _) -> {
      rendering.countDown();
      try {
        never.await();
      } catch (final InterruptedException exception) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
      }
      return true;
    };
  }

  @Test
  void stopsWhileTheDecoderWaitsForRoomInTheQueue() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final VideoFilter blocking = blockingUntilInterrupted(rendering);
    this.onVideo(blocking);
    final List<Object> script = frames(20, FRAME_MICROS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(4, 2, false, script);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    final boolean rendered = rendering.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertTrue(rendered);
    // one frame is rendered, four wait in the queue, and the decoder blocks on the sixth
    awaitCondition("the queue is full", () -> grabber.getRemainingSteps() == 14);

    final long stopStart = System.nanoTime();
    session.stop();
    final long stopEnd = System.nanoTime();
    final long stopTime = stopEnd - stopStart;
    awaitCondition("the decoder closed its grabber", grabber::isClosed);
    final boolean active = session.isActive();
    assertFalse(active);
    assertTrue(stopTime < 1_000_000_000L, "the blocked decoder is interrupted, took " + stopTime);
  }

  /**
   * Creates a video filter that counts its frames and stops the session in the holder, recording how long stopping
   * took.
   */
  private static VideoFilter stoppingItsOwnSession(
    final AtomicReference<PlaybackSession> holder,
    final AtomicInteger frames,
    final AtomicLong stopNanos
  ) {
    return (_, _) -> {
      frames.incrementAndGet();
      final long stopStart = System.nanoTime();
      final PlaybackSession own = holder.get();
      own.stop();
      final long stopEnd = System.nanoTime();
      stopNanos.set(stopEnd - stopStart);
      return true;
    };
  }

  @Test
  void canBeStoppedFromItsOwnFilter() throws Exception {
    final AtomicReference<PlaybackSession> holder = new AtomicReference<>();
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong stopNanos = new AtomicLong(-1L);
    final VideoFilter stopping = stoppingItsOwnSession(holder, frames, stopNanos);
    this.onVideo(stopping);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROS);
    final PlaybackSession session = this.session(grabber, 0L, false);
    holder.set(session);
    session.start();
    awaitCondition("the filter stopped the session", () -> stopNanos.get() >= 0 && grabber.isClosed());

    final long stopTime = stopNanos.get();
    final boolean closed = grabber.isClosed();
    final boolean active = session.isActive();
    final int frameCount = frames.get();
    assertFalse(active);
    assertTrue(closed, "the decoder finished");
    assertTrue(stopTime < 1_000_000_000L, "stopping from a filter does not wait for its own thread, took " + stopTime);
    assertEquals(1, frameCount, "frames queued before the stop are discarded");
  }

  @Test
  void keepsTheInterruptWhenStoppedFromAnInterruptedThread() throws Exception {
    final ScriptedFrameGrabber.Delay stuck = new ScriptedFrameGrabber.Delay(500L);
    final Frame picture = ScriptedFrameGrabber.video(0L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(stuck, picture);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();

    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    session.stop();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted, "the interrupt must be restored");
    awaitCondition("the stuck decoder finished and closed its grabber", grabber::isClosed);
  }

  @Test
  void reportsNothingAfterItWasStopped() throws Exception {
    final ScriptedFrameGrabber.Delay stuck = new ScriptedFrameGrabber.Delay(300L);
    final FrameGrabber.Exception lateFailure = new FrameGrabber.Exception("late failure");
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(stuck, lateFailure);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    session.stop();
    // the decoder closes its grabber only after it threw the late failure
    awaitCondition("the decoder hit the failure", grabber::isClosed);

    final boolean noReports = this.reports.isEmpty();
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void describesSourcesWithoutSoundWithTheOutputFormat() throws Exception {
    final AtomicReference<OriginalAudioMetadata> audioMetadata = new AtomicReference<>();
    this.onAudio((_, metadata) -> {
        audioMetadata.set(metadata);
        return true;
      });
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(sound);
    grabber.setSampleRate(0);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final OriginalAudioMetadata metadata = audioMetadata.get();
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    assertEquals(AudioFilter.SAMPLE_RATE, sampleRate);
    assertEquals(AudioFilter.CHANNELS, channels);
  }

  @Test
  void failsToStartWhenTheVideoGrabberCannotBeCreated() {
    final PlaybackSession.GrabberFactory missing = () -> {
      throw new FrameGrabber.Exception("missing");
    };
    final PlaybackSession session = this.session(missing, null, 0L, false);
    assertThrows(FrameGrabber.Exception.class, session::start);
    final boolean active = session.isActive();
    assertFalse(active);
  }

  @Test
  void handsTheSamplesOnAsLittleEndianBytes() throws Exception {
    final AtomicReference<byte[]> received = new AtomicReference<>();
    this.onAudio((samples, _) -> {
        final ByteBuffer view = samples.duplicate();
        final int remaining = view.remaining();
        final byte[] bytes = new byte[remaining];
        view.get(bytes);
        received.set(bytes);
        return true;
      });
    final Frame chunk = ScriptedFrameGrabber.audio(0L);
    final short[] samples = { 0x0102, (short) 0xFFFE };
    final ShortBuffer sampleBuffer = ShortBuffer.wrap(samples);
    chunk.samples = new Buffer[] { sampleBuffer };
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(chunk);
    final PlaybackSession session = this.session(grabber, 0L, false);
    session.start();
    awaitEnd(session);

    final byte[] bytes = received.get();
    assertArrayEquals(new byte[] { 0x02, 0x01, (byte) 0xFE, (byte) 0xFF }, bytes, "the pipeline carries little-endian samples");
  }

  /**
   * Creates a session that plays one grabber from the beginning and hands audio to its pipeline 400 ms before it is
   * due.
   */
  private PlaybackSession sessionWithAudioLead(final ScriptedFrameGrabber grabber) {
    final long audioLeadNanos = 400_000_000L;
    final Timing earlyAudio = new Timing(audioLeadNanos, Long.MAX_VALUE, System::nanoTime);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    return this.session(factory, null, this.videoCallback, earlyAudio, 0L, false);
  }

  @Test
  void handsAudioToThePipelineAheadOfItsTimestamp() throws Exception {
    final List<Long> deliveries = Collections.synchronizedList(new ArrayList<>());
    this.onAudio((_, _) -> {
        final long now = System.nanoTime();
        return deliveries.add(now);
      });
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame laterSound = ScriptedFrameGrabber.audio(500_000L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstSound, laterSound);
    // with a lead of 400 ms, the chunk due 500 ms after the first one goes to the pipeline about 100 ms after it
    final PlaybackSession session = this.sessionWithAudioLead(grabber);
    session.start();
    awaitEnd(session);

    final long first = deliveries.getFirst();
    final long second = deliveries.get(1);
    final long gap = second - first;
    assertTrue(gap < 450_000_000L, "the chunk is handed on ahead of its timestamp, gap " + gap);
  }

  @Test
  void replacesTheQueueWithTheEndMarkerEvenWhenADecoderRefillsIt() {
    final RefillingQueue queue = new RefillingQueue();
    queue.add("frame");
    final List<String> discarded = new ArrayList<>();
    PlaybackSession.replaceWithEndMarker(queue, "end", discarded::add);

    final List<String> remaining = new ArrayList<>(queue);
    final List<String> expectedRemaining = List.of("end");
    final List<String> expectedDiscarded = List.of("frame", "late frame");
    assertEquals(expectedRemaining, remaining);
    assertEquals(expectedDiscarded, discarded, "every removed element is handed on, so it can be released");
  }

  /**
   * The timing a test session runs with: how early audio is handed on, how late a frame may be, and the clock.
   */
  private static final class Timing {

    /**
     * The wall clock and the audio lead of the players, with a lag no frame can exceed, so frames are never dropped
     * as late however loaded the machine is.
     */
    static final Timing NEVER_DROPPING = new Timing(PlaybackSession.AUDIO_LEAD_NANOS, Long.MAX_VALUE, System::nanoTime);

    private final long audioLeadNanos;
    private final long maxVideoLagNanos;
    private final LongSupplier nanoClock;

    Timing(final long audioLeadNanos, final long maxVideoLagNanos, final LongSupplier nanoClock) {
      this.audioLeadNanos = audioLeadNanos;
      this.maxVideoLagNanos = maxVideoLagNanos;
      this.nanoClock = nanoClock;
    }
  }

  /**
   * A queue with room for one element that is refilled once right after it is drained, like a decoder racing the
   * shutdown would.
   */
  private static final class RefillingQueue extends ArrayBlockingQueue<String> {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean refilled;

    RefillingQueue() {
      super(1);
    }

    @Override
    public int drainTo(final Collection<? super String> target) {
      final int drained = super.drainTo(target);
      if (!this.refilled) {
        this.refilled = true;
        this.offer("late frame");
      }
      return drained;
    }
  }
}

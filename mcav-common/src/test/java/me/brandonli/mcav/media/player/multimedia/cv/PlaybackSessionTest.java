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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Uninterruptibles;
import java.io.Serial;
import java.lang.reflect.Field;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

// Tests PlaybackSession with scripted grabbers, so every timing and failure case is deterministic.
final class PlaybackSessionTest {

  private static final long FRAME_MICROSECONDS = 33_333L;
  private static final long TIMEOUT_MILLISECONDS = 10_000L;
  private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MILLISECONDS);

  private static final int FRAME_WIDTH = 4;
  private static final int FRAME_HEIGHT = 2;

  private static final String SIZE_SEPARATOR = "x";
  private static final int SCALED_WIDTH = 2;
  private static final String SCALED_SIZE = "2x1";

  private static final int SAMPLE_RATE = 44_100;

  private static final long REQUESTED_POSITION = 5_000_000L;

  private static final long LAST_FRAME_POSITION = 400_000L;
  private static final long PACED_NANOSECONDS = 350_000_000L;

  private static final long PROMPT_STOP_NANOSECONDS = 1_000_000_000L;
  private static final long STALL_MILLISECONDS = 1_000L;

  private static final long EARLY_AUDIO_NANOSECONDS = 400_000_000L;
  private static final long LATER_SOUND_TIMESTAMP = 500_000L;

  private static final String LATE_FRAME = "late frame";

  private static final int SOUND_COUNT = 100;

  private final VideoAttachableCallback videoCallback = VideoAttachableCallback.create();
  private final AudioAttachableCallback audioCallback = AudioAttachableCallback.create();
  private final DimensionAttachableCallback dimensionCallback = DimensionAttachableCallback.create();
  private final List<String> reports = Collections.synchronizedList(new ArrayList<>());

  private void report(final String message, final Throwable cause) {
    this.reports.add(message);
  }

  // Creates a session on the wall clock that never drops a frame as late, so the outcome of the test does not depend on
  // the load of the machine.
  private PlaybackSession session(
    final PlaybackSession.GrabberFactory video,
    final PlaybackSession.GrabberFactory audio,
    final long startMicroseconds,
    final StartState startState
  ) {
    return this.session(video, audio, this.videoCallback, Timing.NEVER_DROPPING, startMicroseconds, startState);
  }

  private PlaybackSession session(
    final PlaybackSession.GrabberFactory video,
    final PlaybackSession.GrabberFactory audio,
    final VideoAttachableCallback videoPipeline,
    final Timing timing,
    final long startMicroseconds,
    final StartState startState
  ) {
    return new PlaybackSession(
      video,
      audio,
      videoPipeline,
      this.audioCallback,
      this.dimensionCallback,
      this::report,
      startMicroseconds,
      startState,
      timing.audioLeadNanoseconds,
      timing.maximumVideoLagNanoseconds,
      timing.nanosecondClock
    );
  }

  private PlaybackSession session(final ScriptedFrameGrabber grabber, final long startMicroseconds, final StartState startState) {
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    return this.session(factory, null, startMicroseconds, startState);
  }

  private static PlaybackSession.GrabberFactory factoryOf(final ScriptedFrameGrabber grabber) {
    return () -> grabber;
  }

  private static PlaybackSession.GrabberFactory countingOpens(final ScriptedFrameGrabber grabber, final AtomicInteger opened) {
    return () -> countOpen(grabber, opened);
  }

  private static FrameGrabber countOpen(final ScriptedFrameGrabber grabber, final AtomicInteger opened) {
    opened.incrementAndGet();
    return grabber;
  }

  private void onVideo(final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    this.videoCallback.attach(step);
  }

  private void onAudio(final AudioFilter filter) {
    final AudioPipelineStep step = AudioPipelineStep.of(filter);
    this.audioCallback.attach(step);
  }

  // Loads the OpenCV natives before any timed test, because the first image buffer of the JVM loads them on the
  // rendering thread, where the loading cannot be interrupted and takes seconds.
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

  private static List<Object> frames(final int count, final long spacingMicroseconds) {
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      final Frame frame = ScriptedFrameGrabber.video(index * spacingMicroseconds);
      script.add(frame);
    }

    return script;
  }

  private static ScriptedFrameGrabber videoAt(final long... timestamps) {
    final List<Object> script = new ArrayList<>();
    for (final long timestamp : timestamps) {
      final Frame frame = ScriptedFrameGrabber.video(timestamp);
      script.add(frame);
    }

    return new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
  }

  private static ScriptedFrameGrabber pictureSoundPicture() {
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final Frame secondPicture = ScriptedFrameGrabber.video(FRAME_MICROSECONDS);
    return ScriptedFrameGrabber.of(firstPicture, sound, secondPicture);
  }

  private static VideoFilter recordingSizes(
    final List<String> imageSizes,
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicInteger frames
  ) {
    return (image, metadata) -> recordSizeAndMetadata(imageSizes, videoMetadata, frames, image, metadata);
  }

  private static boolean recordSizeAndMetadata(
    final List<String> imageSizes,
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicInteger frames,
    final ImageBuffer image,
    final OriginalVideoMetadata metadata
  ) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    imageSizes.add(width + SIZE_SEPARATOR + height);
    videoMetadata.set(metadata);
    return frames.incrementAndGet() > 0;
  }

  private void recordFramesAndChunks(
    final List<String> imageSizes,
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicInteger frames,
    final AtomicReference<OriginalAudioMetadata> audioMetadata,
    final AtomicInteger chunks
  ) {
    final VideoFilter recorder = recordingSizes(imageSizes, videoMetadata, frames);
    this.onVideo(recorder);
    this.onAudio((_, metadata) -> this.recordChunk(audioMetadata, chunks, metadata));
  }

  private boolean recordChunk(
    final AtomicReference<OriginalAudioMetadata> audioMetadata,
    final AtomicInteger chunks,
    final OriginalAudioMetadata metadata
  ) {
    audioMetadata.set(metadata);
    return chunks.incrementAndGet() > 0;
  }

  private static ScriptedFrameGrabber threePicturesWithSound() {
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame secondPicture = ScriptedFrameGrabber.video(FRAME_MICROSECONDS);
    final Frame secondSound = ScriptedFrameGrabber.audio(20_000L);
    final Frame thirdPicture = ScriptedFrameGrabber.video(2 * FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstPicture, firstSound, secondPicture, secondSound, thirdPicture);
    grabber.setSampleRate(SAMPLE_RATE);
    return grabber;
  }

  private static void assertMetadataOfTheGrabber(
    final AtomicReference<OriginalVideoMetadata> videoMetadata,
    final AtomicReference<OriginalAudioMetadata> audioMetadata
  ) {
    final OriginalVideoMetadata video = videoMetadata.get();
    final OriginalAudioMetadata audio = audioMetadata.get();
    final int sampleRate = audio.getAudioSampleRate();
    final int videoWidth = video.getVideoWidth();
    assertEquals(FRAME_WIDTH, videoWidth);
    assertEquals(SAMPLE_RATE, sampleRate, "the metadata describes the audio track of the grabber");
  }

  private static void assertPlayedFromTheBeginningAndClosed(final ScriptedFrameGrabber grabber, final PlaybackSession session) {
    final long requested = grabber.getRequestedTimestamp();
    final boolean seekable = session.isSeekable();
    final boolean closed = grabber.isClosed();
    assertEquals(-1L, requested, "starting at the beginning does not seek");
    assertFalse(seekable, "media without a length cannot be seeked");
    assertTrue(closed);
  }

  @Test
  void shouldMakeTheThreadsOfASessionDaemonsWhateverThreadStartsIt() throws Exception {
    final PlaybackSession session = this.session(threePicturesWithSound(), 0L, StartState.PAUSED);
    final AtomicReference<Exception> failure = new AtomicReference<>();
    final Thread starter = Thread.ofPlatform()
      .daemon(false)
      .start(() -> this.startRecordingFailure(session, failure));
    starter.join();
    try {
      final Exception reported = failure.get();
      assertEquals(null, reported);

      final List<Thread> threads = Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.getName().startsWith("mcav-decode-") || thread.getName().startsWith("mcav-render-"))
        .toList();
      final boolean noThreads = threads.isEmpty();
      assertFalse(noThreads, "the session runs its threads");

      for (final Thread thread : threads) {
        final boolean daemon = thread.isDaemon();
        assertTrue(daemon, thread.getName() + " would keep the JVM alive");
      }
    } finally {
      session.stop();
    }
  }

  private void startRecordingFailure(final PlaybackSession session, final AtomicReference<Exception> failure) {
    try {
      session.start();
    } catch (final Exception exception) {
      failure.set(exception);
    }
  }

  @Test
  void shouldRenderVideoAndAudioOfOneGrabber() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();
    final AtomicReference<OriginalVideoMetadata> videoMetadata = new AtomicReference<>();
    final AtomicReference<OriginalAudioMetadata> audioMetadata = new AtomicReference<>();
    final List<String> imageSizes = Collections.synchronizedList(new ArrayList<>());
    this.recordFramesAndChunks(imageSizes, videoMetadata, frames, audioMetadata, chunks);

    final ScriptedFrameGrabber grabber = threePicturesWithSound();
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final int chunkCount = chunks.get();
    final long position = session.getPositionMicroseconds();
    final List<String> sizes = List.copyOf(imageSizes);
    final List<String> expectedSizes = List.of("4x2", "4x2", "4x2");
    assertEquals(3, frameCount);
    assertEquals(2, chunkCount);
    assertEquals(2 * FRAME_MICROSECONDS, position);
    assertMetadataOfTheGrabber(videoMetadata, audioMetadata);
    assertEquals(expectedSizes, sizes, "every frame keeps its width and height, the first one included");
    assertPlayedFromTheBeginningAndClosed(grabber, session);

    final boolean noReports = this.reports.isEmpty();
    assertTrue(noReports, this.reports::toString);
  }

  // Creates a grabber without a picture size whose script holds a chunk of sound and a picture the session must ignore.
  private static ScriptedFrameGrabber soundWithAnIgnoredPicture() {
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final Frame ignoredPicture = ScriptedFrameGrabber.video(0L);
    final List<Object> audioScript = List.of(sound, ignoredPicture);
    return new ScriptedFrameGrabber(0, 0, audioScript);
  }

  @Test
  void shouldTakeTheAudioFromASeparateGrabber() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    this.onAudio((_, _) -> chunks.incrementAndGet() > 0);

    final Frame picture = ScriptedFrameGrabber.video(0L);
    final Frame ignoredSound = ScriptedFrameGrabber.audio(0L);
    final ScriptedFrameGrabber video = ScriptedFrameGrabber.of(picture, ignoredSound);
    final ScriptedFrameGrabber audio = soundWithAnIgnoredPicture();
    final PlaybackSession session = this.session(() -> video, () -> audio, 0L, StartState.PLAYING);
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
  void shouldPlayTheSeparateAudioAfterThePicturesEnd() throws Exception {
    final AtomicInteger chunks = new AtomicInteger();
    this.onVideo((_, _) -> true);
    this.onAudio((_, _) -> chunks.incrementAndGet() > 0);

    final ScriptedFrameGrabber video = videoAt(0L);
    final ScriptedFrameGrabber.Delay late = new ScriptedFrameGrabber.Delay(STALL_MILLISECONDS);
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame secondSound = ScriptedFrameGrabber.audio(FRAME_MICROSECONDS);
    final ScriptedFrameGrabber audio = new ScriptedFrameGrabber(0, 0, List.of(late, firstSound, secondSound));
    final PlaybackSession session = this.session(() -> video, () -> audio, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int chunkCount = chunks.get();
    assertEquals(2, chunkCount, "the end of the pictures does not end the sound of the separate source");
  }

  @Test
  void shouldReportAudioSourcesThatCannotBeStarted() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber video = videoAt(0L);
    final PlaybackSession.GrabberFactory videoFactory = factoryOf(video);
    final PlaybackSession.GrabberFactory failingAudio = this::failToOpenAudio;
    final PlaybackSession session = this.session(videoFactory, failingAudio, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final List<String> expected = List.of("Failed to start the audio source");
    assertEquals(1, frameCount);
    assertEquals(expected, this.reports);
  }

  private FrameGrabber failToOpenAudio() throws FrameGrabber.Exception {
    throw new FrameGrabber.Exception("no audio");
  }

  @Test
  void shouldReportDecodingFailures() throws Exception {
    final FrameGrabber.Exception corrupt = new FrameGrabber.Exception("corrupt");
    final ScriptedFrameGrabber failing = ScriptedFrameGrabber.of(corrupt);
    final PlaybackSession first = this.session(failing, 0L, StartState.PLAYING);
    first.start();
    awaitEnd(first);

    final List<Object> crash = List.of(new IllegalStateException("bug!"));
    final ScriptedFrameGrabber crashing = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, crash);
    crashing.failOnStop();
    final PlaybackSession second = this.session(crashing, 0L, StartState.PLAYING);
    second.start();
    awaitEnd(second);

    final List<String> expected = List.of("Failed to decode media", "Unexpected error while decoding media");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldSkipFramesItCannotUse() throws Exception {
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
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
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
  void shouldStartAtTheRequestedPosition() throws Exception {
    final ScriptedFrameGrabber grabber = videoAt(REQUESTED_POSITION);
    final PlaybackSession session = this.session(grabber, REQUESTED_POSITION, StartState.PLAYING);
    final long initialPosition = session.getPositionMicroseconds();
    session.start();
    awaitEnd(session);

    final long requested = grabber.getRequestedTimestamp();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(REQUESTED_POSITION, initialPosition);
    assertEquals(REQUESTED_POSITION, requested);
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void shouldPlayFromTheCurrentPositionWhenTheSourceCannotSeek() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROSECONDS);
    grabber.failOnSeek();
    final PlaybackSession session = this.session(grabber, REQUESTED_POSITION, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicroseconds();
    final int frameCount = frames.get();
    final List<String> expected = List.of("Failed to seek, playing from the current position instead");
    assertEquals(expected, this.reports);
    assertEquals(2, frameCount, "the media still plays");
    assertEquals(FRAME_MICROSECONDS, position);
  }

  @Test
  void shouldOpenTheSourceBeforeItStartsAnyThread() throws Exception {
    final ScriptedFrameGrabber grabber = videoAt(0L);
    grabber.setLength(10_000_000L);
    final AtomicInteger opened = new AtomicInteger();
    final PlaybackSession.GrabberFactory counting = countingOpens(grabber, opened);
    final PlaybackSession session = this.session(counting, null, 0L, StartState.PLAYING);

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
  void shouldRefuseToStartThreadsBeforeTheSourceIsOpened() {
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of();
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    assertThrows(IllegalStateException.class, session::startThreads);

    final boolean active = session.isActive();
    assertFalse(active);
  }

  @Test
  void shouldReportFailingFiltersAndKeepPlaying() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> this.failAfterCountingFrame(frames));
    this.onAudio((_, _) -> this.failAudioFilter());
    final ScriptedFrameGrabber grabber = pictureSoundPicture();
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    final boolean videoReported = this.reports.contains("Video filter failed");
    final boolean audioReported = this.reports.contains("Audio filter failed");
    assertEquals(2, frameCount);
    assertTrue(videoReported);
    assertTrue(audioReported);
  }

  private boolean failAfterCountingFrame(final AtomicInteger frames) {
    frames.incrementAndGet();
    throw new IllegalStateException("video filter bug!");
  }

  private boolean failAudioFilter() {
    throw new AssertionError("audio filter bug!");
  }

  @Test
  void shouldSkipTheWorkWhenNoPipelineIsAttached() throws Exception {
    final ScriptedFrameGrabber grabber = pictureSoundPicture();
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicroseconds();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(FRAME_MICROSECONDS, position, "the position advances even when nobody watches");
    assertTrue(noReports);
  }

  @Test
  void shouldNotCopyPicturesWhileNoPipelineIsAttached() throws Exception {
    final Frame unreadable = ScriptedFrameGrabber.video(0L);
    unreadable.imageStride = 3;
    final Frame last = ScriptedFrameGrabber.video(LAST_FRAME_POSITION);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(unreadable, last);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsedNanoseconds = end - start;
    final long position = session.getPositionMicroseconds();
    final boolean noReports = this.reports.isEmpty();
    assertTrue(noReports, "the picture was never copied: " + this.reports);
    assertEquals(LAST_FRAME_POSITION, position, "the frames still move the position");

    final boolean keptTime = elapsedNanoseconds >= PACED_NANOSECONDS;
    assertTrue(keptTime, "the frames still keep time, took " + elapsedNanoseconds);
  }

  @Test
  void shouldCopyPicturesOnlyForAnAttachedPipeline() throws Exception {
    this.onVideo((_, _) -> true);
    final Frame unreadable = ScriptedFrameGrabber.video(0L);
    unreadable.imageStride = 3;
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(unreadable);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final List<String> expected = List.of("Unexpected error while decoding media");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldReusePooledImagesForLaterFrames() throws Exception {
    final Map<ImageBuffer, Boolean> seen = Collections.synchronizedMap(new IdentityHashMap<>());
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((image, _) -> this.recordImage(seen, frames, image));
    final List<Object> script = frames(24, 5_000L);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final Set<ImageBuffer> distinct = seen.keySet();
    final int images = distinct.size();
    final int frameCount = frames.get();
    assertEquals(24, frameCount);

    final boolean fewImages = images <= 6;
    assertTrue(fewImages, "at most as many images as frames can be in flight are allocated, got " + images);
  }

  private boolean recordImage(final Map<ImageBuffer, Boolean> seen, final AtomicInteger frames, final ImageBuffer image) {
    seen.put(image, Boolean.TRUE);
    return frames.incrementAndGet() > 0;
  }

  @Test
  void shouldScaleFramesToTheAttachedSizeWhenTheDecoderDidNot() throws Exception {
    final List<String> sizes = Collections.synchronizedList(new ArrayList<>());
    this.onVideo((image, _) -> this.recordImageSize(sizes, image));
    final Dimension size = Dimension.of(SCALED_WIDTH, 1);
    this.dimensionCallback.attach(size);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROSECONDS);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final List<String> expected = List.of(SCALED_SIZE, SCALED_SIZE);
    assertEquals(expected, sizes);
  }

  private boolean recordImageSize(final List<String> sizes, final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    return sizes.add(width + SIZE_SEPARATOR + height);
  }

  @Test
  void shouldPaceFramesWithoutTimestampsByTheFrameRate() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final List<Object> script = frames(5, 0L);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    grabber.setFrameRate(10.0);
    grabber.setLength(500_000L);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsedNanoseconds = end - start;
    final long position = session.getPositionMicroseconds();
    final int frameCount = frames.get();
    assertEquals(5, frameCount, "every frame is shown");
    assertEquals(LAST_FRAME_POSITION, position, "every frame is one period of 10 frames per second after the previous one");

    final boolean paced = elapsedNanoseconds >= PACED_NANOSECONDS;
    assertTrue(paced, "the frames are paced instead of racing by, took " + elapsedNanoseconds);
  }

  @Test
  void shouldShowFramesOfLiveSourcesWithoutTimestampsAsTheyArrive() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      final Frame frame = ScriptedFrameGrabber.video(0L);
      script.add(frame);
      script.add(new ScriptedFrameGrabber.Delay(40L));
    }

    final ScriptedFrameGrabber camera = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    camera.setFrameRate(30.0);
    final PlaybackSession session = this.session(camera, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final long position = session.getPositionMicroseconds();
    final int frameCount = frames.get();
    assertEquals(5, frameCount, "every frame that arrives in real time is shown");

    final boolean followedArrival = position >= 160_000L;
    assertTrue(followedArrival, "the position follows the time the frames arrived at, got " + position);
  }

  // Creates a session that plays one grabber from the beginning and takes its video pipeline from the given callback
  // instead of the callback of the test.
  private PlaybackSession sessionWithVideoCallback(final ScriptedFrameGrabber grabber, final VideoAttachableCallback callback) {
    final PlaybackSession.GrabberFactory videoFactory = factoryOf(grabber);
    return this.session(videoFactory, null, callback, Timing.NEVER_DROPPING, 0L, StartState.PLAYING);
  }

  @Test
  void shouldHandThePictureBackWhenThePipelineIsDetachedBeforeTheFrameIsShown() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final VideoFilter counter = (_, _) -> frames.incrementAndGet() > 0;
    final VideoPipelineStep counting = VideoPipelineStep.of(counter);
    final VideoAttachableCallback detaching = mock(VideoAttachableCallback.class);
    when(detaching.retrieve()).thenReturn(counting).thenReturn(VideoPipelineStep.NO_OP);
    final ScriptedFrameGrabber grabber = videoAt(FRAME_MICROSECONDS);
    final PlaybackSession session = this.sessionWithVideoCallback(grabber, detaching);
    final Field poolField = PlaybackSession.class.getDeclaredField("imagePool");
    poolField.setAccessible(true);
    final ImagePool pool = (ImagePool) poolField.get(session);
    try (final ImageBuffer retained = ImageBuffer.bytes(new byte[FRAME_WIDTH * FRAME_HEIGHT * 3], FRAME_WIDTH, FRAME_HEIGHT)) {
      pool.recycle((MatImageBuffer) retained);
      session.start();
      awaitEnd(session);
      final IllegalStateException released = assertThrows(
        IllegalStateException.class,
        retained::getWidth,
        "the detached frame returns to the pool and is released when rendering ends"
      );
      final String message = released.getMessage();
      assertEquals("Image buffer has been released!", message);
    }

    final long position = session.getPositionMicroseconds();
    final int frameCount = frames.get();
    final boolean noReports = this.reports.isEmpty();
    assertEquals(0, frameCount, "a detached pipeline is not called");
    assertEquals(FRAME_MICROSECONDS, position);
    assertTrue(noReports, this.reports::toString);
  }

  @Test
  void shouldDropFramesThatAreTooLate() throws Exception {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> this.advanceClockAfterFirstFrame(frames, now, 300_000_000L));
    final Timing controlled = new Timing(PlaybackSession.AUDIO_LEAD_NANOSECONDS, PlaybackSession.MAXIMUM_VIDEO_LAG_NANOSECONDS, now::get);
    final List<Object> script = frames(6, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    final PlaybackSession session = this.session(factory, null, this.videoCallback, controlled, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    assertEquals(1, frameCount, "every frame after the filter that took 300 ms is more than 100 ms late");
  }

  private boolean advanceClockAfterFirstFrame(final AtomicInteger frames, final AtomicLong now, final long nanoseconds) {
    final int frameNumber = frames.incrementAndGet();
    if (frameNumber == 1) {
      now.addAndGet(nanoseconds);
    }

    return true;
  }

  @Test
  void shouldShowAFrameThatIsExactlyAsLateAsAllowed() throws Exception {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger frames = new AtomicInteger();
    final long frameNanoseconds = TimeUnit.MICROSECONDS.toNanos(FRAME_MICROSECONDS);
    final long slowFilterNanoseconds = PlaybackSession.MAXIMUM_VIDEO_LAG_NANOSECONDS + frameNanoseconds;
    this.onVideo((_, _) -> this.advanceClockAfterFirstFrame(frames, now, slowFilterNanoseconds));
    final Timing controlled = new Timing(PlaybackSession.AUDIO_LEAD_NANOSECONDS, PlaybackSession.MAXIMUM_VIDEO_LAG_NANOSECONDS, now::get);
    final List<Object> script = frames(2, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    final PlaybackSession session = this.session(factory, null, this.videoCallback, controlled, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final int frameCount = frames.get();
    assertEquals(2, frameCount, "the second frame is exactly as late as allowed, so it is still shown");
  }

  @Test
  void shouldSleepUntilAFrameIsDue() throws Exception {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger reads = new AtomicInteger();
    final LongSupplier clock = () -> this.countClockRead(reads, now);
    final Timing frozen = new Timing(PlaybackSession.AUDIO_LEAD_NANOSECONDS, PlaybackSession.MAXIMUM_VIDEO_LAG_NANOSECONDS, clock);
    final List<Object> script = frames(2, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    final PlaybackSession session = this.session(factory, null, this.videoCallback, frozen, 0L, StartState.PLAYING);
    session.start();
    Thread.sleep(300L);
    session.stop();

    final int readCount = reads.get();
    assertTrue(readCount < 1_000, "the renderer sleeps until the second frame is due instead of polling the clock");
  }

  private long countClockRead(final AtomicInteger reads, final AtomicLong now) {
    reads.incrementAndGet();

    return now.get();
  }

  @Test
  void shouldKeepTheInterruptOfADecoderStoppedWhileWaitingForRoom() throws Exception {
    final List<Object> script = frames(20, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PAUSED);
    session.start();
    awaitCondition("the decoder waits for room in the full queue", () -> isWaiting(grabber.getGrabbingThread()));
    session.stop();

    final Thread decoder = grabber.getGrabbingThread();
    decoder.join(TIMEOUT_MILLISECONDS);
    final boolean interrupted = decoder.isInterrupted();
    assertTrue(interrupted, "the decoder keeps its interrupt while it closes the grabber");
  }

  private static boolean isWaiting(final @Nullable Thread thread) {
    return thread != null && thread.getState() == Thread.State.WAITING;
  }

  @Test
  void shouldJumpInTheTimestampsDoNotStallPlayback() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, 30_000_000L);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    final long start = System.nanoTime();
    session.start();
    awaitEnd(session);

    final long end = System.nanoTime();
    final long elapsed = end - start;
    final int frameCount = frames.get();
    assertEquals(2, frameCount);

    final boolean jumpNotWaited = elapsed < 3_000_000_000L;
    assertTrue(jumpNotWaited, "a jump of 30 seconds is not waited out");
  }

  @Test
  void shouldStartPausedAndResumeOnRequest() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROSECONDS);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PAUSED);
    session.start();
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
  void shouldStopWhileWaitingForTheNextFrame() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    this.onAudio((_, _) -> true);
    final Frame firstPicture = ScriptedFrameGrabber.video(0L);
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame latePicture = ScriptedFrameGrabber.video(1_500_000L);
    final Frame lateSound = ScriptedFrameGrabber.audio(1_500_000L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstPicture, firstSound, latePicture, lateSound);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
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

    final boolean stoppedPromptly = stopTime < PROMPT_STOP_NANOSECONDS;
    assertTrue(stoppedPromptly, "stopping interrupts the waiting threads");
  }

  private static VideoFilter blockingUntilInterrupted(final CountDownLatch rendering) {
    final CountDownLatch never = new CountDownLatch(1);
    return (_, _) -> blockUntilInterrupted(rendering, never);
  }

  private static boolean blockUntilInterrupted(final CountDownLatch rendering, final CountDownLatch never) {
    rendering.countDown();
    try {
      never.await();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }

    return true;
  }

  @Test
  void shouldStopWhileTheDecoderWaitsForRoomInTheQueue() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final VideoFilter blocking = blockingUntilInterrupted(rendering);
    this.onVideo(blocking);
    final List<Object> script = frames(20, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    final boolean rendered = rendering.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertTrue(rendered);

    awaitCondition("the queue is full", () -> grabber.getRemainingSteps() == 14);

    final long stopStart = System.nanoTime();
    session.stop();
    final long stopEnd = System.nanoTime();
    final long stopTime = stopEnd - stopStart;
    awaitCondition("the decoder closed its grabber", grabber::isClosed);
    final boolean active = session.isActive();
    assertFalse(active);

    final boolean stoppedPromptly = stopTime < PROMPT_STOP_NANOSECONDS;
    assertTrue(stoppedPromptly, "the blocked decoder is interrupted, took " + stopTime);
  }

  private static VideoFilter stoppingItsOwnSession(
    final AtomicReference<PlaybackSession> holder,
    final AtomicInteger frames,
    final AtomicLong stopNanoseconds
  ) {
    return (_, _) -> stopOwnSession(holder, frames, stopNanoseconds);
  }

  private static boolean stopOwnSession(
    final AtomicReference<PlaybackSession> holder,
    final AtomicInteger frames,
    final AtomicLong stopNanoseconds
  ) {
    frames.incrementAndGet();
    final long stopStart = System.nanoTime();
    final PlaybackSession own = holder.get();
    own.stop();
    final long stopEnd = System.nanoTime();
    stopNanoseconds.set(stopEnd - stopStart);

    return true;
  }

  @Test
  void shouldStopFromItsOwnFilter() throws Exception {
    final AtomicReference<PlaybackSession> holder = new AtomicReference<>();
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong stopNanoseconds = new AtomicLong(-1L);
    final VideoFilter stopping = stoppingItsOwnSession(holder, frames, stopNanoseconds);
    this.onVideo(stopping);
    final ScriptedFrameGrabber grabber = videoAt(0L, FRAME_MICROSECONDS);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    holder.set(session);
    session.start();
    awaitCondition("the filter stopped the session", () -> stopNanoseconds.get() >= 0 && grabber.isClosed());

    final long stopTime = stopNanoseconds.get();
    final boolean closed = grabber.isClosed();
    final boolean active = session.isActive();
    final int frameCount = frames.get();
    assertFalse(active);
    assertTrue(closed, "the decoder finished");

    final boolean stoppedPromptly = stopTime < PROMPT_STOP_NANOSECONDS;
    assertTrue(stoppedPromptly, "stopping from a filter does not wait for its own thread, took " + stopTime);
    assertEquals(1, frameCount, "frames queued before the stop are discarded");
  }

  // A player stops its session from outside while the session may be stopping itself, which its renderers do when the
  // exception handler throws. Found by PlaybackSessionStopStressTest: the outside stop lost the race for the running
  // flag and returned at once, while the session was still waiting for a decoder stuck in native code, so a player
  // could return from release while the playback still ran.
  @Test
  void shouldWaitForTheThreadsWhenAStopRacesTheSessionStoppingItself() throws Exception {
    final AtomicReference<PlaybackSession> holder = new AtomicReference<>();
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong stopNanoseconds = new AtomicLong(-1L);
    final VideoFilter stopping = stoppingItsOwnSession(holder, frames, stopNanoseconds);
    this.onVideo(stopping);
    final Frame picture = ScriptedFrameGrabber.video(0L);
    final ScriptedFrameGrabber.Delay stuck = new ScriptedFrameGrabber.Delay(STALL_MILLISECONDS);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(picture, stuck);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    holder.set(session);
    session.start();
    awaitCondition("the filter began to stop the session", () -> !session.isActive());

    session.stop();
    final boolean closed = grabber.isClosed();
    final long ownStopTime = stopNanoseconds.get();
    assertTrue(closed, "the stop returned while the decoder was still running");

    final boolean stoppedEarly = ownStopTime >= 0;
    assertTrue(stoppedEarly, "the stop returned while the session was still stopping itself");
  }

  @Test
  void shouldKeepTheInterruptWhenStoppedFromAnInterruptedThread() throws Exception {
    final ScriptedFrameGrabber.Delay stuck = new ScriptedFrameGrabber.Delay(500L);
    final Frame picture = ScriptedFrameGrabber.video(0L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(stuck, picture);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();

    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    session.stop();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted, "the interrupt must be restored");

    awaitCondition("the stuck decoder finished and closed its grabber", grabber::isClosed);
  }

  @Test
  void shouldReportNothingAfterItWasStopped() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch releaseFailure = new CountDownLatch(1);
    final FrameGrabber.Exception lateFailure = new FrameGrabber.Exception("late failure");
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final AtomicReference<Throwable> stopFailure = new AtomicReference<>();
    final ScriptedFrameGrabber grabber = spy(ScriptedFrameGrabber.of(lateFailure));
    doAnswer(invocation -> this.grabAfterRelease(invocation, entered, releaseFailure, thrown))
      .when(grabber)
      .grab();
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    final Thread stopper = new Thread(session::stop);
    stopper.setUncaughtExceptionHandler((_, failure) -> stopFailure.set(failure));
    try {
      session.start();
      awaitCondition("the decoder entered grab or closed", () -> entered.getCount() == 0 || grabber.isClosed());
      final long remainingEntries = entered.getCount();
      assertEquals(0, remainingEntries, "the late failure must be in progress before stopping");

      stopper.start();
      awaitCondition("the session stopped accepting reports", () -> !session.isActive());
      releaseFailure.countDown();
      stopper.join(TIMEOUT_MILLISECONDS);
      final boolean stopperAlive = stopper.isAlive();
      assertFalse(stopperAlive, "stop waited for the decoder");

      final Throwable stopThrown = stopFailure.get();
      assertEquals(null, stopThrown);

      final boolean grabberClosed = grabber.isClosed();
      assertTrue(grabberClosed);

      final Throwable decoderThrown = thrown.get();
      assertSame(lateFailure, decoderThrown, "the decoder actually threw after stop");

      final int remainingSteps = grabber.getRemainingSteps();
      assertEquals(0, remainingSteps);
      assertEquals(List.of(), this.reports);
    } finally {
      releaseFailure.countDown();
      session.stop();
      stopper.join(TIMEOUT_MILLISECONDS);
    }
  }

  private @Nullable Object grabAfterRelease(
    final InvocationOnMock invocation,
    final CountDownLatch entered,
    final CountDownLatch releaseFailure,
    final AtomicReference<Throwable> thrown
  ) throws Throwable {
    entered.countDown();
    final boolean released = Uninterruptibles.awaitUninterruptibly(releaseFailure, TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertTrue(released);

    try {
      return invocation.callRealMethod();
    } catch (final FrameGrabber.Exception exception) {
      thrown.set(exception);
      throw exception;
    }
  }

  @Test
  void shouldDescribeSourcesWithoutSoundWithTheOutputFormat() throws Exception {
    final AtomicReference<OriginalAudioMetadata> audioMetadata = new AtomicReference<>();
    this.onAudio((_, metadata) -> this.recordAudioMetadata(audioMetadata, metadata));
    final Frame sound = ScriptedFrameGrabber.audio(0L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(sound);
    grabber.setSampleRate(0);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final OriginalAudioMetadata metadata = audioMetadata.get();
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    assertEquals(AudioFilter.SAMPLE_RATE, sampleRate);
    assertEquals(AudioFilter.CHANNELS, channels);
  }

  private boolean recordAudioMetadata(final AtomicReference<OriginalAudioMetadata> audioMetadata, final OriginalAudioMetadata metadata) {
    audioMetadata.set(metadata);

    return true;
  }

  @Test
  void shouldFailToStartWhenTheVideoGrabberCannotBeCreated() {
    final PlaybackSession.GrabberFactory missing = this::failToCreateVideoGrabber;
    final PlaybackSession session = this.session(missing, null, 0L, StartState.PLAYING);
    assertThrows(FrameGrabber.Exception.class, session::start);

    final boolean active = session.isActive();
    assertFalse(active);
  }

  private FrameGrabber failToCreateVideoGrabber() throws FrameGrabber.Exception {
    throw new FrameGrabber.Exception("missing");
  }

  @Test
  void shouldHandTheSamplesOnAsLittleEndianBytes() throws Exception {
    final AtomicReference<byte[]> received = new AtomicReference<>();
    this.onAudio((samples, _) -> this.recordBytes(received, samples));
    final Frame chunk = ScriptedFrameGrabber.audio(0L);
    final short[] samples = { 0x0102, (short) 0xFFFE };
    final ShortBuffer sampleBuffer = ShortBuffer.wrap(samples);
    chunk.samples = new Buffer[] { sampleBuffer };
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(chunk);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final byte[] bytes = received.get();
    assertArrayEquals(new byte[] { 0x02, 0x01, (byte) 0xFE, (byte) 0xFF }, bytes, "the pipeline carries little-endian samples");
  }

  private boolean recordBytes(final AtomicReference<byte[]> received, final ByteBuffer samples) {
    final ByteBuffer view = samples.duplicate();
    final int remaining = view.remaining();
    final byte[] bytes = new byte[remaining];
    view.get(bytes);
    received.set(bytes);

    return true;
  }

  private PlaybackSession sessionWithAudioLead(final ScriptedFrameGrabber grabber) {
    final long audioLeadNanoseconds = EARLY_AUDIO_NANOSECONDS;
    final Timing earlyAudio = new Timing(audioLeadNanoseconds, Long.MAX_VALUE, System::nanoTime);
    final PlaybackSession.GrabberFactory factory = factoryOf(grabber);
    return this.session(factory, null, this.videoCallback, earlyAudio, 0L, StartState.PLAYING);
  }

  @Test
  void shouldHandAudioToThePipelineAheadOfItsTimestamp() throws Exception {
    final List<Long> deliveries = Collections.synchronizedList(new ArrayList<>());
    this.onAudio((_, _) -> this.recordDeliveryTime(deliveries));
    final Frame firstSound = ScriptedFrameGrabber.audio(0L);
    final Frame laterSound = ScriptedFrameGrabber.audio(LATER_SOUND_TIMESTAMP);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(firstSound, laterSound);
    final PlaybackSession session = this.sessionWithAudioLead(grabber);
    session.start();
    awaitEnd(session);

    final long first = deliveries.getFirst();
    final long second = deliveries.get(1);
    final long gap = second - first;
    final boolean handedOnEarly = gap < 450_000_000L;
    assertTrue(handedOnEarly, "the chunk is handed on ahead of its timestamp, gap " + gap);
  }

  private boolean recordDeliveryTime(final List<Long> deliveries) {
    final long now = System.nanoTime();
    return deliveries.add(now);
  }

  @Test
  void shouldLeaveTheRemainingTimestampDelayWhenTheAudioLeads() throws Exception {
    final AtomicLong virtualTime = new AtomicLong();
    final List<Long> deliveredAt = Collections.synchronizedList(new ArrayList<>());
    this.onAudio((_, _) -> deliveredAt.add(virtualTime.get()));
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(
      ScriptedFrameGrabber.audio(0L),
      ScriptedFrameGrabber.audio(LATER_SOUND_TIMESTAMP)
    );
    final Timing timing = new Timing(EARLY_AUDIO_NANOSECONDS, Long.MAX_VALUE, () -> virtualTime.getAndAdd(10_000_000L));
    final PlaybackSession session = this.session(factoryOf(grabber), null, this.videoCallback, timing, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);
    final int deliveries = deliveredAt.size();
    assertEquals(2, deliveries);

    final boolean initialWithoutDelay = deliveredAt.getFirst() < 100_000_000L;
    assertTrue(initialWithoutDelay, "the initial audio needs no timestamp delay");

    final boolean secondDelayed = deliveredAt.get(1) >= 100_000_000L;
    assertTrue(secondDelayed, "the 400ms lead leaves 100ms of the 500ms timestamp");
  }

  @Test
  void shouldReplaceTheQueueWithTheEndMarkerEvenWhenADecoderRefillsIt() {
    final RefillingQueue queue = new RefillingQueue();
    queue.add("frame");
    final List<String> discarded = new ArrayList<>();
    PlaybackSession.replaceWithEndMarker(queue, "end", discarded::add);

    final List<String> remaining = new ArrayList<>(queue);
    final List<String> expectedRemaining = List.of("end");
    final List<String> expectedDiscarded = List.of("frame", LATE_FRAME);
    assertEquals(expectedRemaining, remaining);
    assertEquals(expectedDiscarded, discarded, "every removed element is handed on, so it can be released");
  }

  // Creates a decoded frame whose picture is a tiny image, so a test can tell whether the picture was released.
  private static DecodedVideoFrame frameWithPicture() {
    final ImageBuffer picture = ImageBuffer.bytes(new byte[3], 1, 1);
    return new DecodedVideoFrame((MatImageBuffer) picture, 0L);
  }

  @Test
  void shouldLeaveAQueuedFrameToTheRendererWhileTheSessionRuns() throws Exception {
    final BlockingQueue<DecodedVideoFrame> queue = new ArrayBlockingQueue<>(1);
    final DecodedVideoFrame frame = frameWithPicture();
    final MatImageBuffer picture = frame.getImage();
    PlaybackSession.queueFrame(queue, frame, () -> true);

    final DecodedVideoFrame queued = queue.poll();
    final int width = picture.getWidth();
    assertSame(frame, queued);
    assertEquals(1, width, "the picture now belongs to the renderer and is not released");

    picture.release();
  }

  @Test
  void shouldTakeBackAFrameQueuedAfterTheSessionStoppedAndReleaseItsPicture() throws Exception {
    final BlockingQueue<DecodedVideoFrame> queue = new ArrayBlockingQueue<>(1);
    final DecodedVideoFrame frame = frameWithPicture();
    final MatImageBuffer picture = frame.getImage();
    PlaybackSession.queueFrame(queue, frame, () -> false);

    final boolean empty = queue.isEmpty();
    assertTrue(empty, "nobody takes frames out of the queue of a stopped session anymore");
    assertThrows(IllegalStateException.class, picture::getWidth, "the picture was released");
  }

  @Test
  void shouldLeaveAFrameTheRendererTookBeforeTheDecoderNoticedTheStop() throws Exception {
    final BlockingQueue<DecodedVideoFrame> queue = new ArrayBlockingQueue<>(1);
    final DecodedVideoFrame frame = frameWithPicture();
    final MatImageBuffer picture = frame.getImage();
    final AtomicReference<DecodedVideoFrame> taken = new AtomicReference<>();
    final BooleanSupplier renderedThenStopped = () -> this.takeQueuedFrame(queue, taken);
    PlaybackSession.queueFrame(queue, frame, renderedThenStopped);

    final DecodedVideoFrame rendered = taken.get();
    final int width = picture.getWidth();
    assertSame(frame, rendered);
    assertEquals(1, width, "the renderer owns the picture it took, so the decoder leaves it alone");

    picture.release();
  }

  private boolean takeQueuedFrame(final BlockingQueue<DecodedVideoFrame> queue, final AtomicReference<DecodedVideoFrame> taken) {
    final DecodedVideoFrame polled = queue.poll();
    taken.set(polled);

    return false;
  }

  @Test
  void shouldReleaseThePictureOfAFrameItCouldNotQueueBeforeItWasInterrupted() {
    final BlockingQueue<DecodedVideoFrame> queue = new ArrayBlockingQueue<>(1);
    final DecodedVideoFrame waiting = new DecodedVideoFrame(null, 0L);
    queue.add(waiting);
    final DecodedVideoFrame frame = frameWithPicture();
    final MatImageBuffer picture = frame.getImage();
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();

    assertThrows(InterruptedException.class, () -> PlaybackSession.queueFrame(queue, frame, () -> true));

    final boolean interrupted = Thread.interrupted();
    assertFalse(interrupted, "the interrupt was thrown, not kept");
    assertThrows(IllegalStateException.class, picture::getWidth, "the picture was released");
  }

  // Creates a picture whose pixels are on the Java heap, where OpenCV cannot read them, so the decoder copies it into
  // an image of its own size before it scales it.
  private static Frame heapPicture(final long timestampMicroseconds) {
    final Frame frame = ScriptedFrameGrabber.video(timestampMicroseconds);
    final ByteBuffer direct = (ByteBuffer) frame.image[0];
    final ByteBuffer whole = direct.duplicate();
    whole.clear();
    final byte[] pixels = new byte[whole.capacity()];
    whole.get(pixels);
    frame.image[0] = ByteBuffer.wrap(pixels);
    return frame;
  }

  @Test
  void shouldReleaseTheCopyOfAnUnscaledPictureWhenDecodingEnds() throws Exception {
    final List<String> sizes = Collections.synchronizedList(new ArrayList<>());
    this.onVideo((image, _) -> this.recordImageSize(sizes, image));
    final Dimension size = Dimension.of(SCALED_WIDTH, 1);
    this.dimensionCallback.attach(size);
    final Frame picture = heapPicture(0L);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(picture);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final boolean copyKept = session.hasUnscaledCopy();
    assertEquals(List.of(SCALED_SIZE), sizes);
    assertFalse(copyKept, "the decoder releases its copy of the unscaled picture once it is done");
  }

  @Test
  void shouldKeepTheCopyOfAnUnscaledPictureWhileDecoding() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    this.onVideo((_, _) -> frames.incrementAndGet() > 0);
    final Dimension size = Dimension.of(SCALED_WIDTH, 1);
    this.dimensionCallback.attach(size);
    final Frame picture = heapPicture(0L);
    final ScriptedFrameGrabber.Delay stall = new ScriptedFrameGrabber.Delay(STALL_MILLISECONDS);
    final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(picture, stall);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitCondition("the first picture was shown", () -> frames.get() == 1);

    final boolean copyKept = session.hasUnscaledCopy();
    assertTrue(copyKept, "the decoder keeps its copy of the unscaled picture for the next one");

    awaitEnd(session);
  }

  @Test
  void shouldGiveFiltersEmptyMetadataWhenTheDecoderReportsNoPictureSize() throws Exception {
    final AtomicReference<OriginalVideoMetadata> seen = new AtomicReference<>();
    this.onVideo((_, metadata) -> seen.compareAndSet(null, metadata));
    final Frame picture = ScriptedFrameGrabber.video(0L);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(0, 0, List.of(picture));
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();
    awaitEnd(session);

    final OriginalVideoMetadata metadata = seen.get();
    assertSame(OriginalVideoMetadata.EMPTY, metadata);
  }

  // A pipeline may interrupt the thread it runs on, for instance when it gives up a blocking call. Its renderer then
  // ends, and it stops the session: otherwise the decoder would wait for room in a queue nobody empties anymore.
  @Test
  void shouldStopTheSessionWhenAVideoPipelineInterruptsItsThread() throws Exception {
    final AtomicReference<Thread> rendererHolder = new AtomicReference<>();
    this.onVideo((_, _) -> this.recordAndInterruptRenderer(rendererHolder));
    final List<Object> pictures = frames(20, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, pictures);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();

    awaitCondition("the session stopped and closed its decoder", () -> !session.isActive() && grabber.isClosed());
    assertEndedInterrupted(rendererHolder.get());
  }

  private boolean recordAndInterruptRenderer(final AtomicReference<Thread> rendererHolder) {
    final Thread renderer = Thread.currentThread();
    rendererHolder.set(renderer);
    renderer.interrupt();

    return true;
  }

  private static void assertEndedInterrupted(final Thread renderer) throws InterruptedException {
    renderer.join(TIMEOUT_MILLISECONDS);
    final boolean alive = renderer.isAlive();
    final boolean interrupted = renderer.isInterrupted();
    assertFalse(alive);
    assertTrue(interrupted, "the renderer keeps its interrupt");
  }

  @Test
  void shouldStopTheSessionWhenAnAudioPipelineInterruptsItsThread() throws Exception {
    final AtomicReference<Thread> rendererHolder = new AtomicReference<>();
    this.onAudio((_, _) -> this.recordAndInterruptRenderer(rendererHolder));
    final List<Object> sounds = new ArrayList<>();
    for (int index = 0; index < SOUND_COUNT; index++) {
      final Frame sound = ScriptedFrameGrabber.audio(0L);
      sounds.add(sound);
    }

    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, sounds);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    session.start();

    awaitCondition("the session stopped and closed its decoder", () -> !session.isActive() && grabber.isClosed());
    assertEndedInterrupted(rendererHolder.get());
  }

  // The audio renderer is not interrupted when its own pipeline stops the session, so it ends through the end marker
  // the stop puts into its queue. The decoder is stopped before it could queue one: it waits for room in the queue.
  @Test
  void shouldStopFromItsOwnAudioPipeline() throws Exception {
    final AtomicReference<PlaybackSession> holder = new AtomicReference<>();
    final AtomicReference<Thread> rendererHolder = new AtomicReference<>();
    this.onAudio((_, _) -> this.recordRendererAndStop(rendererHolder, holder));
    final List<Object> sounds = new ArrayList<>();
    for (int index = 0; index < SOUND_COUNT; index++) {
      final Frame sound = ScriptedFrameGrabber.audio(0L);
      sounds.add(sound);
    }

    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, sounds);
    final PlaybackSession session = this.session(grabber, 0L, StartState.PLAYING);
    holder.set(session);
    session.start();
    awaitCondition("the pipeline stopped the session", () -> rendererHolder.get() != null && !session.isActive());

    final Thread renderer = rendererHolder.get();
    renderer.join(TIMEOUT_MILLISECONDS);
    final boolean alive = renderer.isAlive();
    assertFalse(alive, "the audio renderer that stopped the session ends");
  }

  private boolean recordRendererAndStop(final AtomicReference<Thread> rendererHolder, final AtomicReference<PlaybackSession> holder) {
    final Thread renderer = Thread.currentThread();
    rendererHolder.set(renderer);
    final PlaybackSession own = holder.get();
    own.stop();

    return true;
  }

  // A source that decodes slower than it plays has its frames dropped one after the other, which freezes the picture
  // without a word, so the session says so.
  @Test
  void shouldSaySoWhenMostFramesComeTooLate() throws Exception {
    final AtomicInteger shown = new AtomicInteger();
    this.onVideo((_, _) -> shown.incrementAndGet() > 0);
    final List<String> reports = Collections.synchronizedList(new ArrayList<>());
    final List<Object> script = frames(320, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession session = new PlaybackSession(
      factoryOf(grabber),
      null,
      this.videoCallback,
      this.audioCallback,
      this.dimensionCallback,
      this::report,
      0L,
      StartState.PLAYING,
      PlaybackSession.AUDIO_LEAD_NANOSECONDS,
      -1L,
      System::nanoTime,
      (dropped, frames) -> reports.add(dropped + " of " + frames)
    );
    session.setSpeed(1_000.0);
    session.start();
    awaitEnd(session);

    final int shownCount = shown.get();
    assertEquals(0, shownCount);
    assertEquals(List.of("302 of 302"), reports, "the frames of the first ten seconds, all dropped");
  }

  // The first frame takes 450 ms, so the ten frames due in the next 350 ms come too late, and the frames after them are
  // shown while the clock moves on one frame at a time. The last frame of the first ten seconds takes 500 ms, so the
  // frame that closes the stretch is dropped: eleven dropped frames do not outnumber the 291 shown ones.
  @Test
  void shouldStaySilentWhileMostFramesAreShown() throws Exception {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger shown = new AtomicInteger();
    this.onVideo((_, _) -> this.advanceClockByOneFrameOrASlowFrame(shown, now));
    final List<String> reports = Collections.synchronizedList(new ArrayList<>());
    final List<Object> script = frames(320, FRAME_MICROSECONDS);
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    final PlaybackSession session = new PlaybackSession(
      factoryOf(grabber),
      null,
      this.videoCallback,
      this.audioCallback,
      this.dimensionCallback,
      this::report,
      0L,
      StartState.PLAYING,
      PlaybackSession.AUDIO_LEAD_NANOSECONDS,
      PlaybackSession.MAXIMUM_VIDEO_LAG_NANOSECONDS,
      now::get,
      (dropped, frames) -> reports.add(dropped + " of " + frames)
    );
    session.start();
    awaitEnd(session);

    final int shownCount = shown.get();
    assertEquals(296, shownCount, "only the frames due during the two slow frames are dropped");
    assertEquals(List.of(), reports, "a stretch that shows most of its frames is not reported");
  }

  private boolean advanceClockByOneFrameOrASlowFrame(final AtomicInteger shown, final AtomicLong now) {
    final int frameNumber = shown.incrementAndGet();
    final long advance = switch (frameNumber) {
      case 1 -> 450_000_000L;
      case 291 -> 500_000_000L;
      default -> FRAME_MICROSECONDS * 1_000L;
    };
    now.addAndGet(advance);

    return true;
  }

  private static final class Timing {

    // The wall clock and the audio lead of the players, with a lag no frame can exceed, so frames are never dropped as
    // late however loaded the machine is.
    private static final Timing NEVER_DROPPING = new Timing(PlaybackSession.AUDIO_LEAD_NANOSECONDS, Long.MAX_VALUE, System::nanoTime);

    private final long audioLeadNanoseconds;
    private final long maximumVideoLagNanoseconds;
    private final LongSupplier nanosecondClock;

    Timing(final long audioLeadNanoseconds, final long maximumVideoLagNanoseconds, final LongSupplier nanosecondClock) {
      this.audioLeadNanoseconds = audioLeadNanoseconds;
      this.maximumVideoLagNanoseconds = maximumVideoLagNanoseconds;
      this.nanosecondClock = nanosecondClock;
    }
  }

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
        this.offer(LATE_FRAME);
      }

      return drained;
    }
  }
}

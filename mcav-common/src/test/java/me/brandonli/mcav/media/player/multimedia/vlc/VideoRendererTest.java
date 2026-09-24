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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.assertNoFrame;
import static me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.rv32;
import static me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.solid;
import static me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.take;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.RecordedFrame;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * Tests {@link VideoRenderer}.
 */
final class VideoRendererTest {

  private static final int RED = 0xFF0000;
  private static final int GREEN = 0x00FF00;
  private static final int BLUE = 0x0000FF;
  private static final int WHITE = 0xFFFFFF;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final MockVlc vlc = new MockVlc();
  private final VLCPlayer owner = this.vlc.newPlayer(1_000L);
  private final VideoRenderer renderer = new VideoRenderer(this.owner);
  private final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
  private final BlockingQueue<RecordedFrame> frames = new LinkedBlockingQueue<>();
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  VideoRendererTest() {
    this.owner.setExceptionHandler((message, error) -> {
        this.messages.add(message);
        this.errors.add(error);
      });
  }

  /**
   * Loads the OpenCV natives before any timed test, because the first image buffer of the JVM loads them on the
   * render thread, which takes seconds on a busy machine.
   */
  @BeforeAll
  static void loadTheNativeLibrariesAheadOfTheTimedTests() {
    final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1);
    final ResizeFilter resize = new ResizeFilter(2, 2);
    resize.applyFilter(warmUp);
    warmUp.release();
  }

  @AfterEach
  void stopRenderer() {
    this.renderer.stop();
  }

  private void display(final int bufferWidth, final int bufferHeight, final int... argbPixels) {
    final ByteBuffer buffer = rv32(argbPixels);
    final ByteBuffer[] planes = { buffer };
    final BufferFormat format = new RV32BufferFormat(bufferWidth, bufferHeight);
    this.renderer.display(this.mediaPlayer, planes, format, bufferWidth, bufferHeight);
  }

  private void displayPixel(final VideoRenderer target, final int argb) {
    final ByteBuffer buffer = rv32(argb);
    final ByteBuffer[] planes = { buffer };
    final BufferFormat format = new RV32BufferFormat(1, 1);
    target.display(this.mediaPlayer, planes, format, 1, 1);
  }

  private void startRecording(final int width, final int height) {
    VlcTestFrames.record(this.owner, this.frames);
    this.renderer.createBufferFormat(width, height, width, height);
    this.renderer.start();
  }

  private void attach(final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = this.owner.getVideoAttachableCallback();
    callback.attach(step);
  }

  /**
   * Attaches a filter that records the first pixel of every frame, then blocks the render thread until the test lets
   * it proceed, so later frames queue up.
   */
  private void attachBlockingRecorder(final List<Integer> rendered, final CountDownLatch rendering, final CountDownLatch proceed) {
    this.attach((image, _) -> {
        final int[] pixels = image.getPixels();
        rendered.add(pixels[0] & WHITE);
        rendering.countDown();
        awaitRelease(proceed);
        return false;
      });
  }

  private static BiConsumer<String, Throwable> reportingHandler(final List<String> reported) {
    return (message, error) -> {
      final String reason = error.getMessage();
      reported.add(message + ": " + reason);
    };
  }

  @Test
  void readsRv32AsPackedArgb() {
    final ByteBuffer buffer = ByteBuffer.wrap(new byte[] { 0x33, 0x22, 0x11, 0x00, 0x66, 0x55, 0x44, (byte) 0xFF });
    final int[] pixels = new int[2];
    VideoRenderer.readVisibleRows(buffer, 2, 2, 1, pixels);
    assertArrayEquals(new int[] { 0x00112233, 0xFF445566 }, pixels);
  }

  @Test
  void readsOnlyTheVisibleColumnsAndRowsOfPaddedBuffers() {
    final int[] padded = new int[4 * 3];
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 4; column++) {
        padded[row * 4 + column] = row * 16 + column;
      }
    }
    final ByteBuffer buffer = rv32(padded);
    final int[] pixels = new int[6];
    VideoRenderer.readVisibleRows(buffer, 4, 3, 2, pixels);
    assertArrayEquals(new int[] { 0, 1, 2, 16, 17, 18 }, pixels);
  }

  @Test
  void readsFromTheStartWithoutMovingTheBuffer() {
    final ByteBuffer buffer = rv32(RED, GREEN);
    buffer.position(4);
    final int[] pixels = new int[2];
    VideoRenderer.readVisibleRows(buffer, 2, 2, 1, pixels);
    final int position = buffer.position();
    assertArrayEquals(new int[] { RED, GREEN }, pixels);
    assertEquals(4, position);
  }

  @Test
  void rendersTheVisiblePartOfEachFrame() throws Exception {
    this.startRecording(3, 2);
    this.display(4, 3, RED, GREEN, BLUE, 0, WHITE, RED, GREEN, 0, 0, 0, 0, 0);
    final RecordedFrame frame = take(this.frames);
    final int width = frame.getWidth();
    final int height = frame.getHeight();
    final int[] rgb = frame.getRgb();
    final OriginalVideoMetadata metadata = frame.getMetadata();
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final boolean noErrors = this.errors.isEmpty();
    assertEquals(3, width);
    assertEquals(2, height);
    assertArrayEquals(new int[] { RED, GREEN, BLUE, WHITE, RED, GREEN }, rgb);
    assertEquals(3, metadataWidth);
    assertEquals(2, metadataHeight);
    assertTrue(noErrors, "errors: " + this.errors);
  }

  @Test
  void reusesTheImageBufferForLaterFrames() throws Exception {
    this.startRecording(2, 1);
    this.display(2, 1, RED, GREEN);
    final RecordedFrame first = take(this.frames);
    this.display(2, 1, BLUE, WHITE);
    final RecordedFrame second = take(this.frames);
    final ImageBuffer firstImage = first.getImage();
    final ImageBuffer secondImage = second.getImage();
    final int[] rgb = second.getRgb();
    assertSame(firstImage, secondImage);
    assertArrayEquals(new int[] { BLUE, WHITE }, rgb);
  }

  @Test
  void dropsFramesBeforeItIsStarted() throws Exception {
    VlcTestFrames.record(this.owner, this.frames);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.display(1, 1, RED);
    this.renderer.start();
    assertNoFrame(this.frames);
  }

  @Test
  void dropsFramesAfterItIsStopped() throws Exception {
    this.startRecording(1, 1);
    this.renderer.stop();
    this.display(1, 1, RED);
    assertNoFrame(this.frames);
  }

  @Test
  void dropsFramesWithoutBuffers() throws Exception {
    this.startRecording(1, 1);
    final BufferFormat format = new RV32BufferFormat(1, 1);
    this.renderer.display(this.mediaPlayer, new ByteBuffer[0], format, 1, 1);
    assertNoFrame(this.frames);
  }

  @Test
  void dropsFramesWithoutAPipeline() throws Exception {
    // the slot has no pipeline when the frame arrives; a frame queued anyway would reach the recorder afterwards
    final VideoFilter recorder = VlcTestFrames.recorder(this.frames);
    final VideoPipelineStep recording = VideoPipelineStep.of(recorder);
    final VideoAttachableCallback callback = mock(VideoAttachableCallback.class);
    when(callback.retrieve()).thenReturn(VideoPipelineStep.NO_OP, recording);
    final DimensionAttachableCallback dimension = DimensionAttachableCallback.create();
    final VideoPlayerMultiplexer detachedOwner = mock(VideoPlayerMultiplexer.class);
    when(detachedOwner.getVideoAttachableCallback()).thenReturn(callback);
    when(detachedOwner.getDimensionAttachableCallback()).thenReturn(dimension);
    final VideoRenderer detached = new VideoRenderer(detachedOwner);
    detached.createBufferFormat(1, 1, 1, 1);
    detached.start();
    try {
      this.displayPixel(detached, RED);
      assertNoFrame(this.frames);
      verify(callback, times(1)).retrieve();
    } finally {
      detached.stop();
    }
  }

  @Test
  void dropsBuffersThatDoNotHoldAWholePicture() throws Exception {
    this.startRecording(2, 2);
    this.display(2, 2, RED, GREEN, BLUE);
    assertNoFrame(this.frames);
  }

  @Test
  void dropsFramesUntilTheSizeIsKnown() throws Exception {
    VlcTestFrames.record(this.owner, this.frames);
    this.renderer.start();
    this.display(1, 1, RED);
    assertNoFrame(this.frames);
    this.renderer.stop();
    final boolean reportedNothing = this.errors.isEmpty();
    assertTrue(reportedNothing, "an unknown size is dropped before allocating an invalid image");
  }

  @Test
  void dropsFramesWhilePaused() throws Exception {
    this.startRecording(1, 1);
    this.renderer.setPaused(true);
    this.display(1, 1, RED);
    assertNoFrame(this.frames);
    this.renderer.setPaused(false);
    this.display(1, 1, GREEN);
    final RecordedFrame frame = take(this.frames);
    final int[] rgb = frame.getRgb();
    assertArrayEquals(new int[] { GREEN }, rgb);
  }

  @Test
  void pausingDropsTheQueuedFrame() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Integer> rendered = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(rendered, rendering, proceed);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    final boolean blocking = rendering.await(5, TimeUnit.SECONDS);
    assertTrue(blocking);

    this.display(1, 1, GREEN);
    this.renderer.setPaused(true);
    this.renderer.setPaused(false);
    proceed.countDown();
    // a frame displayed after the pause is rendered, so once it arrived the dropped frame would have arrived before it
    this.display(1, 1, BLUE);
    awaitSize(rendered, 2);
    final List<Integer> expected = List.of(RED, BLUE);
    assertEquals(expected, rendered, "the frame queued before the pause is dropped");
  }

  @Test
  void keepsOnlyTheNewestUnrenderedFrame() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Integer> rendered = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(rendered, rendering, proceed);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    final boolean blocking = rendering.await(5, TimeUnit.SECONDS);
    assertTrue(blocking);

    this.display(1, 1, GREEN);
    this.display(1, 1, BLUE);
    this.display(1, 1, WHITE);
    proceed.countDown();
    awaitSize(rendered, 2);
    this.display(1, 1, GREEN);
    awaitSize(rendered, 3);
    final List<Integer> expected = List.of(RED, WHITE, GREEN);
    assertEquals(expected, rendered, "only the newest of the frames that queued up is rendered");
  }

  @Test
  void resumingAnAlreadyRunningRendererKeepsItsQueuedFrame() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Integer> rendered = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(rendered, rendering, proceed);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    final boolean blocked = rendering.await(5, TimeUnit.SECONDS);
    assertTrue(blocked);
    try {
      this.display(1, 1, GREEN);
      this.renderer.setPaused(false);
    } finally {
      proceed.countDown();
    }
    awaitSize(rendered, 2);
    final List<Integer> expected = List.of(RED, GREEN);
    assertEquals(expected, rendered);
  }

  @Test
  void replacementNeverRecyclesAnArrayStillQueuedForRendering() throws Exception {
    final CountDownLatch rendering = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Integer> rendered = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(rendered, rendering, proceed);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    final boolean blocked = rendering.await(5, TimeUnit.SECONDS);
    assertTrue(blocked);
    try {
      this.display(1, 1, GREEN);
      this.display(1, 1, BLUE);
      this.display(1, 1, WHITE);
      final int[] spare = this.renderer.acquirePixels(1);
      spare[0] = GREEN;
    } finally {
      proceed.countDown();
    }
    awaitSize(rendered, 2);
    final List<Integer> expected = List.of(RED, WHITE);
    assertEquals(expected, rendered, "a spare array must not alias the newest queued picture");
  }

  @Test
  void scalesFramesToTheAttachedSize() throws Exception {
    this.startRecording(4, 4);
    final DimensionAttachableCallback dimension = this.owner.getDimensionAttachableCallback();
    final Dimension half = Dimension.of(2, 2);
    final Dimension quarter = Dimension.of(1, 1);
    final int[] pixels = solid(16, RED);
    dimension.attach(half);
    this.display(4, 4, pixels);
    final RecordedFrame halfFrame = take(this.frames);
    this.display(4, 4, pixels);
    final RecordedFrame againFrame = take(this.frames);
    dimension.attach(quarter);
    this.display(4, 4, pixels);
    final RecordedFrame quarterFrame = take(this.frames);
    assertScaledToHalfAndQuarter(halfFrame, againFrame, quarterFrame);
  }

  /**
   * Checks that a red 4 by 4 picture was scaled to 2 by 2 twice and then to 1 by 1, while the metadata kept describing
   * the original frame.
   */
  private static void assertScaledToHalfAndQuarter(
    final RecordedFrame halfFrame,
    final RecordedFrame againFrame,
    final RecordedFrame quarterFrame
  ) {
    final OriginalVideoMetadata metadata = halfFrame.getMetadata();
    final int metadataWidth = metadata.getVideoWidth();
    final int halfWidth = halfFrame.getWidth();
    final int halfHeight = halfFrame.getHeight();
    final int againWidth = againFrame.getWidth();
    final int quarterWidth = quarterFrame.getWidth();
    final int quarterHeight = quarterFrame.getHeight();
    final int[] quarterRgb = quarterFrame.getRgb();
    assertEquals(2, halfWidth);
    assertEquals(2, halfHeight);
    assertEquals(2, againWidth);
    assertEquals(1, quarterWidth);
    assertEquals(1, quarterHeight);
    assertArrayEquals(new int[] { RED }, quarterRgb);
    assertEquals(4, metadataWidth, "the metadata describes the original frame");
  }

  /**
   * Creates a filter that throws the runtime failure on its first call, the error on its second call, and counts
   * down the latch on its third call.
   */
  private static VideoFilter failingTwiceThenCounting(
    final AtomicInteger calls,
    final IllegalStateException runtimeFailure,
    final AssertionError errorFailure,
    final CountDownLatch third
  ) {
    return (_, _) -> {
      final int call = calls.incrementAndGet();
      if (call == 1) {
        throw runtimeFailure;
      }
      if (call == 2) {
        throw errorFailure;
      }
      third.countDown();
      return false;
    };
  }

  @Test
  void fatalFilterFailuresEscapeWithoutBeingReported() throws Exception {
    final OutOfMemoryError failure = new OutOfMemoryError("filter exhausted memory");
    final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    final CountDownLatch escaped = new CountDownLatch(1);
    this.attach((_, _) -> {
        final Thread worker = Thread.currentThread();
        worker.setUncaughtExceptionHandler((_, thrown) -> {
          uncaught.set(thrown);
          escaped.countDown();
        });
        throw failure;
      });
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    final boolean ended = escaped.await(5L, TimeUnit.SECONDS);
    final Throwable observed = uncaught.get();
    final boolean reportedNothing = this.errors.isEmpty();
    assertTrue(ended);
    assertSame(failure, observed);
    assertTrue(reportedNothing, "fatal VM failures must not be converted to ordinary filter reports");
  }

  @Test
  void reportsPipelineFailuresAndKeepsRendering() throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    final IllegalStateException runtimeFailure = new IllegalStateException("filter bug");
    final AssertionError errorFailure = new AssertionError("filter assertion");
    final CountDownLatch third = new CountDownLatch(1);
    final VideoFilter failing = failingTwiceThenCounting(calls, runtimeFailure, errorFailure, third);
    this.attach(failing);
    this.renderer.createBufferFormat(1, 1, 1, 1);
    this.renderer.start();
    this.display(1, 1, RED);
    awaitCount(calls, 1);
    this.display(1, 1, GREEN);
    awaitCount(calls, 2);
    this.display(1, 1, BLUE);

    final boolean keptRendering = third.await(5, TimeUnit.SECONDS);
    final List<String> expectedMessages = List.of("Video filter failed", "Video filter failed");
    final List<Throwable> expectedErrors = List.of(runtimeFailure, errorFailure);
    assertTrue(keptRendering, "rendering goes on after failures");
    assertEquals(expectedMessages, this.messages);
    assertEquals(expectedErrors, this.errors);
  }

  @Test
  void releasesTheImageBufferWhenStopped() throws Exception {
    this.startRecording(1, 1);
    this.display(1, 1, RED);
    final RecordedFrame frame = take(this.frames);
    this.renderer.stop();
    final ImageBuffer image = frame.getImage();
    assertThrows(IllegalStateException.class, image::getData);
  }

  @Test
  void lockAndUnlockTouchNothing() {
    this.renderer.lock(this.mediaPlayer);
    this.renderer.unlock(this.mediaPlayer);
    verifyNoInteractions(this.mediaPlayer);
  }

  @Test
  void asksVlcToRenderAtTheAttachedSize() throws Exception {
    VlcTestFrames.record(this.owner, this.frames);
    final DimensionAttachableCallback dimension = this.owner.getDimensionAttachableCallback();
    final Dimension target = Dimension.of(2, 1);
    dimension.attach(target);
    final BufferFormat format = this.renderer.createBufferFormat(1920, 1088, 1920, 1080);
    final int formatWidth = format.getWidth();
    final int formatHeight = format.getHeight();
    this.renderer.start();
    final ByteBuffer buffer = rv32(RED, GREEN);
    final ByteBuffer[] planes = { buffer };
    this.renderer.display(this.mediaPlayer, planes, format, 2, 1);

    final RecordedFrame frame = take(this.frames);
    final int[] rgb = frame.getRgb();
    final OriginalVideoMetadata metadata = frame.getMetadata();
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    assertEquals(2, formatWidth, "VLC scales while it converts the picture, which is far cheaper than resizing later");
    assertEquals(1, formatHeight);
    assertArrayEquals(new int[] { RED, GREEN }, rgb, "the whole scaled buffer is the picture");
    assertEquals(1920, metadataWidth, "the metadata describes the original video");
    assertEquals(1080, metadataHeight);
  }

  @Test
  void reusesThePixelArraysHandedBack() {
    final int[] first = this.renderer.acquirePixels(4);
    final boolean firstKept = this.renderer.recyclePixels(first);
    final int[] reused = this.renderer.acquirePixels(4);
    final boolean reusedKept = this.renderer.recyclePixels(reused);
    final int[] larger = this.renderer.acquirePixels(8);
    final int[] fresh = this.renderer.acquirePixels(8);
    assertTrue(firstKept);
    assertTrue(reusedKept);
    assertSame(first, reused, "VLC's thread copies every frame into an array the render thread handed back");
    assertEquals(8, larger.length, "an array of the wrong size is not reused");
    assertEquals(8, fresh.length);
  }

  @Test
  void keepsOnlyAsManySpareArraysAsCanBeInFlight() {
    final int[] first = new int[1];
    final int[] second = new int[1];
    final int[] third = new int[1];
    final boolean firstKept = this.renderer.recyclePixels(first);
    final boolean secondKept = this.renderer.recyclePixels(second);
    final boolean thirdKept = this.renderer.recyclePixels(third);
    final int[] reusedFirst = this.renderer.acquirePixels(1);
    final int[] reusedSecond = this.renderer.acquirePixels(1);
    assertTrue(firstKept);
    assertTrue(secondKept);
    assertFalse(thirdKept, "an array handed back while two are waiting is left to the garbage collector");
    assertSame(first, reusedFirst);
    assertSame(second, reusedSecond);
  }

  /**
   * Creates an owner whose render thread fails while it scales the first frame, which happens outside of the pipeline.
   */
  private static VideoPlayerMultiplexer ownerFailingOnce(
    final VideoAttachableCallback callback,
    final RuntimeException failure,
    final List<String> reported
  ) {
    final DimensionAttachableCallback dimension = DimensionAttachableCallback.create();
    final VideoPlayerMultiplexer failingOwner = mock(VideoPlayerMultiplexer.class);
    final BiConsumer<String, Throwable> handler = reportingHandler(reported);
    when(failingOwner.getVideoAttachableCallback()).thenReturn(callback);
    when(failingOwner.getExceptionHandler()).thenReturn(handler);
    when(failingOwner.getDimensionAttachableCallback()).thenReturn(dimension).thenThrow(failure).thenReturn(dimension);
    return failingOwner;
  }

  @Test
  void reportsRenderingFailuresAndKeepsRendering() throws Exception {
    final List<String> reported = Collections.synchronizedList(new ArrayList<>());
    final BlockingQueue<RecordedFrame> recorded = new LinkedBlockingQueue<>();
    final VideoFilter recorder = VlcTestFrames.recorder(recorded);
    final VideoPipelineStep recording = VideoPipelineStep.of(recorder);
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    callback.attach(recording);
    final IllegalStateException failure = new IllegalStateException("native failure");
    final VideoPlayerMultiplexer failingOwner = ownerFailingOnce(callback, failure, reported);
    final VideoRenderer failing = new VideoRenderer(failingOwner);
    failing.createBufferFormat(1, 1, 1, 1);
    failing.start();
    try {
      this.displayPixel(failing, RED);
      Polling.awaitCondition("the rendering failure was reported", TIMEOUT, () -> !reported.isEmpty());
      this.displayPixel(failing, GREEN);
      final RecordedFrame frame = take(recorded);
      final int[] rgb = frame.getRgb();
      final List<String> expectedReports = List.of("Video rendering failed: native failure");
      assertEquals(expectedReports, reported);
      assertArrayEquals(new int[] { GREEN }, rgb, "the render thread survived the failure");
    } finally {
      failing.stop();
    }
  }

  private static void awaitSize(final List<Integer> list, final int expected) throws InterruptedException {
    final boolean reached = Polling.pollUntil(TIMEOUT, () -> list.size() >= expected);
    final int size = list.size();
    assertTrue(reached, "expected " + expected + " frames but got " + size);
  }

  private static void awaitCount(final AtomicInteger counter, final int expected) throws InterruptedException {
    final boolean reached = Polling.pollUntil(TIMEOUT, () -> counter.get() >= expected);
    final int count = counter.get();
    assertTrue(reached, "expected " + expected + " calls but got " + count);
  }

  /**
   * Blocks a filter until the test lets it proceed. A filter that is never released fails, which the render thread
   * reports to the exception handler.
   */
  private static void awaitRelease(final CountDownLatch proceed) {
    try {
      final boolean released = proceed.await(5, TimeUnit.SECONDS);
      assertTrue(released, "the test released the blocked filter");
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }
}

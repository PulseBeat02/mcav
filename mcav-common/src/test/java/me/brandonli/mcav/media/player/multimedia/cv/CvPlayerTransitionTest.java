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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class CvPlayerTransitionTest {

  private static final String TRANSITION_FILE_NAME = "transition.mp4";

  private static final long CALLBACK_TIMEOUT_SECONDS = 2L;
  private static final long LATCH_TIMEOUT_SECONDS = 10L;

  private static final long SEEK_TIME = 100L;

  @BeforeAll
  static void loadImageBuffersBeforeTheTimedCallbacks() {
    try (final ImageBuffer image = ImageBuffer.bytes(new byte[3], 1, 1)) {
      final int width = image.getWidth();
      assertEquals(1, width);
    }
  }

  private static Source source() {
    final Path path = Path.of(TRANSITION_FILE_NAME);
    return FileSource.path(path);
  }

  @ParameterizedTest
  @EnumSource(InputKind.class)
  void shouldConfigureTheFormatsAndFfmpegOptionsPromisedToThePipelines(final InputKind input) {
    final boolean directInput = input == InputKind.DIRECT;
    final TestPlayer player = new TestPlayer();
    final FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
    final Source source = directInput ? FfmpegDirectSource.mrl("color=c=blue", "lavfi") : source();
    try {
      player.configureGrabber(grabber, source);
      verify(grabber).setPixelFormat(avutil.AV_PIX_FMT_BGR24);
      verify(grabber).setSampleMode(FrameGrabber.SampleMode.SHORT);
      verify(grabber).setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
      verify(grabber).setSampleRate(48_000);
      verify(grabber).setAudioChannels(2);
      verify(grabber).setImageScalingFlags(swscale.SWS_AREA);
      verify(grabber).setVideoOption("threads", "auto");
      verify(grabber).setVideoOption("flags", "low_delay");
      verify(grabber).setOption("rw_timeout", "15000000");
      verify(grabber).setOption("reconnect", "1");
      verify(grabber).setOption("reconnect_streamed", "1");
      verify(grabber).setOption("reconnect_delay_max", "5");
      verify(grabber).setOption("fflags", "discardcorrupt+genpts");
      verify(grabber).setOption("hwaccel", "auto");

      if (directInput) {
        verify(grabber).setFormat("lavfi");
        verify(grabber).setOption("probesize", "32");
        verify(grabber).setOption("analyzeduration", "0");
      } else {
        verify(grabber, never()).setFormat("lavfi");
        verify(grabber, never()).setOption("probesize", "32");
        verify(grabber, never()).setOption("analyzeduration", "0");
      }
    } finally {
      player.release();
    }
  }

  @Test
  void shouldReportWhetherTheActiveSessionWasChangedFromPlaybackControls() {
    final TestPlayer player = new TestPlayer();
    final AtomicBoolean paused = new AtomicBoolean();
    try (
      final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class, (session, _) ->
        this.stubPlaybackControls(session, paused)
      )
    ) {
      final boolean pausedBeforeStart = player.pause();
      assertFalse(pausedBeforeStart);

      final boolean resumedBeforeStart = player.resume();
      assertFalse(resumedBeforeStart);

      final boolean seekedBeforeStart = player.seek(0);
      assertFalse(seekedBeforeStart);

      final boolean speedChangedBeforeStart = player.setSpeed(0.5);
      assertFalse(speedChangedBeforeStart);

      final boolean started = player.start(source());
      assertTrue(started);

      final PlaybackSession session = sessions.constructed().getFirst();
      final boolean resumedWhilePlaying = player.resume();
      assertFalse(resumedWhilePlaying);

      final boolean pausedWhilePlaying = player.pause();
      assertTrue(pausedWhilePlaying);

      final boolean sessionPaused = paused.get();
      assertTrue(sessionPaused);

      final boolean pausedAgain = player.pause();
      assertFalse(pausedAgain);

      final boolean resumedWhilePaused = player.resume();
      assertTrue(resumedWhilePaused);

      final boolean sessionStillPaused = paused.get();
      assertFalse(sessionStillPaused);

      final boolean resumedAgain = player.resume();
      assertFalse(resumedAgain);

      final boolean slowedDown = player.setSpeed(0.5);
      assertTrue(slowedDown);

      final double slowSpeed = player.getSpeed();
      assertEquals(0.5, slowSpeed);
      verify(session).setSpeed(0.5);

      final boolean spedUp = player.setSpeed(2.0);
      assertTrue(spedUp);

      final double fastSpeed = player.getSpeed();
      assertEquals(2.0, fastSpeed);
      verify(session).setSpeed(2.0);

      when(session.isSeekable()).thenReturn(false);
      final boolean speedChangedWithoutSeeking = player.setSpeed(1.5);
      assertFalse(speedChangedWithoutSeeking);

      final double keptSpeed = player.getSpeed();
      assertEquals(2.0, keptSpeed);
      verify(session, never()).setSpeed(1.5);

      player.release();
    } finally {
      player.release();
    }
  }

  private void stubPlaybackControls(final PlaybackSession session, final AtomicBoolean paused) {
    when(session.isActive()).thenReturn(true);
    when(session.isSeekable()).thenReturn(true);
    when(session.isPaused()).thenAnswer(_ -> paused.get());
    doAnswer(_ -> this.markPaused(paused))
      .when(session)
      .pause();
    doAnswer(_ -> this.markResumed(paused))
      .when(session)
      .resume();
  }

  private @Nullable Object markPaused(final AtomicBoolean paused) {
    paused.set(true);

    return null;
  }

  private @Nullable Object markResumed(final AtomicBoolean paused) {
    paused.set(false);

    return null;
  }

  @Test
  void shouldLetThePreviousRendererCallBackWithoutWaitingForThePlayerLockOnReplacement() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      final boolean started = player.start(source);
      assertTrue(started);

      final List<PlaybackSession> created = sessions.constructed();
      final PlaybackSession previous = created.getFirst();
      doAnswer(_ -> this.assertPauseAndCompetingStartAreRefused(player, source))
        .when(previous)
        .stop();
      final boolean replaced = player.start(source);
      assertTrue(replaced);

      final int sessionCount = created.size();
      assertEquals(2, sessionCount);

      final PlaybackSession replacement = created.getLast();
      verify(replacement).startThreads();

      player.release();
    }
  }

  private @Nullable Object assertPauseAndCompetingStartAreRefused(final TestPlayer player, final Source source)
    throws InterruptedException, ExecutionException, TimeoutException {
    final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::pause);
    final boolean paused = callback.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(paused, "the previous playback was detached before its renderer was joined");

    final boolean competing = player.start(source);
    assertFalse(competing, "a replacement retains ownership while previous workers shut down");

    return null;
  }

  @Test
  void shouldJoinOutsideThePlayerLockOnSeekToo() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      final boolean started = player.start(source);
      assertTrue(started);

      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      doAnswer(_ -> this.assertResumeIsRefused(player))
        .when(previous)
        .stop();
      final boolean seeked = player.seek(SEEK_TIME);
      assertTrue(seeked);

      player.release();
    }
  }

  private @Nullable Object assertResumeIsRefused(final TestPlayer player)
    throws InterruptedException, ExecutionException, TimeoutException {
    final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::resume);
    final boolean resumed = callback.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(resumed);

    return null;
  }

  @Test
  void shouldNotLetAStaleSeekReplaceANewerPlayback() {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      final boolean started = player.start(source);
      assertTrue(started);

      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      when(previous.isPaused()).thenAnswer(_ -> this.restartAndReportNotPaused(player, source));
      final boolean seeked = player.seek(SEEK_TIME);
      assertFalse(seeked, "a seek whose observed session was replaced must not replace the new one");

      final int sessionCount = sessions.constructed().size();
      assertEquals(2, sessionCount);

      player.release();
    }
  }

  private boolean restartAndReportNotPaused(final TestPlayer player, final Source source) {
    final boolean restarted = player.start(source);
    assertTrue(restarted);

    return false;
  }

  @Test
  void shouldCancelAndCloseTheReplacementOnReleaseDuringThePreviousShutdown() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      final boolean started = player.start(source);
      assertTrue(started);

      final PlaybackSession previous = sessions.constructed().getFirst();
      doAnswer(_ -> this.assertReleaseFromAnotherThreadSucceeds(player))
        .when(previous)
        .stop();
      final boolean replaced = player.start(source);
      assertFalse(replaced);

      final PlaybackSession replacement = sessions.constructed().getLast();
      verify(replacement, never()).startThreads();
      verify(replacement).stop();

      final boolean startedAfterRelease = player.start(source);
      assertFalse(startedAfterRelease);

      final boolean playing = player.isPlaying();
      assertFalse(playing);
    }
  }

  private @Nullable Object assertReleaseFromAnotherThreadSucceeds(final TestPlayer player)
    throws InterruptedException, ExecutionException, TimeoutException {
    final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::release);
    final boolean released = callback.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(released);

    return null;
  }

  @Test
  void shouldNotPublishPlaybackAfterAReleaseReenteredFromOpening() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (
      final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class, (session, _) ->
        this.stubReentrantOpen(session, player, source)
      )
    ) {
      final boolean started = player.start(source);
      assertFalse(started);

      final PlaybackSession cancelled = sessions.constructed().getFirst();
      verify(cancelled, never()).startThreads();
      verify(cancelled).stop();
    }
  }

  private void stubReentrantOpen(final PlaybackSession session, final TestPlayer player, final Source source)
    throws FrameGrabber.Exception {
    doAnswer(_ -> this.assertReenteredStartIsRefusedAndReleaseSucceeds(player, source))
      .when(session)
      .open();
  }

  private @Nullable Object assertReenteredStartIsRefusedAndReleaseSucceeds(final TestPlayer player, final Source source) {
    final boolean reentered = player.start(source);
    assertFalse(reentered);

    final boolean released = player.release();
    assertTrue(released);

    return null;
  }

  @ParameterizedTest
  @EnumSource(MediaKind.class)
  void shouldKeepPipelineDeliverySerialAcrossSessionsOnAReentrantReplacement(final MediaKind media) throws Exception {
    final boolean audio = media == MediaKind.AUDIO;
    final int firstValue = audio ? 17 : 0xFF0000;
    final int secondValue = audio ? 34 : 0x0000FF;
    try (final Frame first = mediaFrame(media, firstValue); final Frame second = mediaFrame(media, secondValue)) {
      final ScriptedPlayer player = new ScriptedPlayer(ScriptedFrameGrabber.of(first), ScriptedFrameGrabber.of(second));
      player.neverDropLateFrames();
      final Source source = source();
      final CountDownLatch startReturned = new CountDownLatch(1);
      final CountDownLatch restarted = new CountDownLatch(1);
      final CountDownLatch releaseFirst = new CountDownLatch(1);
      final CountDownLatch secondEntered = new CountDownLatch(1);
      final CountDownLatch delivered = new CountDownLatch(2);
      final AtomicInteger active = new AtomicInteger();
      final AtomicInteger maximum = new AtomicInteger();
      final List<Integer> colors = new CopyOnWriteArrayList<>();
      final List<Throwable> failures = new CopyOnWriteArrayList<>();
      player.setExceptionHandler((_, failure) -> failures.add(failure));
      final IntConsumer record = value -> this.recordDelivery(value, colors, active, delivered);
      final Runnable countEntry = () -> maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
      final Runnable replaceSession = () -> this.replaceDuringFirstDelivery(player, source, startReturned, restarted, releaseFirst);
      final IntConsumer enter = value -> this.enterPipeline(value, firstValue, countEntry, replaceSession, secondEntered);
      if (audio) {
        final AudioPipelineStep recorder = AudioPipelineStep.of((samples, _) -> this.acceptFirstSample(record, samples));
        player.getAudioAttachableCallback().attach(AudioPipelineStep.of(recorder, (samples, _) -> this.acceptFirstSample(enter, samples)));
      } else {
        final VideoPipelineStep recorder = VideoPipelineStep.of((image, _) -> this.acceptFirstPixel(record, image));
        player.getVideoAttachableCallback().attach(VideoPipelineStep.of(recorder, (image, _) -> this.acceptFirstPixel(enter, image)));
      }
      try {
        final boolean started = player.start(source);
        assertTrue(started);

        startReturned.countDown();
        final boolean wasRestarted = restarted.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(wasRestarted);

        final boolean enteredEarly = secondEntered.await(200, TimeUnit.MILLISECONDS);
        assertFalse(enteredEarly, "the replacement entered a pipeline still processing the old session");

        releaseFirst.countDown();
        final boolean bothDelivered = delivered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(bothDelivered);

        final int concurrentDeliveries = maximum.get();
        assertEquals(1, concurrentDeliveries);
        assertEquals(List.of(firstValue, secondValue), colors, "the replacement must be delivered after the old session");
        assertEquals(List.of(), failures);
      } finally {
        releaseFirst.countDown();
        player.release();
      }
    }
  }

  private void recordDelivery(final int value, final List<Integer> colors, final AtomicInteger active, final CountDownLatch delivered) {
    colors.add(value);
    active.decrementAndGet();
    delivered.countDown();
  }

  private void replaceDuringFirstDelivery(
    final ScriptedPlayer player,
    final Source source,
    final CountDownLatch startReturned,
    final CountDownLatch restarted,
    final CountDownLatch releaseFirst
  ) {
    // start() refuses another start until it has returned, and the session can deliver its first frame before that
    await(startReturned);
    final boolean replaced = player.start(source);
    assertTrue(replaced);

    restarted.countDown();
    await(releaseFirst);
  }

  private void enterPipeline(
    final int value,
    final int firstValue,
    final Runnable countEntry,
    final Runnable replaceSession,
    final CountDownLatch secondEntered
  ) {
    countEntry.run();

    if (value == firstValue) {
      replaceSession.run();
      return;
    }

    secondEntered.countDown();
  }

  private boolean acceptFirstSample(final IntConsumer consumer, final ByteBuffer samples) {
    final int sample = samples.get(0) & 0xFF;
    consumer.accept(sample);

    return false;
  }

  private boolean acceptFirstPixel(final IntConsumer consumer, final ImageBuffer image) {
    final int pixel = image.getPixels()[0] & 0xFFFFFF;
    consumer.accept(pixel);

    return false;
  }

  @ParameterizedTest
  @EnumSource(MediaKind.class)
  void shouldDiscardAFrameWaitingForPipelineDeliveryInAStoppedSession(final MediaKind media) throws Exception {
    final boolean audio = media == MediaKind.AUDIO;
    final VideoAttachableCallback video = mock(VideoAttachableCallback.class);
    final AudioAttachableCallback sound = mock(AudioAttachableCallback.class);
    final CountDownLatch retrieved = new CountDownLatch(audio ? 1 : 2);
    final AtomicInteger delivered = new AtomicInteger();
    final VideoPipelineStep picture = VideoPipelineStep.of((_, _) -> this.countDelivery(delivered));
    final AudioPipelineStep samples = AudioPipelineStep.of((_, _) -> this.countDelivery(delivered));
    when(video.retrieve()).thenAnswer(_ -> this.countRetrieval(retrieved, picture));
    when(sound.retrieve()).thenAnswer(_ -> this.countRetrieval(retrieved, samples));
    try (final Frame frame = mediaFrame(media, 17)) {
      final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(frame);
      final List<Throwable> failures = new CopyOnWriteArrayList<>();
      final PlaybackSession session = new PlaybackSession(
        () -> grabber,
        null,
        video,
        sound,
        DimensionAttachableCallback.create(),
        (_, failure) -> failures.add(failure),
        0L,
        StartState.PLAYING,
        0L,
        Long.MAX_VALUE,
        System::nanoTime
      );
      final Thread stopper = new Thread(session::stop, "stopped-pipeline-delivery");
      final Object delivery = audio ? sound : video;
      try {
        this.stopWhileHoldingDelivery(delivery, session, retrieved, stopper);

        stopper.join(10_000);
        final boolean stopperAlive = stopper.isAlive();
        assertFalse(stopperAlive, "stopping must join the released renderer");

        final int deliveredFrames = delivered.get();
        assertEquals(0, deliveredFrames, "an ended session cannot deliver a frame that was waiting for its pipeline");
        assertEquals(List.of(), failures);
      } finally {
        session.stop();
        stopper.join(10_000);
        final boolean stopperStillAlive = stopper.isAlive();
        assertFalse(stopperStillAlive, "the fixture must stop its control thread");
      }
    }
  }

  private boolean countDelivery(final AtomicInteger delivered) {
    delivered.incrementAndGet();

    return false;
  }

  private <StepType> StepType countRetrieval(final CountDownLatch retrieved, final StepType step) {
    retrieved.countDown();

    return step;
  }

  private void stopWhileHoldingDelivery(
    final Object delivery,
    final PlaybackSession session,
    final CountDownLatch retrieved,
    final Thread stopper
  ) throws FrameGrabber.Exception, InterruptedException {
    synchronized (delivery) {
      session.start();
      final boolean pipelineRetrieved = retrieved.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(pipelineRetrieved, "the renderer must retrieve its pipeline");

      stopper.start();
      Polling.awaitCondition("the session stops", Duration.ofSeconds(10), () -> !session.isActive());
    }
  }

  private static Frame mediaFrame(final MediaKind media, final int value) {
    if (media == MediaKind.AUDIO) {
      final Frame frame = ScriptedFrameGrabber.audio(0);
      final ShortBuffer samples = (ShortBuffer) frame.samples[0];
      samples.put(0, (short) value);
      return frame;
    }

    return coloredFrame(value);
  }

  private static Frame coloredFrame(final int color) {
    final Frame frame = ScriptedFrameGrabber.video(0);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    for (int row = 0; row < frame.imageHeight; row++) {
      for (int column = 0; column < frame.imageWidth; column++) {
        final int offset = row * frame.imageStride + column * 3;
        pixels.put(offset, (byte) color);
        pixels.put(offset + 1, (byte) (color >>> 8));
        pixels.put(offset + 2, (byte) (color >>> 16));
      }
    }

    return frame;
  }

  private static void await(final CountDownLatch latch) {
    try {
      final boolean released = latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(released, "fixture latch must be released");
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static final class ScriptedPlayer extends AbstractVideoPlayerCv {

    private final Deque<FrameGrabber> grabbers;

    private ScriptedPlayer(final FrameGrabber... grabbers) {
      this.grabbers = new ArrayDeque<>(List.of(grabbers));
    }

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      return this.grabbers.removeFirst();
    }
  }

  private static final class TestPlayer extends AbstractVideoPlayerCv {

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      final String message = "Sessions are replaced at the boundary in this fixture!";
      throw new AssertionError(message);
    }
  }

  private enum InputKind {
    FILE,
    DIRECT,
  }

  private enum MediaKind {
    VIDEO,
    AUDIO,
  }
}

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
import static me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.take;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.jna.Memory;
import java.awt.Dimension;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.vlc.VlcTestFrames.RecordedFrame;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.co.caprica.vlcj.factory.MediaPlayerApi;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.AudioApi;
import uk.co.caprica.vlcj.player.base.ControlsApi;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.base.VideoApi;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;

/**
 * Tests {@link VLCPlayer} and {@link VLCPlayback} against a mocked VLC engine, so every failure and every race of VLC
 * can be simulated deterministically. {@link VLCPlayerIntegrationTest} plays real media through VLC.
 */
final class VLCPlayerTest {

  private static final int RED = 0xFF0000;
  private static final int GREEN = 0x00FF00;

  private final MockVlc vlc = new MockVlc();
  private final VLCPlayer player = this.vlc.newPlayer(1_000L, "--opt");
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
  private final Source video = source("video.mp4");
  private final Source audio = source("audio.ogg");

  VLCPlayerTest() {
    this.player.setExceptionHandler((message, error) -> {
        this.messages.add(message);
        this.errors.add(error);
      });
  }

  @AfterEach
  void releasePlayer() throws InterruptedException {
    this.player.release();
    VLCPlaybackTest.awaitNoPlaybackThreads();
  }

  private static Source source(final String resource) {
    final Source source = mock(Source.class);
    when(source.getResource()).thenReturn(resource);
    return source;
  }

  private int references() {
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    return shared.getReferences();
  }

  private void startVideo() {
    final boolean started = this.player.start(this.video);
    assertTrue(started, "the mocked VLC opens the video");
  }

  private void startSeparate() {
    final boolean started = this.player.start(this.video, this.audio);
    assertTrue(started, "the mocked VLC opens both sources");
  }

  /**
   * Asks VLC for the buffer of a picture of the given size, and renders the pixels into it like VLC does.
   */
  private void renderFrame(final MediaPlayer mediaPlayer, final int sourceWidth, final int sourceHeight, final int... argbPixels) {
    final BufferFormatCallback formatCallback = this.vlc.getFormatCallback();
    final RenderCallback renderCallback = this.vlc.getRenderCallback();
    final BufferFormat format = formatCallback.getBufferFormat(sourceWidth, sourceHeight);
    final ByteBuffer buffer = rv32(argbPixels);
    final ByteBuffer[] planes = { buffer };
    renderCallback.display(mediaPlayer, planes, format, sourceWidth, sourceHeight);
  }

  private void attachVideoFilter(final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback videoCallback = this.player.getVideoAttachableCallback();
    videoCallback.attach(step);
  }

  private void assertReleased(final MockVlc.Player mocked) {
    final EmbeddedMediaPlayer mediaPlayer = mocked.getPlayer();
    final ControlsApi controls = mocked.getControls();
    verify(controls).stop();
    verify(mediaPlayer).release();
  }

  private void assertEverythingReleased() throws InterruptedException {
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    for (final MockVlc.Player mocked : players) {
      final EmbeddedMediaPlayer mediaPlayer = mocked.getPlayer();
      verify(mediaPlayer).release();
    }
    final int references = this.references();
    assertEquals(0, references);
    VLCPlaybackTest.awaitNoPlaybackThreads();
  }

  private static long elapsedMillisSince(final long startNanos) {
    final long now = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(now - startNanos);
  }

  @Test
  void startsACombinedSourceOnOnePlayer() {
    final boolean started = this.player.start(this.video);
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    final MockVlc.Player only = this.vlc.player(0);
    final String resource = only.getResource();
    final String[] options = only.getOptions();
    final AudioApi audioApi = only.getAudio();
    final VideoSurfaceApi surface = only.getSurface();
    final int playerCount = players.size();
    final int references = this.references();
    final boolean noErrors = this.errors.isEmpty();
    assertTrue(started);
    assertEquals(1, playerCount);
    assertEquals("video.mp4", resource);
    assertArrayEquals(new String[] { "--opt" }, options);
    verify(audioApi).callback(eq("S16N"), eq(48_000), eq(2), any(AudioCallback.class));
    verify(surface).set(any());
    assertEquals(1, references);
    assertTrue(noErrors, "errors: " + this.errors);
  }

  @Test
  void startsSeparateSourcesOnTwoSynchronizedPlayers() {
    final boolean started = this.player.start(this.video, this.audio);
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final String[] videoOptions = videoPlayer.getOptions();
    final String[] audioOptions = audioPlayer.getOptions();
    final String audioResource = audioPlayer.getResource();
    final AudioApi videoAudioApi = videoPlayer.getAudio();
    final boolean audioCallbackOnAudioPlayer = audioPlayer.hasAudioCallback();
    final int references = this.references();
    assertTrue(started);
    assertArrayEquals(new String[] { "--opt", ":no-audio" }, videoOptions);
    assertArrayEquals(new String[] { "--opt", ":no-video" }, audioOptions);
    assertEquals("audio.ogg", audioResource);
    assertTrue(audioCallbackOnAudioPlayer);
    verify(videoAudioApi, never()).callback(anyString(), anyInt(), anyInt(), any(AudioCallback.class));
    assertEquals(1, references, "one playback holds one reference to the shared instance");

    videoPlayer.setTime(6_000L);
    audioPlayer.setTime(1_000L);
    final ControlsApi videoControls = videoPlayer.getControls();
    verify(videoControls, timeout(3_000L)).setTime(1_000L);
  }

  @Test
  void playsEqualSourcesAsOneCombinedSource() {
    final boolean started = this.player.start(this.video, this.video);
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    final MockVlc.Player only = this.vlc.player(0);
    final String[] options = only.getOptions();
    final int playerCount = players.size();
    assertTrue(started);
    assertEquals(1, playerCount);
    assertArrayEquals(new String[] { "--opt" }, options);
  }

  @Test
  void startFailsWhenVlcCannotOpenTheMedia() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.ERROR);
    final boolean started = this.player.start(this.video);
    final MockVlc.Player only = this.vlc.player(0);
    final List<String> expectedMessages = List.of("VLC playback of video.mp4 failed");
    final Throwable error = this.errors.getFirst();
    assertFalse(started, "a missing file must not count as started");
    assertEquals(expectedMessages, this.messages);
    assertInstanceOf(PlayerException.class, error);
    this.assertReleased(only);
    this.assertEverythingReleased();
  }

  @Test
  void startFailsWhenVlcRejectsTheMedia() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of("Failed to start VLC playback of video.mp4");
    final Throwable error = this.errors.getFirst();
    final PlayerException exception = assertInstanceOf(PlayerException.class, error);
    final String reason = exception.getMessage();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertEquals("VLC could not open video.mp4", reason);
    this.assertEverythingReleased();
  }

  /**
   * A way one of two separate players fails to start.
   */
  enum SeparateFailure {
    VIDEO_REJECTED(MockVlc.Outcome.REJECTED, MockVlc.Outcome.PLAYING),
    AUDIO_REJECTED(MockVlc.Outcome.PLAYING, MockVlc.Outcome.REJECTED),
    VIDEO_ERROR(MockVlc.Outcome.ERROR, MockVlc.Outcome.PLAYING),
    AUDIO_ERROR(MockVlc.Outcome.PLAYING, MockVlc.Outcome.ERROR);

    private final MockVlc.Outcome videoOutcome;
    private final MockVlc.Outcome audioOutcome;

    SeparateFailure(final MockVlc.Outcome videoOutcome, final MockVlc.Outcome audioOutcome) {
      this.videoOutcome = videoOutcome;
      this.audioOutcome = audioOutcome;
    }
  }

  @ParameterizedTest
  @EnumSource(SeparateFailure.class)
  void startFailsWhenEitherSeparateSourceCannotBeOpened(final SeparateFailure failure) throws InterruptedException {
    this.vlc.queueOutcomes(failure.videoOutcome, failure.audioOutcome);
    final boolean started = this.player.start(this.video, this.audio);
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final int reported = this.errors.size();
    assertFalse(started);
    assertEquals(1, reported, "the failure is reported once: " + this.messages);
    this.assertReleased(videoPlayer);
    this.assertReleased(audioPlayer);
    this.assertEverythingReleased();
    final ControlsApi videoControls = videoPlayer.getControls();
    verify(videoControls, after(400L).never()).setRate(anyFloat());
  }

  @Test
  void startSucceedsWhileVlcIsStillOpeningAndReportsLaterErrors() {
    final VLCPlayer slow = this.vlc.newPlayer(100L);
    final List<Throwable> slowErrors = Collections.synchronizedList(new ArrayList<>());
    slow.setExceptionHandler((_, error) -> slowErrors.add(error));
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT);
    try {
      final long start = System.nanoTime();
      final boolean started = slow.start(this.video);
      final long elapsedMillis = elapsedMillisSince(start);
      final MockVlc.Player only = this.vlc.player(0);
      final boolean noErrorsYet = slowErrors.isEmpty();
      assertTrue(started, "a stream that is still opening counts as started");
      assertTrue(elapsedMillis >= 90L, "start waits for the open timeout, took " + elapsedMillis);
      assertTrue(noErrorsYet);

      only.fireError();
      final int errorsWhilePlaying = slowErrors.size();
      final boolean released = slow.release();
      only.fireError();
      final int errorsAfterRelease = slowErrors.size();
      assertEquals(1, errorsWhilePlaying, "a later error is reported");
      assertTrue(released);
      assertEquals(1, errorsAfterRelease, "errors after the release are not reported");
    } finally {
      slow.release();
    }
  }

  @Test
  void separateSourcesShareTheOpenTimeout() {
    final VLCPlayer slow = this.vlc.newPlayer(400L);
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT, MockVlc.Outcome.SILENT);
    try {
      final long start = System.nanoTime();
      final boolean started = slow.start(this.video, this.audio);
      final long elapsedMillis = elapsedMillisSince(start);
      assertTrue(started, "sources that are still opening count as started");
      assertTrue(elapsedMillis >= 350L, "start waits for the open timeout, took " + elapsedMillis);
      assertTrue(elapsedMillis < 700L, "both sources open at the same time, so start waits the timeout once, took " + elapsedMillis);
    } finally {
      slow.release();
    }
  }

  @Test
  void startGivesUpWhenInterruptedWhileWaiting() throws InterruptedException {
    final VLCPlayer slow = this.vlc.newPlayer(10_000L);
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final long start = System.nanoTime();
    final boolean started = slow.start(this.video);
    final long elapsedMillis = elapsedMillisSince(start);
    final boolean stillInterrupted = Thread.interrupted();
    assertFalse(started);
    assertTrue(stillInterrupted, "the interrupt is kept");
    assertTrue(elapsedMillis < 5_000L, "took " + elapsedMillis);

    final MockVlc.Player only = this.vlc.player(0);
    final EmbeddedMediaPlayer mediaPlayer = only.getPlayer();
    final int references = this.references();
    verify(mediaPlayer).release();
    assertEquals(0, references);
    // the render threads were interrupted but not awaited
    VLCPlaybackTest.awaitNoPlaybackThreads();
  }

  @Test
  void startReportsAVlcInstanceThatCannotBeCreated() {
    final IllegalStateException failure = new IllegalStateException("libvlc version mismatch");
    this.vlc.failFactoryCreation(failure);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of("Failed to start VLC playback of video.mp4");
    final List<Throwable> expectedErrors = List.of(failure);
    final int references = this.references();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertEquals(expectedErrors, this.errors);
    assertEquals(0, references);
  }

  @Test
  void startReportsMissingNativeLibraries() {
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("libvlc not found");
    this.vlc.failFactoryCreation(failure);
    final boolean started = this.player.start(this.video);
    final List<Throwable> expectedErrors = List.of(failure);
    final int references = this.references();
    assertFalse(started);
    assertEquals(expectedErrors, this.errors);
    assertEquals(0, references);
  }

  @Test
  void startReleasesTheFirstPlayerWhenTheSecondCannotBeCreated() {
    final IllegalStateException failure = new IllegalStateException("out of players");
    this.vlc.failSecondPlayerCreation(failure);
    final boolean started = this.player.start(this.video, this.audio);
    final MockVlc.Player first = this.vlc.player(0);
    final EmbeddedMediaPlayer mediaPlayer = first.getPlayer();
    final List<Throwable> expectedErrors = List.of(failure);
    final int references = this.references();
    assertFalse(started);
    assertEquals(expectedErrors, this.errors);
    verify(mediaPlayer).release();
    assertEquals(0, references, "the shared instance is released again");
  }

  @Test
  void startCleansUpWhenVlcThrowsWhilePlaying() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.THROWS);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of("Failed to start VLC playback of video.mp4");
    final Throwable error = this.errors.getFirst();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertInstanceOf(IllegalStateException.class, error);
    this.assertEverythingReleased();
  }

  @Test
  void releasesTheFactoryWhenNativePlayerSetupFailsAfterAcquisition() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("player API missing");
    when(factory.mediaPlayers()).thenThrow(failure);
    final boolean started = this.player.start(this.video);
    final List<Throwable> expected = List.of(failure);
    assertFalse(started);
    assertEquals(expected, this.errors);
    verify(factory).release();
    this.assertEverythingReleased();
  }

  @Test
  void releasesTheFirstPlayerAfterANativeFailureCreatingSeparateAudio() throws InterruptedException {
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("audio player native missing");
    this.vlc.failSecondPlayerCreation(failure);
    final boolean started = this.player.start(this.video, this.audio);
    final List<Throwable> expected = List.of(failure);
    assertFalse(started);
    assertEquals(expected, this.errors);
    this.assertEverythingReleased();
  }

  @Test
  void preservesCreationFailureWhenReleasingTheFactoryFails() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("player API missing");
    final AssertionError cleanup = new AssertionError("factory release failed");
    when(factory.mediaPlayers()).thenThrow(failure);
    doThrow(cleanup).when(factory).release();
    final boolean started = this.player.start(this.video);
    final Throwable reported = this.errors.getFirst();
    final Throwable[] suppressed = reported.getSuppressed();
    assertFalse(started);
    assertSame(failure, reported);
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(factory).release();
    this.assertEverythingReleased();
  }

  @Test
  void keepsTheAudioCreationFailureWhenReleasingTheVideoPlayerFails() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi players = factory.mediaPlayers();
    final EmbeddedMediaPlayer first = mock(EmbeddedMediaPlayer.class);
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("audio creation failed");
    final IllegalStateException cleanup = new IllegalStateException("video release failed");
    doReturn(first).doThrow(failure).when(players).newEmbeddedMediaPlayer();
    doThrow(cleanup).when(first).release();
    final boolean started = this.player.start(this.video, this.audio);
    final Throwable reported = this.errors.getFirst();
    final Throwable[] suppressed = reported.getSuppressed();
    assertFalse(started);
    assertSame(failure, reported);
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(first).release();
    verify(factory).release();
    this.assertEverythingReleased();
  }

  @Test
  void cleansUpBeforeAThrowingStartFailureHandlerAndPreservesTheNativeFailure() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("video surface native missing");
    final AssertionError reporting = new AssertionError("handler failed");
    when(factory.videoSurfaces()).thenThrow(failure);
    this.player.setExceptionHandler((_, _) -> {
        throw reporting;
      });
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);
    assertSame(reporting, suppressed[0]);
    this.assertEverythingReleased();
  }

  @Test
  void aHandlerThrowingTheStartFailureDoesNotReplaceItWithSelfSuppression() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final AssertionError failure = new AssertionError("video setup failed");
    when(factory.videoSurfaces()).thenThrow(failure);
    this.player.setExceptionHandler((_, _) -> {
        throw failure;
      });
    final AssertionError thrown = assertThrows(AssertionError.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(0, suppressed.length);
    this.assertEverythingReleased();
  }

  @Test
  void releasesTheReplacementWhenStoppingTheOldPlaybackFails() throws InterruptedException {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("old stop failed");
    doThrow(failure).when(controls).stop();
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, () -> this.player.start(this.audio));
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    final int count = players.size();
    assertSame(failure, thrown);
    assertEquals(2, count, "the replacement acquired its player before the old playback stopped");
    this.assertEverythingReleased();
    this.assertControlsFail();
  }

  @Test
  void keepsTheOldStopFailureWhenReplacementCleanupAlsoFails() throws InterruptedException {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final IllegalStateException failure = new IllegalStateException("old stop failed");
    final LinkageError cleanup = new LinkageError("factory cleanup failed");
    doThrow(failure).when(controls).stop();
    doThrow(cleanup).when(factory).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    this.assertEverythingReleased();
  }

  @Test
  void propagatesFatalStartupFailuresUnwrapped() {
    final OutOfMemoryError failure = new OutOfMemoryError("VLC engine out of memory");
    this.vlc.failFactoryCreation(failure);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.player.start(this.video));
    final int references = this.references();
    final boolean noReports = this.errors.isEmpty();
    assertSame(failure, thrown);
    assertEquals(0, references);
    assertTrue(noReports);
  }

  @Test
  void firstNativeAcquisitionFailureAfterReentrantReleaseHasNoPreviousPlaybackToStop() throws Exception {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi api = factory.mediaPlayers();
    final IllegalStateException failure = new IllegalStateException("first native acquisition failed");
    final AtomicReference<Boolean> released = new AtomicReference<>();
    doAnswer(_ -> {
      released.set(this.player.release());
      throw failure;
    })
      .when(api)
      .newEmbeddedMediaPlayer();
    final boolean started = this.player.start(this.video);
    final Boolean releaseResult = released.get();
    final int references = this.references();
    final List<Throwable> errors = List.copyOf(this.errors);
    assertFalse(started);
    assertEquals(true, releaseResult);
    assertEquals(0, references);
    assertEquals(List.of(failure), errors);
    verify(factory).release();
    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  @Test
  void oldStopFailureDoesNotReclaimACandidateAlreadyCancelledByRelease() throws Exception {
    this.startVideo();
    final MockVlc.Player previous = this.vlc.player(0);
    final ControlsApi controls = previous.getControls();
    final IllegalStateException failure = new IllegalStateException("old native stop failed after cancellation");
    final AtomicReference<Boolean> released = new AtomicReference<>();
    doAnswer(_ -> {
      released.set(this.player.release());
      throw failure;
    })
      .when(controls)
      .stop();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    final Boolean releaseResult = released.get();
    final MockVlc.Player candidate = this.vlc.player(1);
    final String resource = candidate.getResource();
    assertSame(failure, thrown);
    assertEquals(true, releaseResult);
    assertNull(resource, "a cancelled candidate never opens despite the old stop failure");
    this.assertReleased(candidate);
    this.assertEverythingReleased();
  }

  @Test
  void runtimeAcquisitionFailureRemainsPrimaryWhenReportingAlsoFails() {
    final IllegalStateException failure = new IllegalStateException("native acquisition failed");
    final AssertionError reporting = new AssertionError("reporter failed");
    this.vlc.failFactoryCreation(failure);
    this.player.setExceptionHandler((_, _) -> {
        throw reporting;
      });
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    final int references = this.references();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);
    assertSame(reporting, suppressed[0]);
    assertEquals(0, references);
  }

  @Test
  void startingAgainReplacesThePreviousPlayback() {
    this.startVideo();
    final boolean restarted = this.player.start(this.audio);
    final MockVlc.Player first = this.vlc.player(0);
    final MockVlc.Player second = this.vlc.player(1);
    final EmbeddedMediaPlayer secondPlayer = second.getPlayer();
    final String secondResource = second.getResource();
    final int creations = this.vlc.getCreations();
    final int references = this.references();
    assertTrue(restarted);
    this.assertReleased(first);
    verify(secondPlayer, never()).release();
    assertEquals("audio.ogg", secondResource);
    assertEquals(1, references);
    assertEquals(1, creations, "restarting keeps the shared VLC instance instead of loading it again");
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void replacingPlaybackAllowsTheOldPipelineToReenterLifecycle(final boolean releaseInside) throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final AtomicReference<Boolean> callbackResult = new AtomicReference<>();
    this.attachVideoFilter((_, _) -> {
        entered.countDown();
        try {
          escape.await();
        } catch (final InterruptedException exception) {
          final Thread current = Thread.currentThread();
          current.interrupt();
        }
        final boolean result = releaseInside ? this.player.release() : this.player.start(this.video);
        callbackResult.set(result);
        return false;
      });
    final AtomicReference<Boolean> replacementResult = new AtomicReference<>();
    final Thread replacement = new Thread(() -> replacementResult.set(this.player.start(this.audio)), "vlc-replacement");
    replacement.setDaemon(true);
    try {
      this.startVideo();
      final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
      this.renderFrame(mediaPlayer, 1, 1, RED);
      final boolean processing = entered.await(5, TimeUnit.SECONDS);
      assertTrue(processing);
      replacement.start();
      replacement.join(1_000L);
      final boolean blocked = replacement.isAlive();
      assertFalse(blocked, "old render callbacks must acquire the player lock while replacement waits for them");
      final Boolean callback = callbackResult.get();
      final Boolean started = replacementResult.get();
      assertEquals(releaseInside, callback);
      assertEquals(!releaseInside, started);
      final List<MockVlc.Player> players = this.vlc.getPlayers();
      final int count = players.size();
      assertEquals(2, count, "callback start must not allocate a third candidate during a pending replacement");
      final MockVlc.Player candidate = this.vlc.player(1);
      if (releaseInside) {
        final String resource = candidate.getResource();
        assertNull(resource, "a candidate cancelled by release must never open its media");
        this.assertEverythingReleased();
      } else {
        final String resource = candidate.getResource();
        assertEquals("audio.ogg", resource);
      }
    } finally {
      escape.countDown();
      replacement.join(7_000L);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void pendingReplacementHasOneOwnerDuringConcurrentLifecycleCalls(final boolean cancel) throws Exception {
    this.startVideo();
    final MockVlc.Player first = this.vlc.player(0);
    final ControlsApi controls = first.getControls();
    final CountDownLatch stoppingOld = new CountDownLatch(1);
    final CountDownLatch finishStop = new CountDownLatch(1);
    doAnswer(_ -> {
      stoppingOld.countDown();
      final boolean allowed = finishStop.await(10, TimeUnit.SECONDS);
      if (!allowed) {
        throw new IllegalStateException("test did not release the old native stop");
      }
      return null;
    })
      .when(controls)
      .stop();
    final AtomicReference<Boolean> replacementResult = new AtomicReference<>();
    final AtomicReference<Boolean> competingResult = new AtomicReference<>();
    final Thread replacement = new Thread(() -> replacementResult.set(this.player.start(this.audio)), "vlc-blocked-replacement");
    final Thread competing = new Thread(
      () -> {
        final boolean result = cancel ? this.player.release() : this.player.start(this.video);
        competingResult.set(result);
      },
      "vlc-competing-lifecycle"
    );
    replacement.setDaemon(true);
    competing.setDaemon(true);
    try {
      replacement.start();
      final boolean stopping = stoppingOld.await(5, TimeUnit.SECONDS);
      assertTrue(stopping);
      competing.start();
      competing.join(1_000L);
      final boolean blocked = competing.isAlive();
      assertFalse(blocked, "pending replacement must not hold the player lock while native stop waits");
      final Boolean competingValue = competingResult.get();
      assertEquals(cancel, competingValue);
      final MockVlc.Player candidate = this.vlc.player(1);
      final String unopened = candidate.getResource();
      assertNull(unopened, "the candidate cannot open before old cleanup finishes");
      if (cancel) {
        this.assertReleased(candidate);
      }
      finishStop.countDown();
      replacement.join(2_000L);
      final boolean replacementBlocked = replacement.isAlive();
      final Boolean started = replacementResult.get();
      assertFalse(replacementBlocked);
      assertEquals(!cancel, started);
      final List<MockVlc.Player> players = this.vlc.getPlayers();
      final int count = players.size();
      assertEquals(2, count);
      if (cancel) {
        final String resource = candidate.getResource();
        assertNull(resource, "resuming the cancelled starter must not revive its released candidate");
        this.assertEverythingReleased();
      }
    } finally {
      finishStop.countDown();
      replacement.join(7_000L);
      competing.join(7_000L);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void nativeCreationReentryCannotOverwriteTheReservationOrOpenAfterRelease(final boolean failCreation) throws Exception {
    this.startVideo();
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi api = factory.mediaPlayers();
    final EmbeddedMediaPlayer created = mock(EmbeddedMediaPlayer.class);
    final ControlsApi controls = mock(ControlsApi.class);
    when(created.controls()).thenReturn(controls);
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Boolean> nestedStart = new AtomicReference<>();
    doAnswer(_ -> {
      nestedStart.set(this.player.start(this.video));
      released.set(this.player.release());
      if (failCreation) {
        throw new IllegalStateException("creation failed after reentrant release");
      }
      return created;
    })
      .when(api)
      .newEmbeddedMediaPlayer();
    final boolean started = this.player.start(this.audio);
    final Boolean releaseResult = released.get();
    final Boolean nestedResult = nestedStart.get();
    assertFalse(started);
    assertEquals(true, releaseResult);
    assertEquals(false, nestedResult, "native factory callbacks cannot recursively replace the reserved candidate");
    verify(created, never()).media();
    verify(created, never()).videoSurface();
    if (!failCreation) {
      verify(controls).stop();
      verify(created).release();
    }
    this.assertEverythingReleased();
    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  @Test
  void releaseDuringSourceSetupCannotPublishASuccessfullyOpenedCandidate() throws Exception {
    final Source cancelling = mock(Source.class);
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(cancelling.getResource()).thenAnswer(_ -> {
      if (cancelled.compareAndSet(false, true)) {
        final boolean released = this.player.release();
        assertTrue(released);
      }
      return "cancelled-during-setup.mp4";
    });
    final boolean started = this.player.start(cancelling);
    final MockVlc.Player candidate = this.vlc.player(0);
    final String opened = candidate.getResource();
    assertEquals("cancelled-during-setup.mp4", opened, "the native mock did open successfully after the callback");
    assertFalse(started, "release cancellation must win even when native opening subsequently succeeds");
    this.assertEverythingReleased();
    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  @Test
  void synchronousOpenFailureCallbackMayCancelItsOwnStart() throws Exception {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Boolean> restarted = new AtomicReference<>();
    this.player.setExceptionHandler((_, _) -> {
        restarted.set(this.player.start(this.audio));
        released.set(this.player.release());
      });
    final boolean started = this.player.start(this.video);
    final Boolean releaseResult = released.get();
    final Boolean restartResult = restarted.get();
    assertFalse(started);
    assertEquals(true, releaseResult);
    assertEquals(false, restartResult, "a reentrant native-open callback cannot start a nested candidate");
    this.assertEverythingReleased();
    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  @Test
  void aReleasedPlayerCannotBeStartedOrReleasedAgain() {
    final boolean released = this.player.release();
    final boolean releasedAgain = this.player.release();
    final boolean started = this.player.start(this.video);
    final boolean startedSeparate = this.player.start(this.video, this.audio);
    final int creations = this.vlc.getCreations();
    final boolean noErrors = this.errors.isEmpty();
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(started);
    assertFalse(startedSeparate);
    assertEquals(0, creations, "a released player does not touch VLC");
    assertTrue(noErrors);
  }

  @Test
  void releaseStopsThePlayback() throws InterruptedException {
    this.startSeparate();
    final boolean released = this.player.release();
    assertTrue(released);
    this.assertEverythingReleased();
    this.assertControlsFail();
  }

  @Test
  void controlsFailWithoutAPlayback() {
    this.assertControlsFail();
  }

  private void assertControlsFail() {
    final boolean paused = this.player.pause();
    final boolean resumed = this.player.resume();
    final boolean seeked = this.player.seek(0L);
    assertFalse(paused);
    assertFalse(resumed);
    assertFalse(seeked);
  }

  @Test
  void pausesAndResumesEveryPlayer() {
    this.startSeparate();
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final ControlsApi videoControls = videoPlayer.getControls();
    final ControlsApi audioControls = audioPlayer.getControls();

    final boolean paused = this.player.pause();
    assertTrue(paused);
    verify(videoControls).setPause(true);
    verify(audioControls).setPause(true);
    final boolean pausedAgain = this.player.pause();
    assertFalse(pausedAgain, "pausing twice fails even while VLC still reports playing");

    final boolean resumed = this.player.resume();
    assertTrue(resumed);
    verify(videoControls).setPause(false);
    verify(audioControls).setPause(false);
    final boolean resumedAgain = this.player.resume();
    assertFalse(resumedAgain, "resuming twice fails");
  }

  @Test
  void resumesRightAfterPausingAlthoughVlcStillReportsPlaying() {
    this.startVideo();
    final MockVlc.Player only = this.vlc.player(0);
    final ControlsApi controls = only.getControls();
    final boolean paused = this.player.pause();
    // VLC changes its state asynchronously, so the mocked player still reports playing here
    final boolean resumed = this.player.resume();
    assertTrue(paused);
    assertTrue(resumed);
    verify(controls).setPause(false);
  }

  @Test
  void pauseFailsWhenVlcIsNotPlaying() {
    this.startVideo();
    final MockVlc.Player only = this.vlc.player(0);
    only.fireFinished();
    final boolean paused = this.player.pause();
    final ControlsApi controls = only.getControls();
    final boolean resumed = this.player.resume();
    assertFalse(paused, "media that has ended cannot be paused");
    verify(controls, never()).setPause(true);
    assertFalse(resumed);
  }

  @Test
  void pausedPlaybackDropsFrames() throws Exception {
    final BlockingQueue<RecordedFrame> frames = new LinkedBlockingQueue<>();
    VlcTestFrames.record(this.player, frames);
    this.startVideo();
    final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    take(frames);

    final boolean paused = this.player.pause();
    assertTrue(paused);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    assertNoFrame(frames);

    final boolean resumed = this.player.resume();
    assertTrue(resumed);
    this.renderFrame(mediaPlayer, 1, 1, GREEN);
    final RecordedFrame resumedFrame = take(frames);
    final int[] rgb = resumedFrame.getRgb();
    assertArrayEquals(new int[] { GREEN }, rgb);
  }

  private void recordChunkSizes(final BlockingQueue<Integer> chunkSizes) {
    final AudioFilter recorder = (samples, _) -> {
      final int remaining = samples.remaining();
      chunkSizes.add(remaining);
      return false;
    };
    final AudioPipelineStep recording = AudioPipelineStep.of(recorder);
    final AudioAttachableCallback audioCallback = this.player.getAudioAttachableCallback();
    audioCallback.attach(recording);
  }

  /**
   * Makes the mocked VLC player report a video track of 2 by 1 pixels.
   */
  private static void reportTrackSize(final MockVlc.Player mocked) {
    final VideoApi videoApi = mocked.getVideo();
    final Dimension trackSize = new Dimension(2, 1);
    when(videoApi.videoDimension()).thenReturn(trackSize);
  }

  /**
   * Hands 480 stereo frames of silence, 1920 bytes, to the audio callback of the mocked VLC player, like VLC does.
   */
  private static void playSilence(final MockVlc.Player mocked, final MediaPlayer mediaPlayer) {
    final AudioCallback vlcAudio = mocked.getAudioCallback();
    final Memory samples = new Memory(1_920L);
    samples.clear();
    vlcAudio.play(mediaPlayer, samples, 480, 0L);
  }

  @Test
  void deliversFramesAndSamplesToThePipelines() throws Exception {
    final BlockingQueue<RecordedFrame> frames = new LinkedBlockingQueue<>();
    final BlockingQueue<Integer> chunkSizes = new LinkedBlockingQueue<>();
    VlcTestFrames.record(this.player, frames);
    this.recordChunkSizes(chunkSizes);
    this.startVideo();
    final MockVlc.Player only = this.vlc.player(0);
    reportTrackSize(only);
    final MediaPlayer mediaPlayer = only.getPlayer();
    this.renderFrame(mediaPlayer, 3, 2, RED, GREEN, 0, 0, 0, 0);
    final RecordedFrame frame = take(frames);

    playSilence(only, mediaPlayer);
    final Integer chunkSize = chunkSizes.poll(5, TimeUnit.SECONDS);

    final int[] rgb = frame.getRgb();
    final OriginalVideoMetadata metadata = frame.getMetadata();
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final Integer expectedChunkSize = 1_920;
    assertArrayEquals(new int[] { RED, GREEN }, rgb, "only the visible 2x1 part of the padded 3x2 buffer");
    assertEquals(2, metadataWidth);
    assertEquals(1, metadataHeight);
    assertEquals(expectedChunkSize, chunkSize, "480 stereo frames of 16-bit samples");
  }

  @Test
  void releaseWaitsForRunningPipelines() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicBoolean finished = new AtomicBoolean();
    this.attachVideoFilter((_, _) -> {
        entered.countDown();
        try {
          Thread.sleep(300);
        } catch (final InterruptedException exception) {
          final Thread renderThread = Thread.currentThread();
          renderThread.interrupt();
        }
        finished.set(true);
        return false;
      });
    this.startVideo();
    final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    final boolean running = entered.await(5, TimeUnit.SECONDS);
    final boolean released = this.player.release();
    final boolean finishedBeforeRelease = finished.get();
    assertTrue(running);
    assertTrue(released);
    assertTrue(finishedBeforeRelease, "no pipeline runs after release returns");
  }

  @Test
  void externalReleaseLeavesThePlayerLockAvailableToItsCallback() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    final AtomicReference<Boolean> repeatedRelease = new AtomicReference<>();
    this.attachVideoFilter((_, _) -> {
        entered.countDown();
        try {
          escape.await();
        } catch (final InterruptedException exception) {
          final Thread current = Thread.currentThread();
          current.interrupt();
        }
        repeatedRelease.set(this.player.release());
        finished.countDown();
        return false;
      });
    final AtomicReference<Boolean> firstRelease = new AtomicReference<>();
    final Thread releaser = new Thread(() -> firstRelease.set(this.player.release()), "vlc-external-release");
    releaser.setDaemon(true);
    try {
      this.startVideo();
      final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
      this.renderFrame(mediaPlayer, 1, 1, RED);
      final boolean processing = entered.await(5, TimeUnit.SECONDS);
      assertTrue(processing);
      releaser.start();
      releaser.join(1_000L);
      final boolean blocked = releaser.isAlive();
      final long unfinishedCallbacks = finished.getCount();
      assertFalse(blocked, "release must not join while holding the lock needed by the interrupted callback");
      assertEquals(0L, unfinishedCallbacks);
      final Boolean first = firstRelease.get();
      final Boolean repeated = repeatedRelease.get();
      assertEquals(true, first);
      assertEquals(false, repeated);
      this.assertEverythingReleased();
    } finally {
      escape.countDown();
      this.player.release();
      releaser.join(7_000L);
    }
  }

  @Test
  void aPipelineMayReleaseThePlayer() throws Exception {
    final AtomicReference<Boolean> releasedFromPipeline = new AtomicReference<>();
    final CountDownLatch done = new CountDownLatch(1);
    this.attachVideoFilter((_, _) -> {
        final boolean released = this.player.release();
        releasedFromPipeline.set(released);
        done.countDown();
        return false;
      });
    this.startVideo();
    final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    final boolean finished = done.await(5, TimeUnit.SECONDS);
    assertTrue(finished, "releasing from the render thread must not deadlock");
    VLCPlaybackTest.awaitNoPlaybackThreads();
    final Boolean released = releasedFromPipeline.get();
    final int references = this.references();
    assertEquals(true, released);
    assertEquals(0, references);
  }

  @Test
  void seeksEveryPlayer() {
    this.startSeparate();
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    audioPlayer.setState(State.PAUSED);
    final boolean seeked = this.player.seek(1_234L);
    final ControlsApi videoControls = videoPlayer.getControls();
    final ControlsApi audioControls = audioPlayer.getControls();
    assertTrue(seeked);
    verify(videoControls).setTime(1_234L);
    verify(audioControls).setTime(1_234L);
  }

  /**
   * A reason why a playback cannot seek.
   */
  enum SeekFailure {
    VIDEO_NOT_SEEKABLE,
    AUDIO_NOT_SEEKABLE,
    VIDEO_ENDED,
    AUDIO_STOPPED,
    VIDEO_FAILED,
    AUDIO_NOTHING_OPEN,
  }

  @ParameterizedTest
  @EnumSource(SeekFailure.class)
  void seekFailsWhenAPlayerCannotSeek(final SeekFailure failure) {
    this.startSeparate();
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    switch (failure) {
      case VIDEO_NOT_SEEKABLE -> videoPlayer.makeUnseekable();
      case AUDIO_NOT_SEEKABLE -> audioPlayer.makeUnseekable();
      case VIDEO_ENDED -> videoPlayer.setState(State.ENDED);
      case AUDIO_STOPPED -> audioPlayer.setState(State.STOPPED);
      case VIDEO_FAILED -> videoPlayer.setState(State.ERROR);
      case AUDIO_NOTHING_OPEN -> audioPlayer.setState(State.NOTHING_SPECIAL);
    }
    final boolean seeked = this.player.seek(1_000L);
    final ControlsApi videoControls = videoPlayer.getControls();
    final ControlsApi audioControls = audioPlayer.getControls();
    assertFalse(seeked);
    verify(videoControls, never()).setTime(1_000L);
    verify(audioControls, never()).setTime(anyLong());
  }

  @Test
  void seeksWhileVlcIsStillOpeningOrBuffering() {
    this.startVideo();
    final MockVlc.Player only = this.vlc.player(0);
    only.setState(State.OPENING);
    final boolean seekedWhileOpening = this.player.seek(10L);
    only.setState(State.BUFFERING);
    final boolean seekedWhileBuffering = this.player.seek(20L);
    assertTrue(seekedWhileOpening);
    assertTrue(seekedWhileBuffering);
  }

  @Test
  void rejectsInvalidArguments() {
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    assertThrows(IllegalArgumentException.class, () -> this.player.seek(-1L));
    assertThrows(NullPointerException.class, () -> this.player.start(null));
    assertThrows(NullPointerException.class, () -> this.player.start(null, this.audio));
    assertThrows(NullPointerException.class, () -> this.player.start(this.video, null));
    assertThrows(NullPointerException.class, () -> this.player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> new VLCPlayer(null, 1L));
    assertThrows(IllegalArgumentException.class, () -> new VLCPlayer(shared, -1L));
    assertThrows(NullPointerException.class, () -> new VLCPlayer(shared, 1L, (String[]) null));
    assertThrows(NullPointerException.class, () -> new VLCPlayer(shared, 1L, "--ok", null));
    assertThrows(NullPointerException.class, () -> new VLCPlayer((String[]) null));
    assertThrows(NullPointerException.class, () -> new VLCPlayer("--ok", null));
  }

  @Test
  void exposesItsSlotsAndExceptionHandler() {
    final VLCPlayer fresh = this.vlc.newPlayer(1L);
    final BiConsumer<String, Throwable> defaultHandler = fresh.getExceptionHandler();
    final VideoAttachableCallback firstVideo = fresh.getVideoAttachableCallback();
    final VideoAttachableCallback secondVideo = fresh.getVideoAttachableCallback();
    final AudioAttachableCallback firstAudio = fresh.getAudioAttachableCallback();
    final AudioAttachableCallback secondAudio = fresh.getAudioAttachableCallback();
    final DimensionAttachableCallback firstDimension = fresh.getDimensionAttachableCallback();
    final DimensionAttachableCallback secondDimension = fresh.getDimensionAttachableCallback();
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    fresh.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> replaced = fresh.getExceptionHandler();
    assertNotNull(defaultHandler);
    assertSame(firstVideo, secondVideo);
    assertSame(firstAudio, secondAudio);
    assertSame(firstDimension, secondDimension);
    assertSame(handler, replaced);
  }

  @Test
  void copiesTheMediaOptions() {
    final String[] options = { "--first" };
    final VLCPlayer copying = this.vlc.newPlayer(1_000L, options);
    options[0] = "--changed";
    final String[] created = copying.createMediaOptions(null);
    created[0] = "--changed-again";
    final String[] again = copying.createMediaOptions(null);
    final String[] extended = copying.createMediaOptions(":extra");
    assertArrayEquals(new String[] { "--first" }, again);
    assertArrayEquals(new String[] { "--first", ":extra" }, extended);
    copying.release();
  }

  @ParameterizedTest
  @ValueSource(strings = { "pause", "resume", "seek" })
  void idleControlsAllowReleaseFromAnotherThread(final String operation) throws Exception {
    final boolean controlled =
      switch (operation) {
        case "pause" -> this.player.pause();
        case "resume" -> this.player.resume();
        case "seek" -> this.player.seek(100L);
        default -> throw new AssertionError(operation);
      };
    assertFalse(controlled);
    final CompletableFuture<Boolean> release = CompletableFuture.supplyAsync(this.player::release);
    final boolean released = release.get(5L, TimeUnit.SECONDS);
    assertTrue(released, "an idle control call must relinquish the player lock");
  }

  @Test
  void failedReplacementAllowsANewStartFromAnotherThread() throws Exception {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final IllegalStateException failure = new IllegalStateException("old stop failed");
    doThrow(failure).when(controls).stop();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    assertSame(failure, thrown);
    final CompletableFuture<Boolean> restart = CompletableFuture.supplyAsync(() -> this.player.start(this.video));
    final boolean restarted = restart.get(5L, TimeUnit.SECONDS);
    assertTrue(restarted, "failed replacement must clear its reservation and relinquish its lock");
  }

  @Test
  void failedAcquisitionAllowsReleaseFromAnotherThread() throws Exception {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("player API missing");
    when(factory.mediaPlayers()).thenThrow(failure);
    final boolean started = this.player.start(this.video);
    assertFalse(started);
    final CompletableFuture<Boolean> release = CompletableFuture.supplyAsync(this.player::release);
    final boolean released = release.get(5L, TimeUnit.SECONDS);
    assertTrue(released, "failed acquisition must relinquish its lock");
  }

  @Test
  void equalSourcesPreserveAFailedStart() {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final boolean started = this.player.start(this.video, this.video);
    assertFalse(started);
  }

  @Test
  void acceptsAZeroOpenTimeout() {
    final SharedMediaPlayerFactory factory = mock(SharedMediaPlayerFactory.class);
    final VLCPlayer player = new VLCPlayer(factory, 0L);
    try {
      final long timeoutMillis = player.getOpenTimeoutMillis();
      assertEquals(0L, timeoutMillis);
    } finally {
      player.release();
    }
  }

  @Test
  void thePublicConstructorUsesTheSharedInstance() {
    final VLCPlayer shared = new VLCPlayer("--x");
    final SharedMediaPlayerFactory factory = shared.getSharedFactory();
    final SharedMediaPlayerFactory instance = SharedMediaPlayerFactory.getInstance();
    final long timeout = shared.getOpenTimeoutMillis();
    final String[] options = shared.createMediaOptions(null);
    final boolean released = shared.release();
    assertSame(instance, factory);
    assertEquals(5_000L, timeout);
    assertArrayEquals(new String[] { "--x" }, options);
    assertTrue(released);
  }
}

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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.checkerframework.checker.nullness.qual.Nullable;
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

// Tests VlcPlayer and VlcPlayback against a mocked VLC engine, so every failure and every race of VLC can be simulated
// deterministically. VlcPlayerIntegrationTest plays real media through VLC.
final class VlcPlayerTest {

  private static final int RED = 0xFF0000;
  private static final int GREEN = 0x00FF00;

  private static final String OPTION = "--opt";
  private static final String VIDEO_RESOURCE = "video.mp4";
  private static final String AUDIO_RESOURCE = "audio.ogg";
  private static final String CANCELLED_DURING_SETUP_RESOURCE = "cancelled-during-setup.mp4";

  private static final long WAIT_SECONDS = 5L;
  private static final long UNBLOCKED_JOIN_MILLISECONDS = 1_000L;
  private static final long CLEANUP_JOIN_MILLISECONDS = 7_000L;

  private static final long OPEN_TIMEOUT_MILLISECONDS = 1_000L;

  private static final int PLAYERS_WITH_REPLACEMENT = 2;

  private static final String VIDEO_START_FAILURE_MESSAGE = "VLC playback of video.mp4 could not be started.";

  private static final String PLAYER_API_MISSING_MESSAGE = "player API missing!";
  private static final String OLD_STOP_FAILURE_MESSAGE = "old stop failed!";

  private static final String START_RESERVED_MESSAGE = "the start stays reserved, so nothing more runs on a broken virtual machine";

  private final MockVlc vlc = new MockVlc();
  private final VlcPlayer player = this.vlc.newPlayer(OPEN_TIMEOUT_MILLISECONDS, OPTION);
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
  private final Source video = source(VIDEO_RESOURCE);
  private final Source audio = source(AUDIO_RESOURCE);

  VlcPlayerTest() {
    this.player.setExceptionHandler(this::recordFailure);
  }

  private void recordFailure(final String message, final Throwable error) {
    this.messages.add(message);
    this.errors.add(error);
  }

  @AfterEach
  void releasePlayer() throws InterruptedException {
    this.player.release();
    VlcPlaybackTest.awaitNoPlaybackThreads();
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

  private static void assertReleased(final MockVlc.Player mocked) {
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

    VlcPlaybackTest.awaitNoPlaybackThreads();
  }

  private static long elapsedMillisecondsSince(final long startNanoseconds) {
    final long now = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(now - startNanoseconds);
  }

  @Test
  void shouldStartACombinedSourceOnOnePlayer() {
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
    assertEquals(VIDEO_RESOURCE, resource);
    final String[] expected = new String[] { OPTION };
    assertArrayEquals(expected, options);
    verify(audioApi).callback(eq("S16N"), eq(48_000), eq(2), any(AudioCallback.class));
    verify(surface).set(any());
    assertEquals(1, references);
    assertTrue(noErrors, "errors: " + this.errors);
  }

  @Test
  void shouldStartSeparateSourcesOnTwoSynchronizedPlayers() {
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
    final String[] expectedVideoOptions = new String[] { OPTION, ":no-audio" };
    assertArrayEquals(expectedVideoOptions, videoOptions);
    final String[] expectedAudioOptions = new String[] { OPTION, ":no-video" };
    assertArrayEquals(expectedAudioOptions, audioOptions);
    assertEquals(AUDIO_RESOURCE, audioResource);
    assertTrue(audioCallbackOnAudioPlayer);
    verify(videoAudioApi, never()).callback(anyString(), anyInt(), anyInt(), any(AudioCallback.class));
    assertEquals(1, references, "one playback holds one reference to the shared instance");

    videoPlayer.setTime(6_000L);
    audioPlayer.setTime(1_000L);
    final ControlsApi videoControls = videoPlayer.getControls();
    verify(videoControls, timeout(3_000L)).setTime(1_000L);
  }

  @Test
  void shouldPlayEqualSourcesAsOneCombinedSource() {
    final boolean started = this.player.start(this.video, this.video);
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    final MockVlc.Player only = this.vlc.player(0);
    final String[] options = only.getOptions();
    final int playerCount = players.size();
    assertTrue(started);
    assertEquals(1, playerCount);
    final String[] expected = new String[] { OPTION };
    assertArrayEquals(expected, options);
  }

  @Test
  void shouldFailToStartWhenVlcCannotOpenTheMedia() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.ERROR);
    final boolean started = this.player.start(this.video);
    final MockVlc.Player only = this.vlc.player(0);
    final List<String> expectedMessages = List.of("VLC playback of video.mp4 failed.");
    final Throwable error = this.errors.getFirst();
    assertFalse(started, "a missing file must not count as started");
    assertEquals(expectedMessages, this.messages);
    assertInstanceOf(PlayerException.class, error);
    assertReleased(only);

    this.assertEverythingReleased();
  }

  @Test
  void shouldFailToStartWhenVlcRejectsTheMedia() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of(VIDEO_START_FAILURE_MESSAGE);
    final Throwable error = this.errors.getFirst();
    final PlayerException exception = assertInstanceOf(PlayerException.class, error);
    final String reason = exception.getMessage();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertEquals("Media video.mp4 could not be opened by VLC!", reason);

    this.assertEverythingReleased();
  }

  @ParameterizedTest
  @EnumSource(SeparateFailure.class)
  void shouldFailToStartWhenEitherSeparateSourceCannotBeOpened(final SeparateFailure failure) throws InterruptedException {
    this.vlc.queueOutcomes(failure.videoOutcome, failure.audioOutcome);
    final boolean started = this.player.start(this.video, this.audio);
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final int reported = this.errors.size();
    assertFalse(started);
    assertEquals(1, reported, "the failure is reported once: " + this.messages);
    assertReleased(videoPlayer);
    assertReleased(audioPlayer);

    this.assertEverythingReleased();
    final ControlsApi videoControls = videoPlayer.getControls();
    verify(videoControls, after(400L).never()).setRate(anyFloat());
  }

  @Test
  void shouldStartWhileVlcIsStillOpeningAndReportLaterErrors() {
    final VlcPlayer slow = this.vlc.newPlayer(100L);
    final List<Throwable> slowErrors = Collections.synchronizedList(new ArrayList<>());
    slow.setExceptionHandler((_, error) -> slowErrors.add(error));
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT);
    try {
      final long start = System.nanoTime();
      final boolean started = slow.start(this.video);
      final long elapsedMilliseconds = elapsedMillisecondsSince(start);
      final MockVlc.Player only = this.vlc.player(0);
      final boolean noErrorsYet = slowErrors.isEmpty();
      assertTrue(started, "a stream that is still opening counts as started");

      final boolean waitedForTimeout = elapsedMilliseconds >= 90L;
      assertTrue(waitedForTimeout, "start waits for the open timeout, took " + elapsedMilliseconds);
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
  void shouldShareTheOpenTimeoutBetweenSeparateSources() {
    final VlcPlayer slow = this.vlc.newPlayer(400L);
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT, MockVlc.Outcome.SILENT);
    try {
      final long start = System.nanoTime();
      final boolean started = slow.start(this.video, this.audio);
      final long elapsedMilliseconds = elapsedMillisecondsSince(start);
      assertTrue(started, "sources that are still opening count as started");

      final boolean waitedForTimeout = elapsedMilliseconds >= 350L;
      assertTrue(waitedForTimeout, "start waits for the open timeout, took " + elapsedMilliseconds);

      final boolean waitedOnce = elapsedMilliseconds < 700L;
      assertTrue(waitedOnce, "both sources open at the same time, so start waits the timeout once, took " + elapsedMilliseconds);
    } finally {
      slow.release();
    }
  }

  @Test
  void shouldGiveUpStartingWhenInterruptedWhileWaiting() throws InterruptedException {
    final VlcPlayer slow = this.vlc.newPlayer(10_000L);
    this.vlc.queueOutcomes(MockVlc.Outcome.SILENT);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final long start = System.nanoTime();
    final boolean started = slow.start(this.video);
    final long elapsedMilliseconds = elapsedMillisecondsSince(start);
    final boolean stillInterrupted = Thread.interrupted();
    assertFalse(started);
    assertTrue(stillInterrupted, "the interrupt is kept");

    final boolean gaveUpPromptly = elapsedMilliseconds < 5_000L;
    assertTrue(gaveUpPromptly, "took " + elapsedMilliseconds);

    final MockVlc.Player only = this.vlc.player(0);
    final EmbeddedMediaPlayer mediaPlayer = only.getPlayer();
    final int references = this.references();
    verify(mediaPlayer).release();
    assertEquals(0, references);

    VlcPlaybackTest.awaitNoPlaybackThreads();
  }

  @Test
  void shouldReportAVlcInstanceThatCannotBeCreatedOnStart() {
    final IllegalStateException failure = new IllegalStateException("libvlc version mismatch!");
    this.vlc.failFactoryCreation(failure);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of(VIDEO_START_FAILURE_MESSAGE);
    final List<Throwable> expectedErrors = List.of(failure);
    final int references = this.references();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertEquals(expectedErrors, this.errors);
    assertEquals(0, references);
  }

  @Test
  void shouldReportMissingNativeLibrariesOnStart() {
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("libvlc not found!");
    this.vlc.failFactoryCreation(failure);
    final boolean started = this.player.start(this.video);
    final List<Throwable> expectedErrors = List.of(failure);
    final int references = this.references();
    assertFalse(started);
    assertEquals(expectedErrors, this.errors);
    assertEquals(0, references);
  }

  @Test
  void shouldReleaseTheFirstPlayerOnStartWhenTheSecondCannotBeCreated() {
    final IllegalStateException failure = new IllegalStateException("out of players!");
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
  void shouldCleanUpOnStartWhenVlcThrowsWhilePlaying() throws InterruptedException {
    this.vlc.queueOutcomes(MockVlc.Outcome.THROWS);
    final boolean started = this.player.start(this.video);
    final List<String> expectedMessages = List.of(VIDEO_START_FAILURE_MESSAGE);
    final Throwable error = this.errors.getFirst();
    assertFalse(started);
    assertEquals(expectedMessages, this.messages);
    assertInstanceOf(IllegalStateException.class, error);

    this.assertEverythingReleased();
  }

  @Test
  void shouldReleaseTheFactoryWhenNativePlayerSetupFailsAfterAcquisition() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError(PLAYER_API_MISSING_MESSAGE);
    when(factory.mediaPlayers()).thenThrow(failure);
    final boolean started = this.player.start(this.video);
    final List<Throwable> expected = List.of(failure);
    assertFalse(started);
    assertEquals(expected, this.errors);
    verify(factory).release();

    this.assertEverythingReleased();
  }

  @Test
  void shouldReleaseTheFirstPlayerAfterANativeFailureCreatingSeparateAudio() throws InterruptedException {
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("audio player native missing!");
    this.vlc.failSecondPlayerCreation(failure);
    final boolean started = this.player.start(this.video, this.audio);
    final List<Throwable> expected = List.of(failure);
    assertFalse(started);
    assertEquals(expected, this.errors);

    this.assertEverythingReleased();
  }

  @Test
  void shouldPreserveCreationFailureWhenReleasingTheFactoryFails() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError(PLAYER_API_MISSING_MESSAGE);
    final AssertionError cleanup = new AssertionError("factory release failed!");
    when(factory.mediaPlayers()).thenThrow(failure);
    doThrow(cleanup).when(factory).release();
    final boolean started = this.player.start(this.video);
    final Throwable reported = this.errors.getFirst();
    final Throwable[] suppressed = reported.getSuppressed();
    assertFalse(started);
    assertSame(failure, reported);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(cleanup, firstSuppressed);
    verify(factory).release();

    this.assertEverythingReleased();
  }

  @Test
  void shouldKeepTheAudioCreationFailureWhenReleasingTheVideoPlayerFails() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi players = factory.mediaPlayers();
    final EmbeddedMediaPlayer first = mock(EmbeddedMediaPlayer.class);
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("audio creation failed!");
    final IllegalStateException cleanup = new IllegalStateException("video release failed!");
    doReturn(first).doThrow(failure).when(players).newEmbeddedMediaPlayer();
    doThrow(cleanup).when(first).release();
    final boolean started = this.player.start(this.video, this.audio);
    final Throwable reported = this.errors.getFirst();
    final Throwable[] suppressed = reported.getSuppressed();
    assertFalse(started);
    assertSame(failure, reported);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(cleanup, firstSuppressed);
    verify(first).release();
    verify(factory).release();

    this.assertEverythingReleased();
  }

  @Test
  void shouldCleanUpBeforeAThrowingStartFailureHandlerAndPreserveTheNativeFailure() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("video surface native missing!");
    final AssertionError reporting = new AssertionError("handler failed!");
    when(factory.videoSurfaces()).thenThrow(failure);
    this.player.setExceptionHandler((_, _) -> this.rethrow(reporting));
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(reporting, firstSuppressed);

    this.assertEverythingReleased();
  }

  private void rethrow(final AssertionError failure) {
    throw failure;
  }

  @Test
  void shouldNotReplaceTheStartFailureWithSelfSuppressionWhenAHandlerThrowsIt() throws InterruptedException {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final AssertionError failure = new AssertionError("video setup failed!");
    when(factory.videoSurfaces()).thenThrow(failure);
    this.player.setExceptionHandler((_, _) -> this.rethrow(failure));
    final AssertionError thrown = assertThrows(AssertionError.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(0, suppressed.length);

    this.assertEverythingReleased();
  }

  @Test
  void shouldReleaseTheReplacementWhenStoppingTheOldPlaybackFails() throws InterruptedException {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError(OLD_STOP_FAILURE_MESSAGE);
    doThrow(failure).when(controls).stop();
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, () -> this.player.start(this.audio));
    final List<MockVlc.Player> players = this.vlc.getPlayers();
    final int count = players.size();
    assertSame(failure, thrown);
    assertEquals(PLAYERS_WITH_REPLACEMENT, count, "the replacement acquired its player before the old playback stopped");

    this.assertEverythingReleased();
    this.assertControlsFail();
  }

  @Test
  void shouldKeepTheOldStopFailureWhenReplacementCleanupAlsoFails() throws InterruptedException {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final IllegalStateException failure = new IllegalStateException(OLD_STOP_FAILURE_MESSAGE);
    final LinkageError cleanup = new LinkageError("factory cleanup failed!");
    doThrow(failure).when(controls).stop();
    doThrow(cleanup).when(factory).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(cleanup, firstSuppressed);

    this.assertEverythingReleased();
  }

  @Test
  void shouldPropagateFatalStartupFailuresUnwrapped() {
    final OutOfMemoryError failure = new OutOfMemoryError("VLC engine out of memory!");
    this.vlc.failFactoryCreation(failure);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.player.start(this.video));
    final int references = this.references();
    final boolean noReports = this.errors.isEmpty();
    assertSame(failure, thrown);
    assertEquals(0, references);
    assertTrue(noReports);
  }

  @Test
  void shouldRunNoCleanupAfterAFatalErrorCreatingTheAudioPlayer() {
    final OutOfMemoryError failure = new OutOfMemoryError("audio player out of memory!");
    this.vlc.failSecondPlayerCreation(failure);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.player.start(this.video, this.audio));
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final EmbeddedMediaPlayer mediaPlayer = videoPlayer.getPlayer();
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final boolean restarted = this.player.start(this.video);
    assertSame(failure, thrown);
    verify(mediaPlayer, never()).release();
    verify(factory, never()).release();
    assertFalse(restarted, START_RESERVED_MESSAGE);
  }

  @Test
  void shouldRunNoCleanupAfterAFatalErrorStoppingTheOldPlayback() {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final EmbeddedMediaPlayer oldPlayer = old.getPlayer();
    final OutOfMemoryError failure = new OutOfMemoryError("old player release out of memory!");
    doThrow(failure).when(oldPlayer).release();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.player.start(this.audio));
    final MockVlc.Player replacement = this.vlc.player(1);
    final EmbeddedMediaPlayer replacementPlayer = replacement.getPlayer();
    assertSame(failure, thrown);
    verify(replacementPlayer, never()).release();

    final boolean restarted = this.player.start(this.video);
    assertFalse(restarted, START_RESERVED_MESSAGE);
  }

  @Test
  void shouldRunNoCleanupAfterAFatalErrorStartingThePlayback() {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi surfaces = factory.videoSurfaces(); // fqn: VideoSurfaceApi is imported as uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi
    final OutOfMemoryError failure = new OutOfMemoryError("video surface out of memory!");
    doThrow(failure).when(surfaces).newVideoSurface(any(BufferFormatCallback.class), any(RenderCallback.class), anyBoolean());
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.player.start(this.video));
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final EmbeddedMediaPlayer mediaPlayer = videoPlayer.getPlayer();
    assertSame(failure, thrown);
    verify(mediaPlayer, never()).release();
    verify(factory, never()).release();

    final boolean restarted = this.player.start(this.video);
    assertFalse(restarted, START_RESERVED_MESSAGE);
  }

  @Test
  void shouldHaveNoPreviousPlaybackToStopOnTheFirstNativeAcquisitionFailureAfterAReentrantRelease() throws Exception {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi api = factory.mediaPlayers();
    final IllegalStateException failure = new IllegalStateException("first native acquisition failed!");
    final AtomicReference<Boolean> released = new AtomicReference<>();
    doAnswer(_ -> this.releaseAndFail(released, failure))
      .when(api)
      .newEmbeddedMediaPlayer();
    final boolean started = this.player.start(this.video);
    final Boolean releaseResult = released.get();
    final int references = this.references();
    final List<Throwable> errors = List.copyOf(this.errors);
    assertFalse(started);
    assertEquals(true, releaseResult);
    assertEquals(0, references);
    final List<Throwable> expected = List.of(failure);
    assertEquals(expected, errors);
    verify(factory).release();

    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  private @Nullable Object releaseAndFail(final AtomicReference<Boolean> released, final IllegalStateException failure) {
    released.set(this.player.release());
    throw failure;
  }

  @Test
  void shouldNotReclaimACandidateAlreadyCancelledByReleaseOnAnOldStopFailure() throws Exception {
    this.startVideo();
    final MockVlc.Player previous = this.vlc.player(0);
    final ControlsApi controls = previous.getControls();
    final IllegalStateException failure = new IllegalStateException("old native stop failed after cancellation!");
    final AtomicReference<Boolean> released = new AtomicReference<>();
    doAnswer(_ -> this.releaseAndFail(released, failure))
      .when(controls)
      .stop();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    final Boolean releaseResult = released.get();
    final MockVlc.Player candidate = this.vlc.player(1);
    final String resource = candidate.getResource();
    assertSame(failure, thrown);
    assertEquals(true, releaseResult);
    assertNull(resource, "a cancelled candidate never opens despite the old stop failure");
    assertReleased(candidate);

    this.assertEverythingReleased();
  }

  @Test
  void shouldKeepTheRuntimeAcquisitionFailurePrimaryWhenReportingAlsoFails() {
    final IllegalStateException failure = new IllegalStateException("native acquisition failed!");
    final AssertionError reporting = new AssertionError("reporter failed!");
    this.vlc.failFactoryCreation(failure);
    this.player.setExceptionHandler((_, _) -> this.rethrow(reporting));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.video));
    final Throwable[] suppressed = thrown.getSuppressed();
    final int references = this.references();
    assertSame(failure, thrown);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(reporting, firstSuppressed);
    assertEquals(0, references);
  }

  @Test
  void shouldReplaceThePreviousPlaybackWhenStartingAgain() {
    this.startVideo();
    final boolean restarted = this.player.start(this.audio);
    final MockVlc.Player first = this.vlc.player(0);
    final MockVlc.Player second = this.vlc.player(1);
    final EmbeddedMediaPlayer secondPlayer = second.getPlayer();
    final String secondResource = second.getResource();
    final int creations = this.vlc.getCreations();
    final int references = this.references();
    assertTrue(restarted);
    assertReleased(first);
    verify(secondPlayer, never()).release();
    assertEquals(AUDIO_RESOURCE, secondResource);
    assertEquals(1, references);
    assertEquals(1, creations, "restarting keeps the shared VLC instance instead of loading it again");
  }

  @ParameterizedTest
  @EnumSource(LifecycleCall.class)
  void shouldLetTheOldPipelineReenterLifecycleWhenReplacingPlayback(final LifecycleCall innerCall) throws Exception {
    final boolean releaseInside = innerCall == LifecycleCall.RELEASE;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final AtomicReference<Boolean> callbackResult = new AtomicReference<>();
    this.attachVideoFilter((_, _) -> this.reenterLifecycleAfterEscape(entered, escape, innerCall, callbackResult));
    final AtomicReference<Boolean> replacementResult = new AtomicReference<>();
    final Thread replacement = new Thread(() -> replacementResult.set(this.player.start(this.audio)), "vlc-replacement");
    replacement.setDaemon(true);
    try {
      this.startVideo();
      final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
      this.renderFrame(mediaPlayer, 1, 1, RED);
      final boolean processing = entered.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(processing);

      replacement.start();
      replacement.join(UNBLOCKED_JOIN_MILLISECONDS);
      final boolean blocked = replacement.isAlive();
      assertFalse(blocked, "old render callbacks must acquire the player lock while replacement waits for them");

      final Boolean callback = callbackResult.get();
      final Boolean started = replacementResult.get();
      assertEquals(releaseInside, callback);
      final boolean expected = !releaseInside;
      assertEquals(expected, started);

      final List<MockVlc.Player> players = this.vlc.getPlayers();
      final int count = players.size();
      assertEquals(PLAYERS_WITH_REPLACEMENT, count, "callback start must not allocate a third candidate during a pending replacement");

      final MockVlc.Player candidate = this.vlc.player(1);
      if (releaseInside) {
        final String resource = candidate.getResource();
        assertNull(resource, "a candidate cancelled by release must never open its media");

        this.assertEverythingReleased();
      } else {
        final String resource = candidate.getResource();
        assertEquals(AUDIO_RESOURCE, resource);
      }
    } finally {
      escape.countDown();
      replacement.join(CLEANUP_JOIN_MILLISECONDS);
    }
  }

  private boolean reenterLifecycleAfterEscape(
    final CountDownLatch entered,
    final CountDownLatch escape,
    final LifecycleCall innerCall,
    final AtomicReference<Boolean> callbackResult
  ) {
    entered.countDown();
    try {
      escape.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    final boolean succeeded = this.callLifecycle(innerCall);
    callbackResult.set(succeeded);

    return false;
  }

  @ParameterizedTest
  @EnumSource(LifecycleCall.class)
  void shouldGiveAPendingReplacementOneOwnerDuringConcurrentLifecycleCalls(final LifecycleCall competingCall) throws Exception {
    final boolean releases = competingCall == LifecycleCall.RELEASE;
    this.startVideo();
    final MockVlc.Player first = this.vlc.player(0);
    final ControlsApi controls = first.getControls();
    final CountDownLatch stoppingOld = new CountDownLatch(1);
    final CountDownLatch finishStop = new CountDownLatch(1);
    doAnswer(_ -> this.blockOldStop(stoppingOld, finishStop))
      .when(controls)
      .stop();
    final AtomicReference<Boolean> replacementResult = new AtomicReference<>();
    final AtomicReference<Boolean> competingResult = new AtomicReference<>();
    final Thread replacement = new Thread(() -> replacementResult.set(this.player.start(this.audio)), "vlc-blocked-replacement");
    final Thread competing = new Thread(() -> this.competeForLifecycle(competingCall, competingResult), "vlc-competing-lifecycle");
    replacement.setDaemon(true);
    competing.setDaemon(true);
    try {
      replacement.start();
      final boolean stopping = stoppingOld.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(stopping);

      competing.start();
      competing.join(UNBLOCKED_JOIN_MILLISECONDS);
      final boolean blocked = competing.isAlive();
      assertFalse(blocked, "pending replacement must not hold the player lock while native stop waits");

      final Boolean competingValue = competingResult.get();
      assertEquals(releases, competingValue);

      final MockVlc.Player candidate = this.vlc.player(1);
      final String unopened = candidate.getResource();
      assertNull(unopened, "the candidate cannot open before old cleanup finishes");

      if (releases) {
        assertReleased(candidate);
      }

      finishStop.countDown();
      replacement.join(2_000L);
      final boolean replacementBlocked = replacement.isAlive();
      final Boolean started = replacementResult.get();
      assertFalse(replacementBlocked);
      final boolean expected = !releases;
      assertEquals(expected, started);

      final List<MockVlc.Player> players = this.vlc.getPlayers();
      final int count = players.size();
      assertEquals(PLAYERS_WITH_REPLACEMENT, count);

      if (releases) {
        final String resource = candidate.getResource();
        assertNull(resource, "resuming the cancelled starter must not revive its released candidate");

        this.assertEverythingReleased();
      }
    } finally {
      finishStop.countDown();
      replacement.join(CLEANUP_JOIN_MILLISECONDS);
      competing.join(CLEANUP_JOIN_MILLISECONDS);
    }
  }

  private @Nullable Object blockOldStop(final CountDownLatch stoppingOld, final CountDownLatch finishStop) throws InterruptedException {
    stoppingOld.countDown();
    final boolean allowed = finishStop.await(10, TimeUnit.SECONDS);
    if (!allowed) {
      final String message = "test did not release the old native stop!";
      throw new IllegalStateException(message);
    }

    return null;
  }

  private void competeForLifecycle(final LifecycleCall competingCall, final AtomicReference<Boolean> competingResult) {
    final boolean succeeded = this.callLifecycle(competingCall);
    competingResult.set(succeeded);
  }

  private boolean callLifecycle(final LifecycleCall call) {
    return switch (call) {
      case START -> this.player.start(this.video);
      case RELEASE -> this.player.release();
    };
  }

  @ParameterizedTest
  @EnumSource(CreationOutcome.class)
  void shouldNotLetNativeCreationReentryOverwriteTheReservationOrOpenAfterRelease(final CreationOutcome creation) throws Exception {
    final boolean failCreation = creation == CreationOutcome.FAILS;
    this.startVideo();
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final MediaPlayerApi api = factory.mediaPlayers();
    final EmbeddedMediaPlayer created = mock(EmbeddedMediaPlayer.class);
    final ControlsApi controls = mock(ControlsApi.class);
    when(created.controls()).thenReturn(controls);
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Boolean> nestedStart = new AtomicReference<>();
    doAnswer(_ -> this.reenterCreation(nestedStart, released, creation, created))
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

  private EmbeddedMediaPlayer reenterCreation(
    final AtomicReference<Boolean> nestedStart,
    final AtomicReference<Boolean> released,
    final CreationOutcome creation,
    final EmbeddedMediaPlayer created
  ) {
    nestedStart.set(this.player.start(this.video));
    released.set(this.player.release());

    if (creation == CreationOutcome.FAILS) {
      final String message = "creation failed after reentrant release!";
      throw new IllegalStateException(message);
    }

    return created;
  }

  @Test
  void shouldNotPublishASuccessfullyOpenedCandidateOnReleaseDuringSourceSetup() throws Exception {
    final Source cancelling = mock(Source.class);
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(cancelling.getResource()).thenAnswer(_ -> this.releaseOnFirstSetup(cancelled));
    final boolean started = this.player.start(cancelling);
    final MockVlc.Player candidate = this.vlc.player(0);
    final String opened = candidate.getResource();
    assertEquals(CANCELLED_DURING_SETUP_RESOURCE, opened, "the native mock did open successfully after the callback");
    assertFalse(started, "release cancellation must win even when native opening subsequently succeeds");

    this.assertEverythingReleased();
    final boolean revived = this.player.start(this.video);
    assertFalse(revived);
  }

  private String releaseOnFirstSetup(final AtomicBoolean cancelled) {
    if (cancelled.compareAndSet(false, true)) {
      final boolean released = this.player.release();
      assertTrue(released);
    }

    return CANCELLED_DURING_SETUP_RESOURCE;
  }

  @Test
  void shouldLetASynchronousOpenFailureCallbackCancelItsOwnStart() throws Exception {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Boolean> restarted = new AtomicReference<>();
    this.player.setExceptionHandler((_, _) -> this.restartAndRelease(restarted, released));
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

  private void restartAndRelease(final AtomicReference<Boolean> restarted, final AtomicReference<Boolean> released) {
    restarted.set(this.player.start(this.audio));
    released.set(this.player.release());
  }

  @Test
  void shouldNotStartOrReleaseAReleasedPlayerAgain() {
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
  void shouldStopThePlaybackOnRelease() throws InterruptedException {
    this.startSeparate();
    final boolean released = this.player.release();
    assertTrue(released);

    this.assertEverythingReleased();
    this.assertControlsFail();
  }

  @Test
  void shouldControlFailWithoutAPlayback() {
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
  void shouldPauseAndResumeEveryPlayer() {
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
  void shouldResumeRightAfterPausingAlthoughVlcStillReportsPlaying() {
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
  void shouldFailToPauseWhenVlcIsNotPlaying() {
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
  void shouldDropFramesOfPausedPlayback() throws Exception {
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
    final int[] expected = new int[] { GREEN };
    assertArrayEquals(expected, rgb);
  }

  private void recordChunkSizes(final BlockingQueue<Integer> chunkSizes) {
    final AudioFilter recorder = (samples, _) -> this.recordChunkSize(samples, chunkSizes);
    final AudioPipelineStep recording = AudioPipelineStep.of(recorder);
    final AudioAttachableCallback audioCallback = this.player.getAudioAttachableCallback();
    audioCallback.attach(recording);
  }

  private boolean recordChunkSize(final ByteBuffer samples, final BlockingQueue<Integer> chunkSizes) {
    final int remaining = samples.remaining();
    chunkSizes.add(remaining);

    return false;
  }

  private static void reportTrackSize(final MockVlc.Player mocked) {
    final VideoApi videoApi = mocked.getVideo();
    final Dimension trackSize = new Dimension(2, 1);
    when(videoApi.videoDimension()).thenReturn(trackSize);
  }

  private static void playSilence(final MockVlc.Player mocked, final MediaPlayer mediaPlayer) {
    final AudioCallback vlcAudio = mocked.getAudioCallback();
    final Memory samples = new Memory(1_920L);
    samples.clear();
    vlcAudio.play(mediaPlayer, samples, 480, 0L);
  }

  @Test
  void shouldDeliverFramesAndSamplesToThePipelines() throws Exception {
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
    final Integer chunkSize = chunkSizes.poll(WAIT_SECONDS, TimeUnit.SECONDS);

    final int[] rgb = frame.getRgb();
    final OriginalVideoMetadata metadata = frame.getMetadata();
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final Integer expectedChunkSize = 1_920;
    final int[] expected = new int[] { RED, GREEN };
    assertArrayEquals(expected, rgb, "only the visible 2x1 part of the padded 3x2 buffer");
    assertEquals(2, metadataWidth);
    assertEquals(1, metadataHeight);
    assertEquals(expectedChunkSize, chunkSize, "480 stereo frames of 16-bit samples");
  }

  @Test
  void shouldWaitForRunningPipelinesOnRelease() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicBoolean finished = new AtomicBoolean();
    this.attachVideoFilter((_, _) -> this.enterAndFinishSlowly(entered, finished));
    this.startVideo();
    final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    final boolean running = entered.await(WAIT_SECONDS, TimeUnit.SECONDS);
    final boolean released = this.player.release();
    final boolean finishedBeforeRelease = finished.get();
    assertTrue(running);
    assertTrue(released);
    assertTrue(finishedBeforeRelease, "no pipeline runs after release returns");
  }

  private boolean enterAndFinishSlowly(final CountDownLatch entered, final AtomicBoolean finished) {
    entered.countDown();
    try {
      Thread.sleep(300);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    finished.set(true);

    return false;
  }

  @Test
  void shouldLeaveThePlayerLockAvailableToTheCallbackOnAnExternalRelease() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    final AtomicReference<Boolean> repeatedRelease = new AtomicReference<>();
    this.attachVideoFilter((_, _) -> this.releaseAgainAfterEscape(entered, escape, repeatedRelease, finished));
    final AtomicReference<Boolean> firstRelease = new AtomicReference<>();
    final Thread releaser = new Thread(() -> firstRelease.set(this.player.release()), "vlc-external-release");
    releaser.setDaemon(true);
    try {
      this.startVideo();
      final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
      this.renderFrame(mediaPlayer, 1, 1, RED);
      final boolean processing = entered.await(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(processing);

      releaser.start();
      releaser.join(UNBLOCKED_JOIN_MILLISECONDS);
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
      releaser.join(CLEANUP_JOIN_MILLISECONDS);
    }
  }

  private boolean releaseAgainAfterEscape(
    final CountDownLatch entered,
    final CountDownLatch escape,
    final AtomicReference<Boolean> repeatedRelease,
    final CountDownLatch finished
  ) {
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
  }

  @Test
  void shouldLetAPipelineReleaseThePlayer() throws Exception {
    final AtomicReference<Boolean> releasedFromPipeline = new AtomicReference<>();
    final CountDownLatch done = new CountDownLatch(1);
    this.attachVideoFilter((_, _) -> this.releaseFromPipeline(releasedFromPipeline, done));
    this.startVideo();
    final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
    this.renderFrame(mediaPlayer, 1, 1, RED);
    final boolean finished = done.await(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(finished, "releasing from the render thread must not deadlock");

    VlcPlaybackTest.awaitNoPlaybackThreads();
    final Boolean released = releasedFromPipeline.get();
    final int references = this.references();
    assertEquals(true, released);
    assertEquals(0, references);
  }

  private boolean releaseFromPipeline(final AtomicReference<Boolean> releasedFromPipeline, final CountDownLatch done) {
    final boolean released = this.player.release();
    releasedFromPipeline.set(released);
    done.countDown();

    return false;
  }

  @Test
  void shouldSeekEveryPlayer() {
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

  @Test
  void shouldStayPausedAfterSeeking() {
    this.startSeparate();
    final MockVlc.Player videoPlayer = this.vlc.player(0);
    final MockVlc.Player audioPlayer = this.vlc.player(1);
    final ControlsApi videoControls = videoPlayer.getControls();
    final ControlsApi audioControls = audioPlayer.getControls();
    final boolean paused = this.player.pause();
    final boolean seeked = this.player.seek(1_234L);
    assertTrue(paused);
    assertTrue(seeked);
    verify(videoControls).setTime(1_234L);
    verify(audioControls).setTime(1_234L);
    verify(videoControls, never()).setPause(false);
    verify(audioControls, never()).setPause(false);

    final boolean resumed = this.player.resume();
    assertTrue(resumed, "a seek keeps the player paused, so it can still be resumed");
  }

  @ParameterizedTest
  @EnumSource(SeekFailure.class)
  void shouldFailToSeekWhenAPlayerCannotSeek(final SeekFailure failure) {
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
  void shouldSeekWhileVlcIsStillOpeningOrBuffering() {
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
  void shouldRejectInvalidArguments() {
    final SharedMediaPlayerFactory shared = this.vlc.getSharedFactory();
    assertThrows(IllegalArgumentException.class, () -> this.player.seek(-1L));
    assertThrows(NullPointerException.class, () -> this.player.start(null));
    assertThrows(NullPointerException.class, () -> this.player.start(null, this.audio));
    assertThrows(NullPointerException.class, () -> this.player.start(this.video, null));
    assertThrows(NullPointerException.class, () -> this.player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> new VlcPlayer(null, 1L));
    assertThrows(IllegalArgumentException.class, () -> new VlcPlayer(shared, -1L));
    assertThrows(NullPointerException.class, () -> new VlcPlayer(shared, 1L, (String[]) null));
    assertThrows(NullPointerException.class, () -> new VlcPlayer(shared, 1L, "--ok", null));
    assertThrows(NullPointerException.class, () -> new VlcPlayer((String[]) null));
    assertThrows(NullPointerException.class, () -> new VlcPlayer("--ok", null));
  }

  @Test
  void shouldExposeItsSlotsAndExceptionHandler() {
    final VlcPlayer fresh = this.vlc.newPlayer(1L);
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
  void shouldCopyTheMediaOptions() {
    final String[] options = { "--first" };
    final VlcPlayer copying = this.vlc.newPlayer(OPEN_TIMEOUT_MILLISECONDS, options);
    options[0] = "--changed";
    final String[] created = copying.createMediaOptions(null);
    created[0] = "--changed-again";
    final String[] again = copying.createMediaOptions(null);
    final String[] extended = copying.createMediaOptions(":extra");
    final String[] expectedAgain = new String[] { "--first" };
    assertArrayEquals(expectedAgain, again);
    final String[] expectedExtended = new String[] { "--first", ":extra" };
    assertArrayEquals(expectedExtended, extended);

    copying.release();
  }

  @ParameterizedTest
  @ValueSource(strings = { "pause", "resume", "seek" })
  void shouldAllowReleaseFromAnotherThreadWithIdleControls(final String operation) throws Exception {
    final boolean controlled = switch (operation) {
      case "pause" -> this.player.pause();
      case "resume" -> this.player.resume();
      case "seek" -> this.player.seek(100L);
      default -> throw new AssertionError(operation);
    };
    assertFalse(controlled);

    final CompletableFuture<Boolean> release = CompletableFuture.supplyAsync(this.player::release);
    final boolean released = release.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(released, "an idle control call must relinquish the player lock");
  }

  @Test
  void shouldAllowANewStartFromAnotherThreadAfterAFailedReplacement() throws Exception {
    this.startVideo();
    final MockVlc.Player old = this.vlc.player(0);
    final ControlsApi controls = old.getControls();
    final IllegalStateException failure = new IllegalStateException(OLD_STOP_FAILURE_MESSAGE);
    doThrow(failure).when(controls).stop();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.player.start(this.audio));
    assertSame(failure, thrown);

    final CompletableFuture<Boolean> restart = CompletableFuture.supplyAsync(() -> this.player.start(this.video));
    final boolean restarted = restart.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(restarted, "failed replacement must clear its reservation and relinquish its lock");
  }

  @Test
  void shouldAllowReleaseFromAnotherThreadAfterAFailedAcquisition() throws Exception {
    final MediaPlayerFactory factory = this.vlc.getFactory();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError(PLAYER_API_MISSING_MESSAGE);
    when(factory.mediaPlayers()).thenThrow(failure);
    final boolean started = this.player.start(this.video);
    assertFalse(started);

    final CompletableFuture<Boolean> release = CompletableFuture.supplyAsync(this.player::release);
    final boolean released = release.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(released, "failed acquisition must relinquish its lock");
  }

  @Test
  void shouldPreserveAFailedStartOfEqualSources() {
    this.vlc.queueOutcomes(MockVlc.Outcome.REJECTED);
    final boolean started = this.player.start(this.video, this.video);
    assertFalse(started);
  }

  @Test
  void shouldAcceptAZeroOpenTimeout() {
    final SharedMediaPlayerFactory factory = mock(SharedMediaPlayerFactory.class);
    final VlcPlayer player = new VlcPlayer(factory, 0L);
    try {
      final long timeoutMilliseconds = player.getOpenTimeoutMilliseconds();
      assertEquals(0L, timeoutMilliseconds);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldUseTheSharedInstanceInThePublicConstructor() {
    final VlcPlayer shared = new VlcPlayer("--x");
    final SharedMediaPlayerFactory factory = shared.getSharedFactory();
    final SharedMediaPlayerFactory instance = SharedMediaPlayerFactory.getInstance();
    final long timeout = shared.getOpenTimeoutMilliseconds();
    final String[] options = shared.createMediaOptions(null);
    final boolean released = shared.release();
    assertSame(instance, factory);
    assertEquals(5_000L, timeout);
    final String[] expected = new String[] { "--x" };
    assertArrayEquals(expected, options);
    assertTrue(released);
  }

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

  enum SeekFailure {
    VIDEO_NOT_SEEKABLE,
    AUDIO_NOT_SEEKABLE,
    VIDEO_ENDED,
    AUDIO_STOPPED,
    VIDEO_FAILED,
    AUDIO_NOTHING_OPEN,
  }

  private enum LifecycleCall {
    START,
    RELEASE,
  }

  private enum CreationOutcome {
    SUCCEEDS,
    FAILS,
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.frame.FrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AsyncPlayerDefaultsTest {

  private static final long TIMEOUT_SECONDS = 5;

  private static final int ASYNC_CALL_COUNT = 2;

  private static final long SEEK_POSITION = 1234L;

  private static final String SYNCHRONOUS_FAILURE_MESSAGE = "synchronous failure!";

  private static void assertResult(final boolean expected, final CompletableFuture<Boolean> future) throws Exception {
    final Boolean actual = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals(expected, actual);
  }

  private static void assertFailure(final RuntimeException expected, final CompletableFuture<Boolean> future) {
    final ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    final Throwable cause = failure.getCause();
    assertSame(expected, cause);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultForVideos(final boolean expected) throws Exception {
    final VideoPlayer player = mock(VideoPlayer.class, CALLS_REAL_METHODS);
    final Source source = mock(Source.class);
    doReturn(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertResult(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertResult(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(source);
  }

  @Test
  void shouldPreserveTheOriginalFailureForVideos() {
    final VideoPlayer player = mock(VideoPlayer.class, CALLS_REAL_METHODS);
    final Source source = mock(Source.class);
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertFailure(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertFailure(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(source);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultForMultiplexers(final boolean expected) throws Exception {
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class, CALLS_REAL_METHODS);
    final Source video = mock(Source.class);
    final Source audio = mock(Source.class);
    doReturn(expected).when(player).start(video, audio);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(video, audio, executor);
      assertResult(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(video, audio);
      assertResult(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(video, audio);
  }

  @Test
  void shouldPreserveTheOriginalFailureForMultiplexers() {
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class, CALLS_REAL_METHODS);
    final Source video = mock(Source.class);
    final Source audio = mock(Source.class);
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).start(video, audio);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(video, audio, executor);
      assertFailure(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(video, audio);
      assertFailure(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(video, audio);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultOnSeek(final boolean expected) throws Exception {
    final SeekablePlayer player = mock(SeekablePlayer.class, CALLS_REAL_METHODS);
    final long position = SEEK_POSITION;
    doReturn(expected).when(player).seek(position);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.seekAsync(executor, position);
      assertResult(expected, explicit);

      final CompletableFuture<Boolean> common = player.seekAsync(position);
      assertResult(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).seek(position);
  }

  @Test
  void shouldPreserveTheOriginalFailureOnSeek() {
    final SeekablePlayer player = mock(SeekablePlayer.class, CALLS_REAL_METHODS);
    final long position = SEEK_POSITION;
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).seek(position);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.seekAsync(executor, position);
      assertFailure(expected, explicit);

      final CompletableFuture<Boolean> common = player.seekAsync(position);
      assertFailure(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).seek(position);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultForImages(final boolean expected) throws Exception {
    final ImagePlayer player = mock(ImagePlayer.class, CALLS_REAL_METHODS);
    final FrameSource source = mock(FrameSource.class);
    doReturn(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertResult(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertResult(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(source);
  }

  @Test
  void shouldPreserveTheOriginalFailureForImages() {
    final ImagePlayer player = mock(ImagePlayer.class, CALLS_REAL_METHODS);
    final FrameSource source = mock(FrameSource.class);
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertFailure(expected, explicit);

      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertFailure(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).start(source);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultForPauseAndResume(final boolean expected) throws Exception {
    final ControllablePlayer player = mock(ControllablePlayer.class, CALLS_REAL_METHODS);
    doReturn(expected).when(player).pause();
    doReturn(expected).when(player).resume();
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicitPause = player.pauseAsync(executor);
      assertResult(expected, explicitPause);

      final CompletableFuture<Boolean> commonPause = player.pauseAsync();
      assertResult(expected, commonPause);

      final CompletableFuture<Boolean> explicitResume = player.resumeAsync(executor);
      assertResult(expected, explicitResume);

      final CompletableFuture<Boolean> commonResume = player.resumeAsync();
      assertResult(expected, commonResume);
    }

    verify(player, times(ASYNC_CALL_COUNT)).pause();
    verify(player, times(ASYNC_CALL_COUNT)).resume();
  }

  @Test
  void shouldPreserveTheOriginalFailureForPauseAndResume() {
    final ControllablePlayer player = mock(ControllablePlayer.class, CALLS_REAL_METHODS);
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).pause();
    doThrow(expected).when(player).resume();
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicitPause = player.pauseAsync(executor);
      assertFailure(expected, explicitPause);

      final CompletableFuture<Boolean> commonPause = player.pauseAsync();
      assertFailure(expected, commonPause);

      final CompletableFuture<Boolean> explicitResume = player.resumeAsync(executor);
      assertFailure(expected, explicitResume);

      final CompletableFuture<Boolean> commonResume = player.resumeAsync();
      assertFailure(expected, commonResume);
    }

    verify(player, times(ASYNC_CALL_COUNT)).pause();
    verify(player, times(ASYNC_CALL_COUNT)).resume();
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldForwardEitherResultForRelease(final boolean expected) throws Exception {
    final ReleasablePlayer player = mock(ReleasablePlayer.class, CALLS_REAL_METHODS);
    doReturn(expected).when(player).release();
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.releaseAsync(executor);
      assertResult(expected, explicit);

      final CompletableFuture<Boolean> common = player.releaseAsync();
      assertResult(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).release();
  }

  @Test
  void shouldPreserveTheOriginalFailureForRelease() {
    final ReleasablePlayer player = mock(ReleasablePlayer.class, CALLS_REAL_METHODS);
    final RuntimeException expected = new IllegalStateException(SYNCHRONOUS_FAILURE_MESSAGE);
    doThrow(expected).when(player).release();
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.releaseAsync(executor);
      assertFailure(expected, explicit);

      final CompletableFuture<Boolean> common = player.releaseAsync();
      assertFailure(expected, common);
    }

    verify(player, times(ASYNC_CALL_COUNT)).release();
  }

  @Test
  void shouldThrowWhenTheExecutorRejectsTheCall() {
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class, CALLS_REAL_METHODS);
    final ImagePlayer imagePlayer = mock(ImagePlayer.class, CALLS_REAL_METHODS);
    final Source video = mock(Source.class);
    final Source audio = mock(Source.class);
    final FrameSource frames = mock(FrameSource.class);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      executor.shutdown();

      assertThrows(RejectedExecutionException.class, () -> player.startAsync(video, executor));
      assertThrows(RejectedExecutionException.class, () -> player.startAsync(video, audio, executor));
      assertThrows(RejectedExecutionException.class, () -> player.pauseAsync(executor));
      assertThrows(RejectedExecutionException.class, () -> player.resumeAsync(executor));
      assertThrows(RejectedExecutionException.class, () -> player.seekAsync(executor, SEEK_POSITION));
      assertThrows(RejectedExecutionException.class, () -> player.releaseAsync(executor));
      assertThrows(RejectedExecutionException.class, () -> imagePlayer.startAsync(frames, executor));
    }
  }
}

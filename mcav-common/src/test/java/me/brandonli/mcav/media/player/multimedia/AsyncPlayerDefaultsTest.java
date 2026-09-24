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
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.frame.FrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that asynchronous entry points preserve synchronous outcomes and arguments. */
final class AsyncPlayerDefaultsTest {

  private static void assertResult(final boolean expected, final CompletableFuture<Boolean> future) throws Exception {
    final Boolean actual = future.get(5, TimeUnit.SECONDS);
    assertEquals(Boolean.valueOf(expected), actual);
  }

  private static void assertFailure(final RuntimeException expected, final CompletableFuture<Boolean> future) {
    final ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    final Throwable cause = failure.getCause();
    assertSame(expected, cause);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void videoForwardsEitherResult(final boolean expected) throws Exception {
    final VideoPlayer player = mock(VideoPlayer.class, CALLS_REAL_METHODS);
    final Source source = mock(Source.class);
    doReturn(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertResult(expected, explicit);
      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertResult(expected, common);
    }
    verify(player, times(2)).start(source);
  }

  @Test
  void videoPreservesTheOriginalFailure() {
    final VideoPlayer player = mock(VideoPlayer.class, CALLS_REAL_METHODS);
    final Source source = mock(Source.class);
    final RuntimeException expected = new IllegalStateException("synchronous failure");
    doThrow(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertFailure(expected, explicit);
      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertFailure(expected, common);
    }
    verify(player, times(2)).start(source);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void multiplexerForwardsEitherResult(final boolean expected) throws Exception {
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
    verify(player, times(2)).start(video, audio);
  }

  @Test
  void multiplexerPreservesTheOriginalFailure() {
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class, CALLS_REAL_METHODS);
    final Source video = mock(Source.class);
    final Source audio = mock(Source.class);
    final RuntimeException expected = new IllegalStateException("synchronous failure");
    doThrow(expected).when(player).start(video, audio);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(video, audio, executor);
      assertFailure(expected, explicit);
      final CompletableFuture<Boolean> common = player.startAsync(video, audio);
      assertFailure(expected, common);
    }
    verify(player, times(2)).start(video, audio);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void seekForwardsEitherResult(final boolean expected) throws Exception {
    final SeekablePlayer player = mock(SeekablePlayer.class, CALLS_REAL_METHODS);
    final long position = 1234L;
    doReturn(expected).when(player).seek(position);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.seekAsync(executor, position);
      assertResult(expected, explicit);
      final CompletableFuture<Boolean> common = player.seekAsync(position);
      assertResult(expected, common);
    }
    verify(player, times(2)).seek(position);
  }

  @Test
  void seekPreservesTheOriginalFailure() {
    final SeekablePlayer player = mock(SeekablePlayer.class, CALLS_REAL_METHODS);
    final long position = 1234L;
    final RuntimeException expected = new IllegalStateException("synchronous failure");
    doThrow(expected).when(player).seek(position);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.seekAsync(executor, position);
      assertFailure(expected, explicit);
      final CompletableFuture<Boolean> common = player.seekAsync(position);
      assertFailure(expected, common);
    }
    verify(player, times(2)).seek(position);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void imageForwardsEitherResult(final boolean expected) throws Exception {
    final ImagePlayer player = mock(ImagePlayer.class, CALLS_REAL_METHODS);
    final FrameSource source = mock(FrameSource.class);
    doReturn(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertResult(expected, explicit);
      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertResult(expected, common);
    }
    verify(player, times(2)).start(source);
  }

  @Test
  void imagePreservesTheOriginalFailure() {
    final ImagePlayer player = mock(ImagePlayer.class, CALLS_REAL_METHODS);
    final FrameSource source = mock(FrameSource.class);
    final RuntimeException expected = new IllegalStateException("synchronous failure");
    doThrow(expected).when(player).start(source);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> explicit = player.startAsync(source, executor);
      assertFailure(expected, explicit);
      final CompletableFuture<Boolean> common = player.startAsync(source);
      assertFailure(expected, common);
    }
    verify(player, times(2)).start(source);
  }
}

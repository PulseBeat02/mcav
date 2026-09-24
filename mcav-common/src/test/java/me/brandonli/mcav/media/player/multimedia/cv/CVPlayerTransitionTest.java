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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/** Exercises callback reentry and cancellation at the session boundary; real decoding has separate integration tests. */
final class CVPlayerTransitionTest {

  private static Source source() {
    final Path path = Path.of("transition.mp4");
    return FileSource.path(path);
  }

  @Test
  void replacementLetsThePreviousRendererCallBackWithoutWaitingForThePlayerLock() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final List<PlaybackSession> created = sessions.constructed();
      final PlaybackSession previous = created.getFirst();
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::pause);
        final boolean paused = callback.get(2, TimeUnit.SECONDS);
        assertFalse(paused, "the previous playback was detached before its renderer was joined");
        final boolean competing = player.start(source);
        assertFalse(competing, "a replacement retains ownership while previous workers shut down");
        return null;
      })
        .when(previous)
        .stop();
      assertTrue(player.start(source));
      assertEquals(2, created.size());
      final PlaybackSession replacement = created.getLast();
      verify(replacement).startThreads();
      player.release();
    }
  }

  @Test
  void seekAlsoJoinsOutsideThePlayerLock() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::resume);
        assertFalse(callback.get(2, TimeUnit.SECONDS));
        return null;
      })
        .when(previous)
        .stop();
      assertTrue(player.seek(100));
      player.release();
    }
  }

  @Test
  void staleSeekCannotReplaceANewerPlayback() {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      when(previous.isPaused()).thenAnswer(_ -> {
        assertTrue(player.start(source));
        return false;
      });
      assertFalse(player.seek(100), "a seek whose observed session was replaced must not replace the new one");
      assertEquals(2, sessions.constructed().size());
      player.release();
    }
  }

  @Test
  void releaseDuringPreviousShutdownCancelsAndClosesTheReplacement() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::release);
        assertTrue(callback.get(2, TimeUnit.SECONDS));
        return null;
      })
        .when(previous)
        .stop();
      assertFalse(player.start(source));
      final PlaybackSession replacement = sessions.constructed().getLast();
      verify(replacement, never()).startThreads();
      verify(replacement).stop();
      assertFalse(player.start(source));
      assertFalse(player.isPlaying());
    }
  }

  @Test
  void releaseReenteredFromOpeningCannotPublishPlaybackAfterRelease() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (
      final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class, (session, _) -> {
        doAnswer(_ -> {
          assertFalse(player.start(source));
          assertTrue(player.release());
          return null;
        })
          .when(session)
          .open();
      })
    ) {
      assertFalse(player.start(source));
      final PlaybackSession cancelled = sessions.constructed().getFirst();
      verify(cancelled, never()).startThreads();
      verify(cancelled).stop();
    }
  }

  private static final class TestPlayer extends AbstractVideoPlayerCV {

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      throw new AssertionError("Sessions are replaced at the boundary in this fixture");
    }
  }
}

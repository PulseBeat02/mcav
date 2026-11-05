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
package me.brandonli.mcav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class ExampleResourcesTest {

  private static final String API_RESOURCE = "api";
  private static final String PLAYER_RESOURCE = "player";
  private static final String SPEAKERS_RESOURCE = "speakers";

  private static final long TIMEOUT_SECONDS = 5;

  private static final String WINDOW_TITLE = "test";

  private static final String FATAL_CREATION_RETURNED_MESSAGE = "fatal creation unexpectedly returned!";

  @Test
  void shouldReleaseInReverseAcquisitionOrderExactlyOnce() {
    final List<String> closed = new ArrayList<>();
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add(API_RESOURCE));
    resources.add(() -> closed.add("window"));
    resources.add(() -> closed.add(SPEAKERS_RESOURCE));
    resources.add(() -> closed.add(PLAYER_RESOURCE));
    resources.close();
    resources.close();
    final List<String> expected = List.of(PLAYER_RESOURCE, SPEAKERS_RESOURCE, "window", API_RESOURCE);
    assertEquals(expected, closed);
  }

  @Test
  void shouldCloseOtherResourcesDespiteOneCleanupFailure() {
    final List<String> closed = new ArrayList<>();
    final IllegalStateException first = new IllegalStateException("player!");
    final AssertionError second = new AssertionError("speakers!");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add(API_RESOURCE));
    resources.add(() -> this.recordAndFail(closed, SPEAKERS_RESOURCE, second));
    resources.add(() -> this.recordAndFail(closed, PLAYER_RESOURCE, first));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, resources::close);
    assertSame(first, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expectedFailures = { second };
    assertArrayEquals(expectedFailures, suppressed);

    final List<String> expected = List.of(PLAYER_RESOURCE, SPEAKERS_RESOURCE, API_RESOURCE);
    assertEquals(expected, closed);

    resources.close();
  }

  private void recordAndFail(final List<String> closed, final String resource, final Error failure) {
    closed.add(resource);
    throw failure;
  }

  private void recordAndFail(final List<String> closed, final String resource, final RuntimeException failure) {
    closed.add(resource);
    throw failure;
  }

  @Test
  void shouldRetainTheIdentityOfCleanupErrorsAndNotSkipTheApi() {
    final List<String> closed = new ArrayList<>();
    final AssertionError failure = new AssertionError("native cleanup!");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add(API_RESOURCE));
    resources.add(() -> this.throwFailure(failure));
    resources.add(() -> this.throwFailure(failure));
    final AssertionError thrown = assertThrows(AssertionError.class, resources::close);
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length, "repeated failure instances cannot be suppressed onto themselves");

    final List<String> expected = List.of(API_RESOURCE);
    assertEquals(expected, closed);
  }

  private void throwFailure(final Error failure) {
    throw failure;
  }

  @Test
  void shouldReleaseLateAcquisitionsInsteadOfEscapingAClosedOwner() {
    final List<String> closed = new ArrayList<>();
    final ExampleResources resources = new ExampleResources();
    resources.close();
    final Runnable release = () -> closed.add("late");
    assertThrows(IllegalStateException.class, () -> resources.add(release));

    final List<String> expected = List.of("late");
    assertEquals(expected, closed);
  }

  @Test
  void shouldWaitForTheOwnerOnAConcurrentCloseAndNotSelfWaitOnAReentrantClose() throws Exception {
    final ExampleResources resources = new ExampleResources();
    final CountDownLatch entered = new CountDownLatch(1);
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    resources.add(() -> this.closeAgainAndAwaitResume(resources, entered, resume));
    final CompletableFuture<Void> owner = CompletableFuture.runAsync(resources::close);
    try {
      final boolean started = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started, "the owner must reenter close without joining itself");

      final CountDownLatch otherEntered = new CountDownLatch(1);
      final CompletableFuture<Void> other = CompletableFuture.runAsync(() -> this.signalAndClose(otherEntered, resources));
      final boolean otherStarted = otherEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(otherStarted, "the competing closer must run before testing that it waits");
      assertThrows(TimeoutException.class, () -> other.get(100, TimeUnit.MILLISECONDS));

      resume.complete(null);
      owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      resume.complete(null);
      owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      resources.close();
    }
  }

  private void closeAgainAndAwaitResume(
    final ExampleResources resources,
    final CountDownLatch entered,
    final CompletableFuture<Void> resume
  ) {
    resources.close();
    entered.countDown();
    resume.join();
  }

  private void signalAndClose(final CountDownLatch otherEntered, final ExampleResources resources) {
    otherEntered.countDown();
    resources.close();
  }

  @Test
  void shouldStopImmediatelyOnAFatalCleanupWithoutReclassifyingIt() {
    final List<String> closed = new ArrayList<>();
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic cleanup failure!");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add(API_RESOURCE));
    resources.add(() -> this.throwFailure(fatal));
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, resources::close);
    assertSame(fatal, thrown);

    final List<String> expected = List.of();
    assertEquals(expected, closed, "fatal VM errors stop further cleanup attempts");
  }

  @Test
  void shouldNotSuppressAFatalWindowCleanupOntoAnOrdinaryConstructionFailure() {
    final IllegalStateException primary = new IllegalStateException("window setup!");
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic dispose failure!");
    try (
      final MockedConstruction<JFrame> frames = mockConstruction(JFrame.class, (frame, _) ->
        this.failTheSetupAndTheDisposal(frame, primary, fatal)
      )
    ) {
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> new SwingVideoWindow(WINDOW_TITLE, 1, 1));
      assertSame(fatal, thrown);

      final List<JFrame> constructed = frames.constructed();
      final int constructedCount = constructed.size();
      assertEquals(1, constructedCount);
    }
  }

  private void failTheSetupAndTheDisposal(final JFrame frame, final IllegalStateException primary, final OutOfMemoryError fatal) {
    doThrow(primary).when(frame).setDefaultCloseOperation(anyInt());
    doThrow(fatal).when(frame).dispose();
  }

  @Test
  void shouldLetAFatalWindowCreationReachTheWaitingCallerWithoutStrandingIt() throws Exception {
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic window failure!");
    // the first static and construction mocks of a class instrument it, which takes seconds on a busy machine: done
    // here, outside the waits below, which then measure only the hand-over of the failure
    try (
      final MockedStatic<SwingUtilities> warmSwing = mockStatic(SwingUtilities.class);
      final MockedConstruction<JFrame> warmFrames = mockConstruction(JFrame.class)
    ) {
      final boolean noFramesConstructed = warmFrames.constructed().isEmpty();
      assertTrue(noFramesConstructed);
      warmSwing.verifyNoInteractions();
    }

    final CompletableFuture<Throwable> callerFailure = new CompletableFuture<>();
    final CompletableFuture<Throwable> dispatchFailure = new CompletableFuture<>();
    final Thread caller = new Thread(
      () -> this.openTheWindowRecordingTheFailure(fatal, callerFailure, dispatchFailure),
      "example-fatal-window-caller"
    );
    caller.setDaemon(true);
    caller.start();
    try {
      final Throwable received = callerFailure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final Throwable dispatched = dispatchFailure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertSame(fatal, received, "the main-thread caller must receive the fatal error, not a CompletionException wrapper");
      assertSame(fatal, dispatched, "the dispatch task must publish before propagating its fatal error");
    } finally {
      caller.join(5000);
    }

    final boolean finished = !caller.isAlive();
    assertTrue(finished, "fatal creation must not strand the waiting caller");
  }

  private void openTheWindowRecordingTheFailure(
    final OutOfMemoryError fatal,
    final CompletableFuture<Throwable> callerFailure,
    final CompletableFuture<Throwable> dispatchFailure
  ) {
    try (final MockedStatic<SwingUtilities> swing = mockStatic(SwingUtilities.class)) {
      swing
        .when(() -> SwingUtilities.invokeLater(any(Runnable.class)))
        .thenAnswer(invocation -> this.dispatchOnANewThread(invocation, fatal, dispatchFailure));
      SwingVideoWindow.open(WINDOW_TITLE, 1, 1);
      callerFailure.complete(new AssertionError(FATAL_CREATION_RETURNED_MESSAGE));
    } catch (final Throwable exception) {
      callerFailure.complete(exception);
    }
  }

  private @Nullable Object dispatchOnANewThread(
    final InvocationOnMock invocation,
    final OutOfMemoryError fatal,
    final CompletableFuture<Throwable> dispatchFailure
  ) {
    final Runnable create = invocation.getArgument(0);
    final Thread dispatcher = new Thread(
      () -> this.createTheWindowRecordingTheFailure(create, fatal, dispatchFailure),
      "example-fatal-window-dispatch"
    );
    dispatcher.setDaemon(true);
    dispatcher.start();
    return null;
  }

  private void createTheWindowRecordingTheFailure(
    final Runnable create,
    final OutOfMemoryError fatal,
    final CompletableFuture<Throwable> dispatchFailure
  ) {
    try (
      final MockedConstruction<JFrame> frames = mockConstruction(JFrame.class, (frame, _) ->
        doThrow(fatal).when(frame).setDefaultCloseOperation(anyInt())
      )
    ) {
      try {
        create.run();
        dispatchFailure.complete(new AssertionError(FATAL_CREATION_RETURNED_MESSAGE));
      } finally {
        final List<JFrame> constructed = frames.constructed();
        final int constructedCount = constructed.size();
        assertEquals(1, constructedCount, "fatal dispatch must attempt exactly one frame construction");
      }
    } catch (final Throwable exception) {
      // Transport the synthetic fatal error to the test thread; do not lose it to an uncaught-handler log.
      dispatchFailure.complete(exception);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "ffmpeg", "vlc", "multiplexer" })
  void shouldMakeEveryVideoExampleOwnTheApiBeforeInstallationCanFail(final String example) {
    final MCAVApi api = mock(MCAVApi.class);
    final IllegalStateException failure = new IllegalStateException("install!");
    Mockito.doThrow(failure).when(api).install();
    try (final MockedStatic<MCAV> entry = mockStatic(MCAV.class)) {
      entry.when(MCAV::api).thenReturn(api);
      final Executable run = switch (example) {
        case "ffmpeg" -> FfmpegPlayerExample::main;
        case "vlc" -> VlcPlayerExample::main;
        case "multiplexer" -> MultiplexerInputExample::main;
        default -> throw new IllegalArgumentException(example);
      };
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, run);
      assertSame(failure, thrown);
      verify(api).release();
    }
  }

  @Test
  void shouldFailBeforeOpeningAWindowOrCreatingAPlayerWhenVlcIsUnavailable() {
    final MCAVApi api = mock(MCAVApi.class);
    final CompletableFuture<Boolean> unavailable = CompletableFuture.completedFuture(false);
    when(api.whenCapabilityReady(Capability.VLC)).thenReturn(unavailable);
    try (
      final MockedStatic<MCAV> entry = mockStatic(MCAV.class);
      final MockedStatic<VideoPlayer> players = mockStatic(VideoPlayer.class);
      final MockedStatic<SwingVideoWindow> windows = mockStatic(SwingVideoWindow.class)
    ) {
      entry.when(MCAV::api).thenReturn(api);
      assertThrows(IllegalStateException.class, VlcPlayerExample::main);
      verify(api).install();
      verify(api).whenCapabilityReady(Capability.VLC);
      verify(api).release();
      players.verifyNoInteractions();
      windows.verifyNoInteractions();
    }
  }
}

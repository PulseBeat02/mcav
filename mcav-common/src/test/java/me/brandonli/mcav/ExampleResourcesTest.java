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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

final class ExampleResourcesTest {

  @Test
  void releasesInReverseAcquisitionOrderExactlyOnce() {
    final List<String> closed = new ArrayList<>();
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add("api"));
    resources.add(() -> closed.add("window"));
    resources.add(() -> closed.add("speakers"));
    resources.add(() -> closed.add("player"));
    resources.close();
    resources.close();
    final List<String> expected = List.of("player", "speakers", "window", "api");
    assertEquals(expected, closed);
  }

  @Test
  void oneCleanupFailureDoesNotPreventOtherResourcesFromClosing() {
    final List<String> closed = new ArrayList<>();
    final IllegalStateException first = new IllegalStateException("player");
    final AssertionError second = new AssertionError("speakers");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add("api"));
    resources.add(() -> {
      closed.add("speakers");
      throw second;
    });
    resources.add(() -> {
      closed.add("player");
      throw first;
    });
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, resources::close);
    assertSame(first, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expectedFailures = { second };
    assertArrayEquals(expectedFailures, suppressed);
    final List<String> expected = List.of("player", "speakers", "api");
    assertEquals(expected, closed);
    resources.close();
  }

  @Test
  void cleanupErrorsRetainTheirIdentityAndDoNotSkipTheApi() {
    final List<String> closed = new ArrayList<>();
    final AssertionError failure = new AssertionError("native cleanup");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add("api"));
    resources.add(() -> {
      throw failure;
    });
    resources.add(() -> {
      throw failure;
    });
    final AssertionError thrown = assertThrows(AssertionError.class, resources::close);
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length, "repeated failure instances cannot be suppressed onto themselves");
    final List<String> expected = List.of("api");
    assertEquals(expected, closed);
  }

  @Test
  void lateAcquisitionsAreReleasedInsteadOfEscapingAClosedOwner() {
    final List<String> closed = new ArrayList<>();
    final ExampleResources resources = new ExampleResources();
    resources.close();
    final Runnable release = () -> closed.add("late");
    assertThrows(IllegalStateException.class, () -> resources.add(release));
    final List<String> expected = List.of("late");
    assertEquals(expected, closed);
  }

  @Test
  void concurrentCloseWaitsForTheOwnerAndReentrantCloseDoesNotSelfWait() throws Exception {
    final ExampleResources resources = new ExampleResources();
    final CountDownLatch entered = new CountDownLatch(1);
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    resources.add(() -> {
      resources.close();
      entered.countDown();
      resume.join();
    });
    final CompletableFuture<Void> owner = CompletableFuture.runAsync(resources::close);
    try {
      final boolean started = entered.await(5, TimeUnit.SECONDS);
      assertTrue(started, "the owner must reenter close without joining itself");
      final CountDownLatch otherEntered = new CountDownLatch(1);
      final CompletableFuture<Void> other = CompletableFuture.runAsync(() -> {
        otherEntered.countDown();
        resources.close();
      });
      final boolean otherStarted = otherEntered.await(5, TimeUnit.SECONDS);
      assertTrue(otherStarted, "the competing closer must run before testing that it waits");
      assertThrows(TimeoutException.class, () -> other.get(100, TimeUnit.MILLISECONDS));
      resume.complete(null);
      owner.get(5, TimeUnit.SECONDS);
      other.get(5, TimeUnit.SECONDS);
    } finally {
      resume.complete(null);
      owner.get(5, TimeUnit.SECONDS);
      resources.close();
    }
  }

  @Test
  void fatalCleanupStopsImmediatelyWithoutBeingReclassified() {
    final List<String> closed = new ArrayList<>();
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic cleanup failure");
    final ExampleResources resources = new ExampleResources();
    resources.add(() -> closed.add("api"));
    resources.add(() -> {
      throw fatal;
    });
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, resources::close);
    assertSame(fatal, thrown);
    final List<String> expected = List.of();
    assertEquals(expected, closed, "fatal VM errors stop further cleanup attempts");
  }

  @Test
  void fatalWindowCleanupIsNotSuppressedOntoAnOrdinaryConstructionFailure() {
    final IllegalStateException primary = new IllegalStateException("window setup");
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic dispose failure");
    try (
      final MockedConstruction<JFrame> frames = mockConstruction(JFrame.class, (frame, context) -> {
        doThrow(primary).when(frame).setDefaultCloseOperation(anyInt());
        doThrow(fatal).when(frame).dispose();
      })
    ) {
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> new SwingVideoWindow("test", 1, 1));
      assertSame(fatal, thrown);
      final List<JFrame> constructed = frames.constructed();
      final int constructedCount = constructed.size();
      assertEquals(1, constructedCount);
    }
  }

  @Test
  void fatalWindowCreationReachesTheWaitingCallerWithoutStrandingIt() throws Exception {
    final OutOfMemoryError fatal = new OutOfMemoryError("synthetic window failure");
    final CompletableFuture<Throwable> callerFailure = new CompletableFuture<>();
    final CompletableFuture<Throwable> dispatchFailure = new CompletableFuture<>();
    final Thread caller = new Thread(
      () -> {
        try (final MockedStatic<SwingUtilities> swing = mockStatic(SwingUtilities.class)) {
          swing
            .when(() -> SwingUtilities.invokeLater(any(Runnable.class)))
            .thenAnswer(invocation -> {
              final Runnable create = invocation.getArgument(0);
              final Thread dispatcher = new Thread(
                () -> {
                  try (
                    final MockedConstruction<JFrame> frames = mockConstruction(JFrame.class, (frame, context) -> {
                      doThrow(fatal).when(frame).setDefaultCloseOperation(anyInt());
                    })
                  ) {
                    try {
                      create.run();
                      dispatchFailure.complete(new AssertionError("fatal creation unexpectedly returned"));
                    } finally {
                      final List<JFrame> constructed = frames.constructed();
                      final int constructedCount = constructed.size();
                      assertEquals(1, constructedCount, "fatal dispatch must attempt exactly one frame construction");
                    }
                  } catch (final Throwable failure) {
                    // Transport the synthetic fatal error to the test thread; do not lose it to an uncaught-handler log.
                    dispatchFailure.complete(failure);
                  }
                },
                "example-fatal-window-dispatch"
              );
              dispatcher.setDaemon(true);
              dispatcher.start();
              return null;
            });
          SwingVideoWindow.open("test", 1, 1);
          callerFailure.complete(new AssertionError("fatal creation unexpectedly returned"));
        } catch (final Throwable failure) {
          callerFailure.complete(failure);
        }
      },
      "example-fatal-window-caller"
    );
    caller.setDaemon(true);
    caller.start();
    try {
      final Throwable received = callerFailure.get(5, TimeUnit.SECONDS);
      final Throwable dispatched = dispatchFailure.get(5, TimeUnit.SECONDS);
      assertSame(fatal, received, "the main-thread caller must receive the fatal error, not a CompletionException wrapper");
      assertSame(fatal, dispatched, "the dispatch task must publish before propagating its fatal error");
    } finally {
      caller.join(5000);
    }
    final boolean finished = !caller.isAlive();
    assertTrue(finished, "fatal creation must not strand the waiting caller");
  }

  @ParameterizedTest
  @ValueSource(strings = { "ffmpeg", "vlc", "multiplexer" })
  void everyVideoExampleOwnsTheApiBeforeInstallationCanFail(final String example) {
    final MCAVApi api = mock(MCAVApi.class);
    final IllegalStateException failure = new IllegalStateException("install");
    org.mockito.Mockito.doThrow(failure).when(api).install();
    try (final MockedStatic<MCAV> entry = mockStatic(MCAV.class)) {
      entry.when(MCAV::api).thenReturn(api);
      final Executable run =
        switch (example) {
          case "ffmpeg" -> FFmpegPlayerExample::main;
          case "vlc" -> VLCPlayerExample::main;
          case "multiplexer" -> MultiplexerInputExample::main;
          default -> throw new IllegalArgumentException(example);
        };
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, run);
      assertSame(failure, thrown);
      verify(api).release();
    }
  }

  @Test
  void vlcUnavailableFailsBeforeOpeningAWindowOrCreatingAPlayer() {
    final MCAVApi api = mock(MCAVApi.class);
    final CompletableFuture<Boolean> unavailable = CompletableFuture.completedFuture(false);
    when(api.whenCapabilityReady(Capability.VLC)).thenReturn(unavailable);
    try (
      final MockedStatic<MCAV> entry = mockStatic(MCAV.class);
      final MockedStatic<VideoPlayer> players = mockStatic(VideoPlayer.class);
      final MockedStatic<SwingVideoWindow> windows = mockStatic(SwingVideoWindow.class)
    ) {
      entry.when(MCAV::api).thenReturn(api);
      assertThrows(IllegalStateException.class, VLCPlayerExample::main);
      verify(api).install();
      verify(api).whenCapabilityReady(Capability.VLC);
      verify(api).release();
      players.verifyNoInteractions();
      windows.verifyNoInteractions();
    }
  }
}

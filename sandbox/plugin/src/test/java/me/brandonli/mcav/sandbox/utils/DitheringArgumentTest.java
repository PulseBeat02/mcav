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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests {@link DitheringArgument}.
 *
 * <p>Stateless algorithms are cached for the lifetime of the JVM, so the test of concurrent creation runs first and
 * uses an argument no other test touches.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class DitheringArgumentTest {

  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private static void awaitBlocked(final Thread thread) {
    final long start = System.nanoTime();
    final long deadline = start + TIMEOUT_NANOS;
    Thread.State state = thread.getState();
    while (state != Thread.State.BLOCKED) {
      final long now = System.nanoTime();
      assertTrue(now < deadline, "the thread never waited for the lock");
      Thread.onSpinWait();
      state = thread.getState();
    }
  }

  private static Thread startCreating(final DitheringArgument argument, final AtomicReference<DitherAlgorithm> result) {
    final Thread thread = new Thread(() -> {
      final DitherAlgorithm algorithm = argument.createAlgorithm();
      result.set(algorithm);
    });
    thread.start();
    return thread;
  }

  @Test
  @Order(0)
  void createsTheSharedAlgorithmOfAnArgumentOnItsFirstUse() {
    // this test runs before every other one, so the algorithm of the argument is not cached yet
    final DitheringArgument argument = DitheringArgument.CLUSTERED_DOT_4X4_LIGHT;
    final DitherAlgorithm first = argument.createAlgorithm();
    final DitherAlgorithm second = argument.createAlgorithm();
    assertNotNull(first, "the first use of an argument creates its algorithm");
    assertSame(first, second, "every later use returns the algorithm that was created first");
  }

  @Test
  @Order(1)
  void threadsThatRaceForTheFirstUseShareOneStatelessAlgorithm() throws InterruptedException {
    final DitheringArgument argument = DitheringArgument.HORIZONTAL_3X5_HEAVY;
    final AtomicReference<DitherAlgorithm> first = new AtomicReference<>();
    final AtomicReference<DitherAlgorithm> second = new AtomicReference<>();
    final Thread firstThread;
    final Thread secondThread;

    // both threads find no algorithm and wait for the lock held here, so one of them finds the algorithm the other
    // created once it gets the lock
    synchronized (argument) {
      firstThread = startCreating(argument, first);
      secondThread = startCreating(argument, second);
      awaitBlocked(firstThread);
      awaitBlocked(secondThread);
    }
    firstThread.join(TIMEOUT_MILLIS);
    secondThread.join(TIMEOUT_MILLIS);

    final DitherAlgorithm firstAlgorithm = first.get();
    final DitherAlgorithm secondAlgorithm = second.get();
    assertNotNull(firstAlgorithm);
    assertSame(firstAlgorithm, secondAlgorithm);
    final DitherAlgorithm later = argument.createAlgorithm();
    assertSame(firstAlgorithm, later);
  }

  @ParameterizedTest
  @Order(2)
  @EnumSource(DitheringArgument.class)
  void createsAnAlgorithmOfTheNamedFamily(final DitheringArgument argument) {
    final DitherAlgorithm algorithm = argument.createAlgorithm();
    final String name = argument.name();
    final Class<?> expected;
    if (name.startsWith("RANDOM_")) {
      expected = RandomDither.class;
    } else if (name.equals("NEAREST_COLOR")) {
      expected = NearestDither.class;
    } else if (
      name.startsWith("BAYER_") || name.startsWith("CLUSTERED_") || name.startsWith("VERTICAL_") || name.startsWith("HORIZONTAL_")
    ) {
      expected = BayerDither.class;
    } else {
      expected = ErrorDiffusionDither.class;
    }
    assertInstanceOf(expected, algorithm);
  }

  @ParameterizedTest
  @Order(3)
  @EnumSource(value = DitheringArgument.class, mode = EnumSource.Mode.EXCLUDE, names = "FLOYD_STEINBERG_TEMPORAL")
  void sharesTheStatelessAlgorithm(final DitheringArgument argument) {
    final DitherAlgorithm first = argument.createAlgorithm();
    final DitherAlgorithm second = argument.createAlgorithm();
    assertSame(first, second);
  }

  @Test
  void cachedAlgorithmDoesNotWaitForTheInitializationLock() throws Exception {
    final DitheringArgument argument = DitheringArgument.NEAREST_COLOR;
    final DitherAlgorithm expected = argument.createAlgorithm();
    final java.util.concurrent.CompletableFuture<DitherAlgorithm> completed = new java.util.concurrent.CompletableFuture<>();
    final Thread reader = new Thread(() -> completed.complete(argument.createAlgorithm()), "cached-algorithm-reader");
    try {
      synchronized (argument) {
        reader.start();
        final DitherAlgorithm actual = completed.get(2, TimeUnit.SECONDS);
        assertSame(expected, actual, "a warm cache read never waits for first-use initialization");
      }
    } finally {
      reader.join(TIMEOUT_MILLIS);
    }
  }

  @Test
  @Order(4)
  void createsANewTemporalAlgorithmForEveryStream() {
    final DitheringArgument argument = DitheringArgument.FLOYD_STEINBERG_TEMPORAL;
    final DitherAlgorithm first = argument.createAlgorithm();
    final DitherAlgorithm second = argument.createAlgorithm();
    assertInstanceOf(TemporalDitherAlgorithm.class, first);
    assertInstanceOf(TemporalDitherAlgorithm.class, second);
    assertNotSame(first, second);
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link NativeHeapTrimmer} with trims the tests count, and once with the JDK's own command.
 */
final class NativeHeapTrimmerTest {

  private static final Duration SHORT = Duration.ofMillis(10);
  private static final long TIMEOUT_SECONDS = 10L;

  private static Optional<Thread> trimmingThread() {
    return Thread.getAllStackTraces()
      .keySet()
      .stream()
      .filter(thread -> thread.getName().equals(NativeHeapTrimmer.THREAD_NAME))
      .findFirst();
  }

  @Test
  void trimsAtEveryIntervalOnADaemonUntilClosed() throws InterruptedException {
    final CountDownLatch trims = new CountDownLatch(3);
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, trims::countDown);
    trimmer.start();
    final boolean trimmed = trims.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean trimming = trimmer.isTrimming();
    final Thread thread = trimmingThread().orElseThrow();
    final boolean daemon = thread.isDaemon();
    trimmer.close();

    assertTrue(trimmed);
    assertTrue(trimming);
    assertTrue(daemon, "the trims never keep the JVM alive");
    assertFalse(trimmer.isTrimming());
    assertFalse(thread.isAlive(), "closing waits for the thread to end");
  }

  @Test
  void aFailedTrimIsLoggedAndTrimmingGoesOn() throws InterruptedException {
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch trims = new CountDownLatch(1);
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> {
      if (attempts.incrementAndGet() == 1) {
        throw new IllegalStateException("the first trim fails on purpose");
      }
      trims.countDown();
    });
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final boolean trimmed;
    try {
      trimmer.start();
      trimmed = trims.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      trimmer.close();
    } finally {
      System.setErr(standardError);
    }
    final String log = captured.toString(StandardCharsets.UTF_8);

    assertTrue(trimmed, "the trim after the failed one ran");
    assertTrue(log.contains("Could not trim the native heap"), log);
    assertTrue(log.contains("the first trim fails on purpose"), log);
  }

  @Test
  void trimsOnlyOnLinuxInAJvmThatDoesNotTrimItselfAtAPositiveInterval() {
    final Runnable nothing = () -> {};
    final NativeHeapTrimmer trimming = NativeHeapTrimmer.create(OS.LINUX, 0L, 60L, nothing);
    trimming.start();
    final boolean trims = trimming.isTrimming();
    trimming.close();

    assertTrue(trims);
    assertNeverTrims(NativeHeapTrimmer.create(OS.WINDOWS, 0L, 60L, nothing), "off Linux");
    assertNeverTrims(NativeHeapTrimmer.create(OS.LINUX, 1L, 60L, nothing), "in a JVM that trims itself");
    assertNeverTrims(NativeHeapTrimmer.create(OS.LINUX, 0L, 0L, nothing), "turned off");
    assertNeverTrims(NativeHeapTrimmer.create(OS.LINUX, 0L, -1L, nothing), "turned off by a negative interval");
  }

  private static void assertNeverTrims(final NativeHeapTrimmer trimmer, final String reason) {
    trimmer.start();
    final boolean trimming = trimmer.isTrimming();
    trimmer.close();
    assertFalse(trimming, reason);
  }

  @Test
  void closingWithoutTrimmingIsHarmless() {
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> {});
    trimmer.close();
    assertFalse(trimmer.isTrimming());
  }

  @Test
  void anInterruptedCloseKeepsTheInterruptOfItsCaller() throws InterruptedException {
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(Duration.ofHours(1), () -> {});
    trimmer.start();
    final Thread thread = trimmingThread().orElseThrow();
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    trimmer.close();
    final boolean interrupted = Thread.interrupted();
    thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

    assertTrue(interrupted);
    assertFalse(thread.isAlive(), "the trimming thread ends on its interrupt");
  }

  @Test
  void followsTheIntervalOfTheSystemProperty() {
    final boolean linux = OSUtils.getOS() == OS.LINUX;
    final String previous = System.getProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
    try {
      System.setProperty(NativeHeapTrimmer.INTERVAL_PROPERTY, "0");
      assertNeverTrims(NativeHeapTrimmer.forThisJvm(), "the property turns trimming off");
      System.clearProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
      final NativeHeapTrimmer trimmer = NativeHeapTrimmer.forThisJvm();
      trimmer.start();
      final boolean trimming = trimmer.isTrimming();
      trimmer.close();
      assertEquals(linux, trimming, "the JVM of the tests does not trim itself");
    } finally {
      if (previous == null) {
        System.clearProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
      } else {
        System.setProperty(NativeHeapTrimmer.INTERVAL_PROPERTY, previous);
      }
    }
  }

  @Test
  void readsTheIntervalOfTheJvmsOwnTrims() {
    assertEquals(0L, NativeHeapTrimmer.readJvmIntervalMillis(), "the JVM of the tests does not trim itself");
  }

  @Test
  void trimsWithTheCommandOfTheJdk() {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    final String answer = NativeHeapTrimmer.trimWithJdk(server);
    final String ownAnswer = NativeHeapTrimmer.trimThisJvm();
    final boolean linux = OSUtils.getOS() == OS.LINUX;
    final String expected = linux ? "Trim native heap" : "Not available";
    assertTrue(answer.startsWith(expected), answer);
    assertTrue(ownAnswer.startsWith(expected), ownAnswer);
  }

  @Test
  void aCommandThatCannotRunFailsWithItsCause() throws Exception {
    final MBeanServer server = mock(MBeanServer.class);
    final InstanceNotFoundException missing = new InstanceNotFoundException("no diagnostic commands");
    when(server.invoke(any(ObjectName.class), any(), any(), any())).thenThrow(missing);

    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> NativeHeapTrimmer.trimWithJdk(server));
    assertInstanceOf(InstanceNotFoundException.class, failure.getCause());
  }
}

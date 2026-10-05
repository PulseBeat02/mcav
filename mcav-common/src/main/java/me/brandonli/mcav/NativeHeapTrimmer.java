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

import com.google.common.annotations.VisibleForTesting;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the memory that the C library holds free for later allocations back to the system once a minute, on Linux.
 *
 * <p>FFmpeg, OpenCV and the encoders allocate and free large buffers on many threads. glibc keeps what is freed in its
 * arenas for later allocations, and in a server that plays for days the arenas keep growing although little of them is
 * in use: after 31 hours of a soak test, the arenas of the server held 2.1 GB of free memory beside 0.3 GB in use, and
 * its resident memory grew by about 20 MB an hour. The JDK's {@code System.trim_native_heap} command returns the free
 * pages of every arena to the system. A JVM started with {@code -XX:TrimNativeHeapInterval} trims on its own and is left
 * to it.
 *
 * <p>The interval is the system property {@value #INTERVAL_PROPERTY}, in seconds, {@value #DEFAULT_INTERVAL_SECONDS} by
 * default; zero or less turns trimming off.
 */
final class NativeHeapTrimmer implements AutoCloseable {

  /**
   * The system property with the interval of the trims, in seconds.
   */
  static final String INTERVAL_PROPERTY = "mcav.nativeTrimSeconds";

  /**
   * The interval of the trims when the system property does not set one, in seconds.
   */
  static final long DEFAULT_INTERVAL_SECONDS = 60L;

  /**
   * The name of the thread that trims.
   */
  static final String THREAD_NAME = "mcav-native-trim";

  private static final Logger LOGGER = LoggerFactory.getLogger(NativeHeapTrimmer.class);
  private static final String TRIM_FAILED = "Could not trim the native heap";
  private static final String DIAGNOSTIC_COMMANDS = "com.sun.management:type=DiagnosticCommand";
  private static final String TRIM_OPERATION = "systemTrimNativeHeap";
  private static final String JVM_INTERVAL_OPTION = "TrimNativeHeapInterval";

  private final Duration interval;
  private final Runnable trim;

  private @Nullable Thread thread;

  /**
   * Creates a trimmer.
   *
   * @param interval the time between two trims; the trimmer trims never at zero or less
   * @param trim     trims the native heap once
   */
  @VisibleForTesting
  NativeHeapTrimmer(final Duration interval, final Runnable trim) {
    this.interval = interval;
    this.trim = trim;
  }

  /**
   * Creates the trimmer of this JVM, which trims with the JDK's command.
   *
   * @return the trimmer, which trims never off Linux, in a JVM that trims itself, or when the system property turns
   *         trimming off
   */
  static NativeHeapTrimmer forThisJvm() {
    final boolean linux = OSUtils.getOS() == OS.LINUX;
    final long jvmIntervalMillis = readJvmIntervalMillis();
    final long seconds = Long.getLong(INTERVAL_PROPERTY, DEFAULT_INTERVAL_SECONDS);
    return create(linux, jvmIntervalMillis, seconds, () -> trimWithJdk(ManagementFactory.getPlatformMBeanServer()));
  }

  /**
   * Creates a trimmer that trims at the interval, unless the system is no Linux or the JVM trims itself.
   *
   * @param linux             whether the system is Linux
   * @param jvmIntervalMillis the interval of the JVM's own trims, in milliseconds, zero if it trims never
   * @param seconds           the interval of the trims, in seconds; zero or less trims never
   * @param trim              trims the native heap once
   * @return the trimmer
   */
  @VisibleForTesting
  static NativeHeapTrimmer create(final boolean linux, final long jvmIntervalMillis, final long seconds, final Runnable trim) {
    final boolean trims = linux && jvmIntervalMillis <= 0;
    final Duration interval = trims ? Duration.ofSeconds(seconds) : Duration.ZERO;
    return new NativeHeapTrimmer(interval, trim);
  }

  /**
   * Starts trimming on a daemon thread of its own, unless the trimmer trims never.
   */
  void start() {
    if (!this.interval.isPositive()) {
      return;
    }
    this.thread = Thread.ofPlatform().name(THREAD_NAME).daemon().start(this::trimUntilInterrupted);
  }

  /**
   * Checks whether the trimmer trims.
   *
   * @return true between {@link #start()} and {@link #close()}, unless it trims never
   */
  boolean isTrimming() {
    return this.thread != null;
  }

  private void trimUntilInterrupted() {
    while (true) {
      try {
        Thread.sleep(this.interval);
      } catch (final InterruptedException exception) {
        return;
      }
      try {
        this.trim.run();
      } catch (final RuntimeException exception) {
        LOGGER.warn(TRIM_FAILED, exception);
      }
    }
  }

  /**
   * Stops trimming and waits for the thread to end.
   */
  @Override
  public void close() {
    final Thread running = this.thread;
    this.thread = null;
    if (running == null) {
      return;
    }
    running.interrupt();
    try {
      running.join();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  /**
   * Reads the interval of the JVM's own trims.
   *
   * @return the interval in milliseconds, zero if the JVM trims never
   */
  @VisibleForTesting
  static long readJvmIntervalMillis() {
    final HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    final VMOption option = hotspot.getVMOption(JVM_INTERVAL_OPTION);
    final String value = option.getValue();
    return Long.parseLong(value);
  }

  /**
   * Trims the native heap with the JDK's {@code System.trim_native_heap} command.
   *
   * @param server the server of the JDK's diagnostic commands
   * @return the answer of the command, which says how much memory went back to the system, or that the C library of the
   *         system cannot trim
   * @throws IllegalStateException if the command cannot be run
   */
  @VisibleForTesting
  static String trimWithJdk(final MBeanServer server) {
    try {
      final ObjectName commands = new ObjectName(DIAGNOSTIC_COMMANDS);
      final Object[] arguments = { new String[0] };
      final String[] signature = { String[].class.getName() };
      final Object answer = server.invoke(commands, TRIM_OPERATION, arguments, signature);
      return String.valueOf(answer);
    } catch (final JMException exception) {
      throw new IllegalStateException(TRIM_FAILED, exception);
    }
  }
}

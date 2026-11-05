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
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Optional;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Returns the free memory glibc keeps in its arenas to the system once a minute on Linux (a 31-hour soak held 2.1 GB
// free beside 0.3 GB in use). The trim threshold is fixed at glibc's default so the top of each thread's arena is
// returned too. JVMs with -XX:TrimNativeHeapInterval or without the trim command are left alone.
final class NativeHeapTrimmer {

  static final String INTERVAL_PROPERTY = "mcav.nativeTrimSeconds";

  static final long DEFAULT_INTERVAL_SECONDS = 60L;

  static final String THREAD_NAME = "mcav-native-trim";

  static final long NO_JVM_INTERVAL = -1L;

  static final int TRIM_THRESHOLD_BYTES = 128 * 1024;

  private static final Logger LOGGER = LoggerFactory.getLogger(NativeHeapTrimmer.class);

  // mallopt's parameter for the trim threshold, from glibc's malloc.h
  private static final int M_TRIM_THRESHOLD = -1;
  private static final int MALLOPT_SUCCESS = 1;

  private static final String GLIBC_SYMBOL = "gnu_get_libc_version";
  private static final String MALLOPT_SYMBOL = "mallopt";

  private static final String DIAGNOSTIC_COMMANDS = "com.sun.management:type=DiagnosticCommand";
  private static final String TRIM_OPERATION = "systemTrimNativeHeap";

  private static final String JVM_INTERVAL_OPTION = "TrimNativeHeapInterval";

  private final Duration interval;
  private final Runnable trim;
  private final Runnable fixThreshold;

  private @Nullable Thread thread;

  @VisibleForTesting
  NativeHeapTrimmer(final Duration interval, final Runnable trim, final Runnable fixThreshold) {
    this.interval = interval;
    this.trim = trim;
    this.fixThreshold = fixThreshold;
  }

  static NativeHeapTrimmer create() {
    final Os os = OsUtils.getOs();
    final long jvmIntervalMilliseconds = readJvmIntervalMilliseconds();
    final long seconds = Long.getLong(INTERVAL_PROPERTY, DEFAULT_INTERVAL_SECONDS);
    return of(os, jvmIntervalMilliseconds, seconds, NativeHeapTrimmer::trimThisJvm, NativeHeapTrimmer::fixGlibcTrimThreshold);
  }

  @VisibleForTesting
  static NativeHeapTrimmer of(
    final Os os,
    final long jvmIntervalMilliseconds,
    final long seconds,
    final Runnable trim,
    final Runnable fixThreshold
  ) {
    final boolean trims = os == Os.LINUX && jvmIntervalMilliseconds == 0;
    final Duration interval = trims ? Duration.ofSeconds(seconds) : Duration.ZERO;
    return new NativeHeapTrimmer(interval, trim, fixThreshold);
  }

  void start() {
    if (!this.interval.isPositive()) {
      return;
    }

    try {
      this.fixThreshold.run();
    } catch (final RuntimeException exception) {
      LOGGER.warn("Could not fix the trim threshold of the native heap.", exception);
    }

    this.thread = Thread.ofPlatform().name(THREAD_NAME).daemon().start(this::trimUntilInterrupted);
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
        LOGGER.warn("Could not trim the native heap.", exception);
      }
    }
  }

  void stop() {
    final Thread running = this.thread;
    this.thread = null;

    if (running == null) {
      return;
    }

    running.interrupt();
    try {
      running.join();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @VisibleForTesting
  static long readJvmIntervalMilliseconds() {
    try {
      final @Nullable HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      if (hotspot == null) {
        return NO_JVM_INTERVAL;
      }

      final VMOption option = hotspot.getVMOption(JVM_INTERVAL_OPTION);
      final String value = option.getValue();
      return Long.parseLong(value);
    } catch (final IllegalArgumentException | NoClassDefFoundError exception) {
      // a JVM other than HotSpot may lack the bean or the option, and a runtime linked without the jdk.management module
      // lacks the bean's class; either lacks the command that trims as well
      return NO_JVM_INTERVAL;
    }
  }

  @VisibleForTesting
  static String trimThisJvm() {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    return trimWithJdk(server);
  }

  @VisibleForTesting
  static boolean fixGlibcTrimThreshold() {
    final Linker linker = Linker.nativeLinker();
    final SymbolLookup lookup = linker.defaultLookup();
    return fixGlibcTrimThreshold(lookup);
  }

  @VisibleForTesting
  @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
  static boolean fixGlibcTrimThreshold(final SymbolLookup library) {
    final Optional<MemorySegment> mallopt = library.find(GLIBC_SYMBOL).flatMap(_ -> library.find(MALLOPT_SYMBOL));
    final @Nullable MemorySegment malloptAddress = mallopt.orElse(null);
    if (malloptAddress == null) {
      return false;
    }

    final FunctionDescriptor descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    final Linker linker = Linker.nativeLinker();
    final MethodHandle malloptHandle = linker.downcallHandle(malloptAddress, descriptor);
    return setTrimThreshold(malloptHandle);
  }

  @VisibleForTesting
  static boolean setTrimThreshold(final MethodHandle mallopt) {
    try {
      return (int) mallopt.invokeExact(M_TRIM_THRESHOLD, TRIM_THRESHOLD_BYTES) == MALLOPT_SUCCESS;
    } catch (final Throwable exception) {
      ThrowableUtils.throwIfFatal(exception);

      final String message = "Could not fix the trim threshold of the native heap!";
      throw new MCAVException(message, exception);
    }
  }

  @VisibleForTesting
  static String trimWithJdk(final MBeanServer server) {
    try {
      final ObjectName commands = new ObjectName(DIAGNOSTIC_COMMANDS);
      final Object[] arguments = { new String[0] };
      final String[] signature = { String[].class.getName() };
      final Object answer = server.invoke(commands, TRIM_OPERATION, arguments, signature);
      return String.valueOf(answer);
    } catch (final JMException exception) {
      final String message = "Could not trim the native heap!";
      throw new MCAVException(message, exception);
    }
  }

  boolean isTrimming() {
    return this.thread != null;
  }
}

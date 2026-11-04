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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;

final class NativeHeapTrimmerTest {

  private static final Duration SHORT = Duration.ofMillis(10);
  private static final long TIMEOUT_SECONDS = 10L;
  private static final long FRAME_BYTES = 1920L * 1080L * 3L;
  private static final int ARENA_THREADS = 8;
  private static final String RESIDENT_PREFIX = "VmRSS:";

  private static final long TRIM_INTERVAL_SECONDS = 60L;

  private static final String GLIBC_VERSION_SYMBOL = "gnu_get_libc_version";
  private static final String MALLOPT_SYMBOL = "mallopt";
  private static final String MALLOC_SYMBOL = "malloc";

  private static final String PROCESS_STATUS_PATH = "/proc/self/status";

  private static final String UNTRIMMED_TEST_JVM_MESSAGE = "the JVM of the tests does not trim itself";

  private static Optional<Thread> trimmingThread() {
    return Thread.getAllStackTraces()
      .keySet()
      .stream()
      .filter(thread -> thread.getName().equals(NativeHeapTrimmer.THREAD_NAME))
      .findFirst();
  }

  @Test
  void shouldTrimAtEveryIntervalOnADaemonUntilStopped() throws InterruptedException {
    final CountDownLatch trims = new CountDownLatch(3);
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, trims::countDown, () -> {});
    trimmer.start();
    final boolean trimmed = trims.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean trimming = trimmer.isTrimming();
    final Thread thread = trimmingThread().orElseThrow();
    final boolean daemon = thread.isDaemon();
    trimmer.stop();

    assertTrue(trimmed);
    assertTrue(trimming);
    assertTrue(daemon, "the trims never keep the JVM alive");

    final boolean trimmingAfterStop = trimmer.isTrimming();
    assertFalse(trimmingAfterStop);

    final boolean alive = thread.isAlive();
    assertFalse(alive, "closing waits for the thread to end");
  }

  @Test
  void shouldLogAFailedTrimAndGoOnTrimming() throws InterruptedException {
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch trims = new CountDownLatch(1);
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> this.failTheFirstTrim(attempts, trims), () -> {});
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final boolean trimmed;
    try {
      trimmer.start();
      trimmed = trims.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      trimmer.stop();
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);

    assertTrue(trimmed, "the trim after the failed one ran");

    final boolean reportsTheFailure = log.contains("Could not trim the native heap");
    assertTrue(reportsTheFailure, log);

    final boolean reportsTheCause = log.contains("the first trim fails on purpose");
    assertTrue(reportsTheCause, log);
  }

  private void failTheFirstTrim(final AtomicInteger attempts, final CountDownLatch trims) {
    if (attempts.incrementAndGet() == 1) {
      final String message = "the first trim fails on purpose!";
      throw new IllegalStateException(message);
    }

    trims.countDown();
  }

  @Test
  void shouldTrimOnlyOnLinuxInAJvmThatDoesNotTrimItselfAtAPositiveInterval() {
    final Runnable nothing = () -> {};
    final NativeHeapTrimmer trimming = NativeHeapTrimmer.of(Os.LINUX, 0L, TRIM_INTERVAL_SECONDS, nothing, nothing);
    trimming.start();
    final boolean trims = trimming.isTrimming();
    trimming.stop();

    assertTrue(trims);
    assertNeverTrims(NativeHeapTrimmer.of(Os.WINDOWS, 0L, TRIM_INTERVAL_SECONDS, nothing, nothing), "off Linux");
    assertNeverTrims(NativeHeapTrimmer.of(Os.LINUX, 1L, TRIM_INTERVAL_SECONDS, nothing, nothing), "in a JVM that trims itself");
    assertNeverTrims(
      NativeHeapTrimmer.of(Os.LINUX, NativeHeapTrimmer.NO_JVM_INTERVAL, TRIM_INTERVAL_SECONDS, nothing, nothing),
      "in a JVM other than HotSpot"
    );
    assertNeverTrims(NativeHeapTrimmer.of(Os.LINUX, 0L, 0L, nothing, nothing), "turned off");
    assertNeverTrims(NativeHeapTrimmer.of(Os.LINUX, 0L, -1L, nothing, nothing), "turned off by a negative interval");
  }

  @Test
  void shouldFixTheThresholdOnceWhenTrimmingStarts() {
    final Runnable nothing = () -> {};
    final AtomicInteger fixes = new AtomicInteger();
    final Runnable fix = fixes::incrementAndGet;
    final NativeHeapTrimmer trimming = NativeHeapTrimmer.of(Os.LINUX, 0L, TRIM_INTERVAL_SECONDS, nothing, fix);
    final int created = fixes.get();
    trimming.start();
    final int started = fixes.get();
    trimming.stop();
    for (final NativeHeapTrimmer never : List.of(
      NativeHeapTrimmer.of(Os.WINDOWS, 0L, TRIM_INTERVAL_SECONDS, nothing, fix),
      NativeHeapTrimmer.of(Os.LINUX, 1L, TRIM_INTERVAL_SECONDS, nothing, fix),
      NativeHeapTrimmer.of(Os.LINUX, NativeHeapTrimmer.NO_JVM_INTERVAL, TRIM_INTERVAL_SECONDS, nothing, fix),
      NativeHeapTrimmer.of(Os.LINUX, 0L, 0L, nothing, fix),
      NativeHeapTrimmer.of(Os.LINUX, 0L, -1L, nothing, fix)
    )) {
      never.start();
      never.stop();
    }

    assertEquals(0, created, "creating the trimmer leaves the threshold alone");
    assertEquals(1, started);

    final int fixCount = fixes.get();
    assertEquals(1, fixCount, "a trimmer that trims never leaves the threshold alone");
  }

  @Test
  void shouldLogAFailedThresholdAndStillStartTrimming() {
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> {}, this::failTheThreshold);
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final boolean trimming;
    try {
      trimmer.start();
      trimming = trimmer.isTrimming();
      trimmer.stop();
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);

    assertTrue(trimming);

    final boolean reportsTheFailure = log.contains("Could not fix the trim threshold of the native heap");
    assertTrue(reportsTheFailure, log);

    final boolean reportsTheCause = log.contains("the threshold fails on purpose");
    assertTrue(reportsTheCause, log);
  }

  private void failTheThreshold() {
    final String message = "the threshold fails on purpose!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldGiveGlibcTheThreshold() {
    final SymbolLookup library = Linker.nativeLinker().defaultLookup();
    final boolean glibc = OsUtils.getOs() == Os.LINUX && library.find(GLIBC_VERSION_SYMBOL).isPresent();
    final boolean fixed = NativeHeapTrimmer.fixGlibcTrimThreshold();
    assertEquals(glibc, fixed);
  }

  @Test
  void shouldKeepTheThresholdOfAnyCLibraryOtherThanGlibc() {
    final SymbolLookup musl = name -> name.equals(MALLOPT_SYMBOL) ? Optional.of(MemorySegment.NULL) : Optional.empty();
    final boolean fixedForMusl = NativeHeapTrimmer.fixGlibcTrimThreshold(musl);
    assertFalse(fixedForMusl, "musl has a mallopt that ignores every parameter");

    final boolean fixedWithoutLibrary = NativeHeapTrimmer.fixGlibcTrimThreshold(_ -> Optional.empty());
    assertFalse(fixedWithoutLibrary);
  }

  @Test
  void shouldReportAThresholdThatMalloptRefuses() {
    final MethodHandle refusing = MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, int.class, int.class);
    final boolean set = NativeHeapTrimmer.setTrimThreshold(refusing);
    assertFalse(set);
  }

  @Test
  void shouldReportAThresholdThatGlibcRefuses() throws ReflectiveOperationException {
    final List<List<Integer>> calls = new ArrayList<>();
    final boolean taken;
    final boolean refused;
    try (final Arena arena = Arena.ofConfined()) {
      taken = NativeHeapTrimmer.fixGlibcTrimThreshold(glibcWithMallopt(arena, recordingMallopt(calls, 1)));
      refused = NativeHeapTrimmer.fixGlibcTrimThreshold(glibcWithMallopt(arena, recordingMallopt(calls, 0)));
    }

    final List<Integer> threshold = List.of(-1, NativeHeapTrimmer.TRIM_THRESHOLD_BYTES);
    final List<List<Integer>> expected = List.of(threshold, threshold);

    assertTrue(taken);
    assertFalse(refused, "a threshold that glibc refuses is reported");
    assertEquals(expected, calls, "mallopt takes M_TRIM_THRESHOLD and the threshold");
  }

  @Test
  void shouldAskTheCLibraryOfThisJvmForTheThreshold() {
    final List<SymbolLookup> asked = new ArrayList<>();
    final boolean fixed;
    try (final MockedStatic<NativeHeapTrimmer> trimmer = mockStatic(NativeHeapTrimmer.class, CALLS_REAL_METHODS)) {
      trimmer
        .when(() -> NativeHeapTrimmer.fixGlibcTrimThreshold(any(SymbolLookup.class)))
        .thenAnswer(invocation -> this.recordTheAskedLibrary(asked, invocation));
      fixed = NativeHeapTrimmer.fixGlibcTrimThreshold();
    }

    final SymbolLookup ownLibrary = Linker.nativeLinker().defaultLookup();

    assertFalse(fixed, "a C library that keeps its threshold is reported");

    final int askedCount = asked.size();
    assertEquals(1, askedCount);

    final SymbolLookup askedLibrary = asked.getFirst();
    final Optional<MemorySegment> askedMalloc = askedLibrary.find(MALLOC_SYMBOL);
    final Optional<MemorySegment> expected = ownLibrary.find(MALLOC_SYMBOL);
    assertEquals(expected, askedMalloc, "the C library of this JVM is asked");
  }

  private boolean recordTheAskedLibrary(final List<SymbolLookup> asked, final InvocationOnMock invocation) {
    asked.add(invocation.getArgument(0));

    return false;
  }

  private static IntBinaryOperator recordingMallopt(final List<List<Integer>> calls, final int answer) {
    return (parameter, value) -> recordMallopt(calls, answer, parameter, value);
  }

  private static int recordMallopt(final List<List<Integer>> calls, final int answer, final int parameter, final int value) {
    calls.add(List.of(parameter, value));
    return answer;
  }

  // a glibc whose mallopt is a native stub that calls the operator; the trimmer only looks gnu_get_libc_version up
  @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
  private static SymbolLookup glibcWithMallopt(final Arena arena, final IntBinaryOperator mallopt) throws ReflectiveOperationException {
    final MethodType type = MethodType.methodType(int.class, int.class, int.class);
    final MethodHandle operator = MethodHandles.lookup().findVirtual(IntBinaryOperator.class, "applyAsInt", type).bindTo(mallopt);
    final FunctionDescriptor descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    final MemorySegment stub = Linker.nativeLinker().upcallStub(operator, descriptor, arena);
    return name -> name.equals(GLIBC_VERSION_SYMBOL) || name.equals(MALLOPT_SYMBOL) ? Optional.of(stub) : Optional.empty();
  }

  @Test
  void shouldReportAMalloptThatCannotBeCalledAsAnOperationalFailure() {
    final IllegalCallerException refused = new IllegalCallerException("native access is not enabled!");
    final MethodHandle mallopt = malloptThatThrows(refused);
    final MCAVException failure = assertThrows(MCAVException.class, () -> NativeHeapTrimmer.setTrimThreshold(mallopt));
    final String message = failure.getMessage();
    assertEquals("Could not fix the trim threshold of the native heap!", message);

    final Throwable cause = failure.getCause();
    assertSame(refused, cause);
  }

  @Test
  void shouldPropagateAVirtualMachineErrorOfMalloptAsItIs() {
    final OutOfMemoryError exhausted = new OutOfMemoryError("no memory for the call!");
    final MethodHandle mallopt = malloptThatThrows(exhausted);
    final OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, () -> NativeHeapTrimmer.setTrimThreshold(mallopt));
    assertSame(exhausted, failure);
  }

  private static MethodHandle malloptThatThrows(final Throwable failure) {
    final MethodHandle throwing = MethodHandles.throwException(int.class, failure.getClass());
    return MethodHandles.dropArguments(MethodHandles.insertArguments(throwing, 0, failure), 0, int.class, int.class);
  }

  private static void assertNeverTrims(final NativeHeapTrimmer trimmer, final String reason) {
    trimmer.start();
    final boolean trimming = trimmer.isTrimming();
    trimmer.stop();
    assertFalse(trimming, reason);
  }

  @Test
  void shouldStopWithoutTrimmingHarmlessly() {
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> {}, () -> {});
    trimmer.stop();
    final boolean trimming = trimmer.isTrimming();
    assertFalse(trimming);
  }

  @Test
  void shouldKeepTheInterruptOfTheCallerOfAnInterruptedStop() throws InterruptedException {
    final CountDownLatch trimStarted = new CountDownLatch(1);
    final Semaphore trimRelease = new Semaphore(0);
    final NativeHeapTrimmer trimmer = new NativeHeapTrimmer(SHORT, () -> this.signalAndAwaitRelease(trimStarted, trimRelease), () -> {});
    final boolean started;
    final Thread thread;
    final boolean interrupted;
    final boolean aliveAfterStop;
    try {
      trimmer.start();
      started = trimStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      thread = trimmingThread().orElseThrow();
      final Thread current = Thread.currentThread();
      current.interrupt();
      trimmer.stop();
      interrupted = Thread.interrupted();
      aliveAfterStop = thread.isAlive();
    } finally {
      trimRelease.release();
    }

    thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

    assertTrue(started);
    assertTrue(interrupted);
    assertTrue(aliveAfterStop, "an interrupted stop no longer waits for the trimming thread");

    final boolean alive = thread.isAlive();
    assertFalse(alive, "the trimming thread ends on its interrupt");
  }

  private void signalAndAwaitRelease(final CountDownLatch trimStarted, final Semaphore trimRelease) {
    trimStarted.countDown();
    trimRelease.acquireUninterruptibly();
  }

  @Test
  void shouldFollowTheIntervalOfTheSystemProperty() {
    final boolean linux = OsUtils.getOs() == Os.LINUX;
    final String previous = System.getProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
    try {
      System.setProperty(NativeHeapTrimmer.INTERVAL_PROPERTY, "0");
      assertNeverTrims(NativeHeapTrimmer.create(), "the property turns trimming off");

      System.clearProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
      final NativeHeapTrimmer trimmer = NativeHeapTrimmer.create();
      trimmer.start();
      final boolean trimming = trimmer.isTrimming();
      trimmer.stop();
      assertEquals(linux, trimming, UNTRIMMED_TEST_JVM_MESSAGE);
    } finally {
      if (previous == null) {
        System.clearProperty(NativeHeapTrimmer.INTERVAL_PROPERTY);
      } else {
        System.setProperty(NativeHeapTrimmer.INTERVAL_PROPERTY, previous);
      }
    }
  }

  @Test
  @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
  void shouldGiveTheTopOfAThreadArenaBackToTheSystemWhileTrimming() throws InterruptedException, IOException {
    final Linker linker = Linker.nativeLinker();
    final SymbolLookup library = linker.defaultLookup();
    assumeTrue(OsUtils.getOs() == Os.LINUX && library.find(GLIBC_VERSION_SYMBOL).isPresent(), "glibc's thread arenas!");

    final MethodHandle malloc = linker.downcallHandle(
      library.find(MALLOC_SYMBOL).orElseThrow(),
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    final MethodHandle free = linker.downcallHandle(library.find("free").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    final NativeHeapTrimmer trimmer = NativeHeapTrimmer.create();
    final long before;
    final long after;
    trimmer.start();
    try {
      before = residentKibibytes();
      final List<Thread> threads = new ArrayList<>();
      for (int index = 0; index < ARENA_THREADS; index++) {
        threads.add(Thread.ofPlatform().start(() -> freeFramesIntoTheArenaTop(malloc, free)));
      }

      for (final Thread thread : threads) {
        thread.join();
      }

      NativeHeapTrimmer.trimThisJvm();
      after = residentKibibytes();
    } finally {
      trimmer.stop();
    }

    final long freed = (ARENA_THREADS * FRAME_BYTES) / 1024;

    final boolean mostlyReturned = after - before < freed / 2;
    assertTrue(mostlyReturned, "kept " + (after - before) + " KiB of the " + freed + " KiB the threads freed");
  }

  // glibc maps the first frame on its own and raises its thresholds when it is freed; the second comes from the thread's
  // arena and joins its top when it is freed
  @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
  private static void freeFramesIntoTheArenaTop(final MethodHandle malloc, final MethodHandle free) {
    try {
      for (int frame = 0; frame < 2; frame++) {
        final MemorySegment block = ((MemorySegment) malloc.invokeExact(FRAME_BYTES)).reinterpret(FRAME_BYTES);
        block.fill((byte) 1);
        free.invokeExact(block);
      }
    } catch (final Throwable exception) {
      ThrowableUtils.throwIfFatal(exception);
      throw new IllegalStateException(exception);
    }
  }

  private static long residentKibibytes() throws IOException {
    for (final String line : Files.readAllLines(Path.of(PROCESS_STATUS_PATH))) {
      if (line.startsWith(RESIDENT_PREFIX)) {
        return Long.parseLong(line.substring(RESIDENT_PREFIX.length()).replace("kB", "").strip());
      }
    }

    final String message = "no resident size in /proc/self/status!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldReadTheIntervalOfTheJvmsOwnTrims() {
    final long intervalMilliseconds = NativeHeapTrimmer.readJvmIntervalMilliseconds();
    assertEquals(0L, intervalMilliseconds, UNTRIMMED_TEST_JVM_MESSAGE);
  }

  @Test
  void shouldLeaveAJvmThatTrimsItselfToItsOwnTrims() {
    final HotSpotDiagnosticMXBean trimmingItself = mock(HotSpotDiagnosticMXBean.class);
    final VMOption option = new VMOption("TrimNativeHeapInterval", "5000", true, VMOption.Origin.VM_CREATION);
    when(trimmingItself.getVMOption("TrimNativeHeapInterval")).thenReturn(option);
    final long interval;
    final NativeHeapTrimmer trimmer;
    try (final MockedStatic<ManagementFactory> factory = mockStatic(ManagementFactory.class, CALLS_REAL_METHODS)) {
      factory.when(() -> ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)).thenReturn(trimmingItself);
      interval = NativeHeapTrimmer.readJvmIntervalMilliseconds();
      trimmer = NativeHeapTrimmer.create();
    }

    assertEquals(5000L, interval);
    assertNeverTrims(trimmer, "a JVM started with -XX:TrimNativeHeapInterval is left to its own trims");
  }

  @Test
  void shouldNotTrimAJvmOtherThanHotSpot() {
    final HotSpotDiagnosticMXBean withoutTheOption = mock(HotSpotDiagnosticMXBean.class);
    final IllegalArgumentException noOption = new IllegalArgumentException("VM option \"TrimNativeHeapInterval\" does not exist!");
    when(withoutTheOption.getVMOption(any())).thenThrow(noOption);
    final IllegalArgumentException noBean = new IllegalArgumentException("not a platform management interface!");
    try (final MockedStatic<ManagementFactory> factory = mockStatic(ManagementFactory.class, CALLS_REAL_METHODS)) {
      factory.when(() -> ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)).thenReturn(null);
      assertNeverTrims(NativeHeapTrimmer.create(), "a JVM without HotSpot's diagnostic bean");

      factory.when(() -> ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)).thenReturn(withoutTheOption);
      assertNeverTrims(NativeHeapTrimmer.create(), "a JVM without the option of HotSpot's own trims");

      factory.when(() -> ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)).thenThrow(noBean);
      assertNeverTrims(NativeHeapTrimmer.create(), "a JVM that does not know HotSpot's diagnostic bean");
    }
  }

  @Test
  void shouldTrimWithTheCommandOfTheJdk() {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    final String answer = NativeHeapTrimmer.trimWithJdk(server);
    final String ownAnswer = NativeHeapTrimmer.trimThisJvm();
    final boolean linux = OsUtils.getOs() == Os.LINUX;
    final String expected = linux ? "Trim native heap" : "Not available";
    final boolean answered = answer.startsWith(expected);
    assertTrue(answered, answer);

    final boolean ownAnswered = ownAnswer.startsWith(expected);
    assertTrue(ownAnswered, ownAnswer);
  }

  @Test
  void shouldFailACommandThatCannotRunWithItsCause() throws Exception {
    final MBeanServer server = mock(MBeanServer.class);
    final InstanceNotFoundException missing = new InstanceNotFoundException("no diagnostic commands!");
    when(server.invoke(any(ObjectName.class), any(), any(), any())).thenThrow(missing);

    final MCAVException failure = assertThrows(MCAVException.class, () -> NativeHeapTrimmer.trimWithJdk(server));
    final Throwable cause = failure.getCause();
    assertInstanceOf(InstanceNotFoundException.class, cause);
  }
}

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
package me.brandonli.mcav.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class ModuleLoaderTest {

  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private static final String PORT_IN_USE_MESSAGE = "port in use!";
  private static final String MISSING_CONFIGURATION_MESSAGE = "Missing configuration!";
  private static final String SOCKET_CLOSED_MESSAGE = "socket already closed!";

  @Test
  void shouldStartModulesInRequestOrder() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(SecondModule.class, FirstModule.class);
    final SecondModule second = loader.getModule(SecondModule.class);
    final FirstModule first = loader.getModule(FirstModule.class);
    final Collection<MCAVModule> modules = loader.getModules();
    final List<MCAVModule> expected = List.of(second, first);
    final int secondStarts = second.starts.get();
    final int firstStarts = first.starts.get();
    assertEquals(expected, modules);
    assertEquals(1, secondStarts);
    assertEquals(1, firstStarts);

    final boolean secondStartedFirst = second.startedAt < first.startedAt;
    assertTrue(secondStartedFirst);
  }

  @Test
  void shouldLoadNothingForNoClasses() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules();
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldSkipModulesThatWereAlreadyStarted() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FirstModule.class, FirstModule.class);
    final FirstModule first = loader.getModule(FirstModule.class);
    loader.loadModules(FirstModule.class, SecondModule.class);
    final FirstModule firstAgain = loader.getModule(FirstModule.class);
    final Collection<MCAVModule> modules = loader.getModules();
    final int starts = first.starts.get();
    assertSame(first, firstAgain);
    assertEquals(1, starts);

    final int modulesCount = modules.size();
    assertEquals(2, modulesCount);
  }

  @Test
  void shouldCreateModulesThroughPrivateConstructors() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(HiddenModule.class);
    final HiddenModule module = loader.getModule(HiddenModule.class);
    final int starts = module.starts.get();
    assertEquals(1, starts);
  }

  @Test
  void shouldRejectNulls() {
    final ModuleLoader loader = new ModuleLoader();
    assertThrows(NullPointerException.class, () -> loader.loadModules((Class<?>[]) null));
    assertThrows(NullPointerException.class, () -> loader.loadModules(FirstModule.class, null));
    assertThrows(NullPointerException.class, () -> loader.getModule(null));

    final Collection<MCAVModule> modules = loader.getModules();
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldRejectClassesThatAreNotModulesBeforeStartingAnyModule() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(FirstModule.class, NotAModule.class));
    final String message = exception.getMessage();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals("Class NotAModule does not implement MCAVModule!", message);

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldWrapConstructorFailures() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(ThrowingConstructorModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals("Module ThrowingConstructorModule failed with \"broken constructor!\" while being created!", message);
    assertInstanceOf(IllegalStateException.class, cause);

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldRejectModulesThatCannotBeInstantiated() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException withArgument = assertThrows(ModuleException.class, () -> loader.loadModules(ArgumentModule.class));
    final ModuleException abstractClass = assertThrows(ModuleException.class, () -> loader.loadModules(AbstractModule.class));
    final ModuleException interfaceType = assertThrows(ModuleException.class, () -> loader.loadModules(MCAVModule.class));
    final String withArgumentMessage = withArgument.getMessage();
    final String abstractMessage = abstractClass.getMessage();
    final String interfaceMessage = interfaceType.getMessage();
    final Throwable withArgumentCause = withArgument.getCause();
    final Throwable abstractCause = abstractClass.getCause();
    final Throwable interfaceCause = interfaceType.getCause();
    final boolean withArgumentExplained = withArgumentMessage.startsWith("Module ArgumentModule failed with \"");
    final boolean abstractExplained = abstractMessage.startsWith("Module AbstractModule failed with \"");
    final boolean interfaceExplained = interfaceMessage.startsWith("Module MCAVModule failed with \"");
    assertTrue(withArgumentExplained, withArgumentMessage);
    assertTrue(abstractExplained, abstractMessage);
    assertTrue(interfaceExplained, interfaceMessage);
    assertInstanceOf(NoSuchMethodException.class, withArgumentCause);
    assertNotNull(abstractCause);
    assertNotNull(interfaceCause);
  }

  @Test
  void shouldWrapStartFailuresAndKeepEarlierModulesStarted() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () ->
      loader.loadModules(FirstModule.class, FailingStartModule.class, SecondModule.class)
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final FirstModule first = loader.getModule(FirstModule.class);
    final Collection<MCAVModule> modules = loader.getModules();
    final List<MCAVModule> expected = List.of(first);
    assertEquals("Module FailingStartModule failed with \"port in use!\" while starting!", message);
    assertInstanceOf(IllegalStateException.class, cause);
    assertEquals(expected, modules);
    assertThrows(ModuleException.class, () -> loader.getModule(FailingStartModule.class));
    assertThrows(ModuleException.class, () -> loader.getModule(SecondModule.class));

    loader.shutdownModules();
    final int stops = first.stops.get();
    assertEquals(1, stops);
  }

  @Test
  void shouldRollBackLinkageFailuresAndKeepEarlierModulesStarted() {
    LinkageStartModule.STOPS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException thrown = assertThrows(ModuleException.class, () ->
      loader.loadModules(FirstModule.class, LinkageStartModule.class, SecondModule.class)
    );
    final Throwable cause = thrown.getCause();
    final int failedStops = LinkageStartModule.STOPS.get();
    final FirstModule first = loader.getModule(FirstModule.class);
    final int firstStops = first.stops.get();
    final Collection<MCAVModule> modules = loader.getModules();
    final List<MCAVModule> expected = List.of(first);
    assertSame(LinkageStartModule.FAILURE, cause);
    assertEquals(1, failedStops);
    assertEquals(0, firstStops);
    assertEquals(expected, modules);
    assertThrows(ModuleException.class, () -> loader.getModule(LinkageStartModule.class));
    assertThrows(ModuleException.class, () -> loader.getModule(SecondModule.class));

    loader.shutdownModules();
    final int stoppedAfterShutdown = first.stops.get();
    final int failedStopsAfterShutdown = LinkageStartModule.STOPS.get();
    assertEquals(1, stoppedAfterShutdown);
    assertEquals(1, failedStopsAfterShutdown);
  }

  @Test
  void shouldKeepAnAssertionStartFailureAndSuppressTheCleanupFailure() {
    AssertionStartAndStopModule.STOPS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException thrown = assertThrows(ModuleException.class, () -> loader.loadModules(AssertionStartAndStopModule.class));
    final Throwable cause = thrown.getCause();
    final Throwable[] suppressed = thrown.getSuppressed();
    final int stops = AssertionStartAndStopModule.STOPS.get();
    final String message = thrown.getMessage();
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean empty = modules.isEmpty();
    assertSame(AssertionStartAndStopModule.START_FAILURE, cause);
    assertEquals(1, stops);
    assertEquals(1, suppressed.length);

    final Throwable firstSuppressed = suppressed[0];
    assertSame(AssertionStartAndStopModule.STOP_FAILURE, firstSuppressed);
    assertEquals("Module asserting module failed with \"startup invariant failed!\" while starting!", message);
    assertTrue(empty);
  }

  @Test
  void shouldPassVirtualMachineErrorsOfAStartThroughUnwrapped() {
    FatalStartModule.STOPS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> loader.loadModules(FatalStartModule.class));
    final int stops = FatalStartModule.STOPS.get();
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean empty = modules.isEmpty();
    assertSame(FatalStartModule.FAILURE, thrown);
    assertEquals(0, stops, "fatal VM errors propagate without attempting foreign cleanup");
    assertTrue(empty);
  }

  @Test
  void shouldKeepThePrimaryFailureWhenStartAndStopThrowTheSameException() {
    SameStartAndStopFailureModule.STOPS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException thrown = assertThrows(ModuleException.class, () -> loader.loadModules(SameStartAndStopFailureModule.class));
    final Throwable[] suppressed = thrown.getSuppressed();
    final int stops = SameStartAndStopFailureModule.STOPS.get();
    assertSame(SameStartAndStopFailureModule.FAILURE, thrown);
    assertEquals(1, stops);
    assertEquals(0, suppressed.length, "a throwable cannot suppress itself");
  }

  @Test
  void shouldPassModuleExceptionsThroughUnchanged() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(ModuleExceptionStartModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals(MISSING_CONFIGURATION_MESSAGE, message);
    assertNull(cause);

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldStopModulesInReverseOrderEvenWhenSomeFail() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FirstModule.class, FailingStopModule.class, SecondModule.class, ErrorStopModule.class);
    final FirstModule first = loader.getModule(FirstModule.class);
    final FailingStopModule failing = loader.getModule(FailingStopModule.class);
    final SecondModule second = loader.getModule(SecondModule.class);
    final ErrorStopModule error = loader.getModule(ErrorStopModule.class);
    loader.shutdownModules();
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean errorStoppedFirst = error.stoppedAt < second.stoppedAt;
    assertTrue(errorStoppedFirst);

    final boolean secondStoppedBeforeFailing = second.stoppedAt < failing.stoppedAt;
    assertTrue(secondStoppedBeforeFailing);

    final boolean failingStoppedBeforeFirst = failing.stoppedAt < first.stoppedAt;
    assertTrue(failingStoppedBeforeFirst);

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
    assertThrows(ModuleException.class, () -> loader.getModule(FirstModule.class));

    loader.shutdownModules();
    final int firstStops = first.stops.get();
    final int failingStops = failing.stops.get();
    final int secondStops = second.stops.get();
    final int errorStops = error.stops.get();
    assertEquals(1, firstStops);
    assertEquals(1, failingStops);
    assertEquals(1, secondStops);
    assertEquals(1, errorStops);
  }

  @Test
  void shouldCreateNewModulesAfterAShutdown() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FirstModule.class);
    final FirstModule before = loader.getModule(FirstModule.class);
    loader.shutdownModules();
    loader.loadModules(FirstModule.class);
    final FirstModule after = loader.getModule(FirstModule.class);
    final int starts = after.starts.get();
    assertNotSame(before, after);
    assertEquals(1, starts);
  }

  @Test
  void shouldReturnAnImmutableSnapshotOfTheModules() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FirstModule.class);
    final Collection<MCAVModule> snapshot = loader.getModules();
    loader.loadModules(SecondModule.class);
    final Collection<MCAVModule> current = loader.getModules();
    final FirstModule first = loader.getModule(FirstModule.class);
    final int snapshotCount = snapshot.size();
    assertEquals(1, snapshotCount);

    final int currentCount = current.size();
    assertEquals(2, currentCount);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(first));
    assertThrows(UnsupportedOperationException.class, current::clear);
  }

  @Test
  void shouldReportModulesThatAreNotInstalled() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.getModule(FirstModule.class));
    final String message = exception.getMessage();
    assertEquals("Module FirstModule is not installed!", message);
  }

  @Test
  void shouldFindAModuleOnlyOnceItHasStarted() {
    final ModuleLoader loader = new ModuleLoader();
    SelfLookupModule.LOADER.set(loader);
    loader.loadModules(SelfLookupModule.class);

    final SelfLookupModule module = loader.getModule(SelfLookupModule.class);
    final boolean foundWhileStarting = module.foundWhileStarting;
    assertFalse(foundWhileStarting);
  }

  @Test
  void shouldStartEachModuleOnceUnderConcurrentLoads() throws Exception {
    CountedModule.STARTS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    final int threads = 8;
    final CountDownLatch startGate = new CountDownLatch(1);
    final List<Future<?>> futures = new ArrayList<>();
    try (final ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int index = 0; index < threads; index++) {
        final Future<?> future = executor.submit(() -> this.awaitAndLoad(startGate, loader));
        futures.add(future);
      }

      startGate.countDown();
      for (final Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }

    final Collection<MCAVModule> modules = loader.getModules();
    final int starts = CountedModule.STARTS.get();
    assertEquals(1, starts);

    final int modulesCount = modules.size();
    assertEquals(2, modulesCount);
  }

  private @Nullable Void awaitAndLoad(final CountDownLatch startGate, final ModuleLoader loader) throws InterruptedException {
    startGate.await();
    loader.loadModules(CountedModule.class, SecondModule.class);
    return null;
  }

  @Test
  void shouldStopAModuleWhoseStartFailed() {
    TrackedFailingStartModule.STOPS.set(0);
    TrackedModuleExceptionStartModule.STOPS.set(0);
    final ModuleLoader loader = new ModuleLoader();
    assertThrows(ModuleException.class, () -> loader.loadModules(TrackedFailingStartModule.class));
    assertThrows(ModuleException.class, () -> loader.loadModules(TrackedModuleExceptionStartModule.class));

    final int wrappedFailureStops = TrackedFailingStartModule.STOPS.get();
    final int moduleExceptionStops = TrackedModuleExceptionStartModule.STOPS.get();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals(1, wrappedFailureStops, "a module that failed to start releases what it acquired");
    assertEquals(1, moduleExceptionStops, "a module that failed to start releases what it acquired");

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void shouldKeepTheStartFailureWhenStoppingTheModuleFailsToo() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(FailingStartAndStopModule.class));
    final String message = exception.getMessage();
    final Throwable[] suppressed = exception.getSuppressed();
    assertEquals("Module FailingStartAndStopModule failed with \"port in use!\" while starting!", message);
    assertEquals(1, suppressed.length);

    final Throwable stopFailure = suppressed[0];
    final String stopMessage = stopFailure.getMessage();
    assertEquals(SOCKET_CLOSED_MESSAGE, stopMessage);
  }

  @Test
  void shouldPassVirtualMachineErrorsOfAStopThroughTheShutdown() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FatalStopModule.class);
    final StackOverflowError thrown = assertThrows(StackOverflowError.class, loader::shutdownModules);
    assertSame(FatalStopModule.FAILURE, thrown, "a virtual machine error is never only logged");
  }

  @Test
  void shouldPassVirtualMachineErrorsOfAStopAfterAFailedStartThrough() {
    final ModuleLoader loader = new ModuleLoader();
    final StackOverflowError thrown = assertThrows(StackOverflowError.class, () ->
      loader.loadModules(FailingStartAndFatalStopModule.class)
    );
    assertSame(FailingStartAndFatalStopModule.FAILURE, thrown, "a virtual machine error is never only attached");
  }

  @Test
  void shouldPassVirtualMachineErrorsOfAConstructorThroughUnwrapped() {
    final ModuleLoader loader = new ModuleLoader();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> loader.loadModules(FatalConstructorModule.class));
    final Collection<MCAVModule> modules = loader.getModules();
    assertSame(FatalConstructorModule.FAILURE, thrown, "a virtual machine error is never wrapped into a module failure");

    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  private static class RecordingModule implements MCAVModule {

    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();
    volatile int startedAt = -1;
    volatile int stoppedAt = -1;

    @Override
    public void start() {
      this.starts.incrementAndGet();
      this.startedAt = SEQUENCE.incrementAndGet();
    }

    @Override
    public void stop() {
      this.stops.incrementAndGet();
      this.stoppedAt = SEQUENCE.incrementAndGet();
    }

    @Override
    public String getModuleName() {
      final Class<?> type = this.getClass();
      return type.getSimpleName();
    }
  }

  static final class FirstModule extends RecordingModule {}

  static final class SecondModule extends RecordingModule {}

  static final class CountedModule extends RecordingModule {

    private static final AtomicInteger STARTS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      STARTS.incrementAndGet();
    }
  }

  static final class SelfLookupModule extends RecordingModule {

    private static final AtomicReference<ModuleLoader> LOADER = new AtomicReference<>();

    volatile boolean foundWhileStarting;

    @Override
    public void start() {
      super.start();
      final ModuleLoader loader = LOADER.get();
      this.foundWhileStarting = this.isFoundBy(loader);
    }

    private boolean isFoundBy(final ModuleLoader loader) {
      try {
        final SelfLookupModule _ = loader.getModule(SelfLookupModule.class);
        return true;
      } catch (final ModuleException exception) {
        return false;
      }
    }
  }

  static final class HiddenModule extends RecordingModule {

    private HiddenModule() {}
  }

  static final class FailingStopModule extends RecordingModule {

    @Override
    public void stop() {
      super.stop();

      final String message = "stop failed!";
      throw new IllegalStateException(message);
    }
  }

  static final class ErrorStopModule extends RecordingModule {

    @Override
    public void stop() {
      super.stop();

      final String message = "stop crashed!";
      throw new AssertionError(message);
    }
  }

  static final class FatalStopModule extends RecordingModule {

    private static final StackOverflowError FAILURE = new StackOverflowError("stop recursed!");

    @Override
    public void stop() {
      super.stop();
      throw FAILURE;
    }
  }

  static final class FailingStartAndFatalStopModule extends RecordingModule {

    private static final StackOverflowError FAILURE = new StackOverflowError("stop recursed!");

    @Override
    public void start() {
      throw new IllegalStateException(PORT_IN_USE_MESSAGE);
    }

    @Override
    public void stop() {
      throw FAILURE;
    }
  }

  static final class FatalConstructorModule extends RecordingModule {

    private static final OutOfMemoryError FAILURE = new OutOfMemoryError("heap is full!");

    FatalConstructorModule() {
      throw FAILURE;
    }
  }

  static final class FailingStartModule extends RecordingModule {

    @Override
    public void start() {
      super.start();
      throw new IllegalStateException(PORT_IN_USE_MESSAGE);
    }
  }

  static final class LinkageStartModule extends RecordingModule {

    private static final UnsatisfiedLinkError FAILURE = new UnsatisfiedLinkError("native module missing!");
    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw FAILURE;
    }

    @Override
    public void stop() {
      super.stop();
      STOPS.incrementAndGet();
    }
  }

  static final class AssertionStartAndStopModule extends RecordingModule {

    private static final AssertionError START_FAILURE = new AssertionError("startup invariant failed!");
    private static final LinkageError STOP_FAILURE = new LinkageError("cleanup native missing!");
    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw START_FAILURE;
    }

    @Override
    public void stop() {
      STOPS.incrementAndGet();
      throw STOP_FAILURE;
    }

    @Override
    public String getModuleName() {
      final int starts = this.starts.get();
      if (starts != 0) {
        final String message = "a failed module cannot describe itself!";
        throw new IllegalStateException(message);
      }

      return "asserting module";
    }
  }

  static final class FatalStartModule extends RecordingModule {

    private static final OutOfMemoryError FAILURE = new OutOfMemoryError("start exhausted memory!");
    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      throw FAILURE;
    }

    @Override
    public void stop() {
      STOPS.incrementAndGet();
    }
  }

  static final class SameStartAndStopFailureModule extends RecordingModule {

    private static final ModuleException FAILURE = new ModuleException("Module failed!");
    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      throw FAILURE;
    }

    @Override
    public void stop() {
      STOPS.incrementAndGet();
      throw FAILURE;
    }
  }

  static final class ModuleExceptionStartModule extends RecordingModule {

    @Override
    public void start() {
      super.start();
      throw new ModuleException(MISSING_CONFIGURATION_MESSAGE);
    }
  }

  static final class TrackedFailingStartModule extends RecordingModule {

    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw new IllegalStateException(PORT_IN_USE_MESSAGE);
    }

    @Override
    public void stop() {
      super.stop();
      STOPS.incrementAndGet();
    }
  }

  static final class TrackedModuleExceptionStartModule extends RecordingModule {

    private static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw new ModuleException(MISSING_CONFIGURATION_MESSAGE);
    }

    @Override
    public void stop() {
      super.stop();
      STOPS.incrementAndGet();
    }
  }

  static final class FailingStartAndStopModule extends RecordingModule {

    @Override
    public void start() {
      throw new IllegalStateException(PORT_IN_USE_MESSAGE);
    }

    @Override
    public void stop() {
      throw new IllegalStateException(SOCKET_CLOSED_MESSAGE);
    }
  }

  static final class ThrowingConstructorModule extends RecordingModule {

    ThrowingConstructorModule() {
      final String message = "broken constructor!";
      throw new IllegalStateException(message);
    }
  }

  static final class ArgumentModule extends RecordingModule {

    private final String name;

    ArgumentModule(final String name) {
      this.name = name;
    }

    @Override
    public String getModuleName() {
      return this.name;
    }
  }

  abstract static class AbstractModule extends RecordingModule {}

  static final class NotAModule {}
}

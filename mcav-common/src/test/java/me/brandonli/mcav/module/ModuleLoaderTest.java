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
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ModuleLoader} and {@link ModuleException}.
 */
final class ModuleLoaderTest {

  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  /**
   * A module that records when it was started and stopped.
   */
  static class RecordingModule implements MCAVModule {

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

  static final class HiddenModule extends RecordingModule {

    private HiddenModule() {
      // only reachable through the private lookup of the loader
    }
  }

  static final class FailingStopModule extends RecordingModule {

    @Override
    public void stop() {
      super.stop();
      throw new IllegalStateException("stop failed");
    }
  }

  static final class ErrorStopModule extends RecordingModule {

    @Override
    public void stop() {
      super.stop();
      throw new AssertionError("stop crashed");
    }
  }

  static final class FatalStopModule extends RecordingModule {

    static final StackOverflowError FAILURE = new StackOverflowError("stop recursed");

    @Override
    public void stop() {
      super.stop();
      throw FAILURE;
    }
  }

  static final class FailingStartAndFatalStopModule extends RecordingModule {

    static final StackOverflowError FAILURE = new StackOverflowError("stop recursed");

    @Override
    public void start() {
      throw new IllegalStateException("port in use");
    }

    @Override
    public void stop() {
      throw FAILURE;
    }
  }

  static final class FatalConstructorModule extends RecordingModule {

    static final OutOfMemoryError FAILURE = new OutOfMemoryError("heap is full");

    FatalConstructorModule() {
      throw FAILURE;
    }
  }

  static final class FailingStartModule extends RecordingModule {

    @Override
    public void start() {
      super.start();
      throw new IllegalStateException("port in use");
    }
  }

  static final class ModuleExceptionStartModule extends RecordingModule {

    @Override
    public void start() {
      super.start();
      throw new ModuleException("missing configuration");
    }
  }

  static final class TrackedFailingStartModule extends RecordingModule {

    static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw new IllegalStateException("port in use");
    }

    @Override
    public void stop() {
      super.stop();
      STOPS.incrementAndGet();
    }
  }

  static final class TrackedModuleExceptionStartModule extends RecordingModule {

    static final AtomicInteger STOPS = new AtomicInteger();

    @Override
    public void start() {
      super.start();
      throw new ModuleException("missing configuration");
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
      throw new IllegalStateException("port in use");
    }

    @Override
    public void stop() {
      throw new IllegalStateException("socket already closed");
    }
  }

  static final class ThrowingConstructorModule extends RecordingModule {

    ThrowingConstructorModule() {
      throw new IllegalStateException("broken constructor");
    }
  }

  static final class ArgumentModule extends RecordingModule {

    private final String name;

    ArgumentModule(final String name) {
      // the only constructor takes an argument, so the loader cannot create this module
      this.name = name;
    }

    @Override
    public String getModuleName() {
      return this.name;
    }
  }

  abstract static class AbstractModule extends RecordingModule {}

  static final class NotAModule {}

  @Test
  void startsModulesInRequestOrder() {
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
    assertTrue(second.startedAt < first.startedAt);
  }

  @Test
  void loadsNothingForNoClasses() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules();
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void skipsModulesThatWereAlreadyStarted() {
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
  void createsModulesThroughPrivateConstructors() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(HiddenModule.class);
    final HiddenModule module = loader.getModule(HiddenModule.class);
    final int starts = module.starts.get();
    assertEquals(1, starts);
  }

  @Test
  void rejectsNulls() {
    final ModuleLoader loader = new ModuleLoader();
    assertThrows(NullPointerException.class, () -> loader.loadModules((Class<?>[]) null));
    assertThrows(NullPointerException.class, () -> loader.loadModules(FirstModule.class, null));
    assertThrows(NullPointerException.class, () -> loader.getModule(null));
    final Collection<MCAVModule> modules = loader.getModules();
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void rejectsClassesThatAreNotModulesBeforeStartingAnyModule() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(FirstModule.class, NotAModule.class));
    final String message = exception.getMessage();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals("NotAModule does not implement MCAVModule", message);
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void wrapsConstructorFailures() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(ThrowingConstructorModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals("Module ThrowingConstructorModule could not be created: broken constructor", message);
    assertInstanceOf(IllegalStateException.class, cause);
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void rejectsModulesThatCannotBeInstantiated() {
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
    final boolean withArgumentExplained = withArgumentMessage.startsWith("Module ArgumentModule could not be created: ");
    final boolean abstractExplained = abstractMessage.startsWith("Module AbstractModule could not be created: ");
    final boolean interfaceExplained = interfaceMessage.startsWith("Module MCAVModule could not be created: ");
    assertTrue(withArgumentExplained, withArgumentMessage);
    assertTrue(abstractExplained, abstractMessage);
    assertTrue(interfaceExplained, interfaceMessage);
    assertInstanceOf(NoSuchMethodException.class, withArgumentCause);
    assertNotNull(abstractCause);
    assertNotNull(interfaceCause);
  }

  @Test
  void wrapsStartFailuresAndKeepsEarlierModulesStarted() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () ->
      loader.loadModules(FirstModule.class, FailingStartModule.class, SecondModule.class)
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final FirstModule first = loader.getModule(FirstModule.class);
    final Collection<MCAVModule> modules = loader.getModules();
    final List<MCAVModule> expected = List.of(first);
    assertEquals("Module FailingStartModule failed to start: port in use", message);
    assertInstanceOf(IllegalStateException.class, cause);
    assertEquals(expected, modules);
    assertThrows(ModuleException.class, () -> loader.getModule(FailingStartModule.class));
    assertThrows(ModuleException.class, () -> loader.getModule(SecondModule.class));
    loader.shutdownModules();
    final int stops = first.stops.get();
    assertEquals(1, stops);
  }

  @Test
  void passesModuleExceptionsThroughUnchanged() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(ModuleExceptionStartModule.class));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final Collection<MCAVModule> modules = loader.getModules();
    assertEquals("missing configuration", message);
    assertNull(cause);
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void stopsModulesInReverseOrderEvenWhenSomeFail() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FirstModule.class, FailingStopModule.class, SecondModule.class, ErrorStopModule.class);
    final FirstModule first = loader.getModule(FirstModule.class);
    final FailingStopModule failing = loader.getModule(FailingStopModule.class);
    final SecondModule second = loader.getModule(SecondModule.class);
    final ErrorStopModule error = loader.getModule(ErrorStopModule.class);
    loader.shutdownModules();
    final Collection<MCAVModule> modules = loader.getModules();
    assertTrue(error.stoppedAt < second.stoppedAt);
    assertTrue(second.stoppedAt < failing.stoppedAt);
    assertTrue(failing.stoppedAt < first.stoppedAt);
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
  void createsNewModulesAfterAShutdown() {
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
  void returnsAnImmutableSnapshotOfTheModules() {
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
  void reportsModulesThatAreNotInstalled() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.getModule(FirstModule.class));
    final String message = exception.getMessage();
    assertEquals("Module FirstModule is not installed", message);
  }

  @Test
  void startsEachModuleOnceUnderConcurrentLoads() throws Exception {
    final ModuleLoader loader = new ModuleLoader();
    final int threads = 8;
    final CountDownLatch startGate = new CountDownLatch(1);
    final List<Future<?>> futures = new ArrayList<>();
    try (final ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int index = 0; index < threads; index++) {
        final Future<?> future = executor.submit(() -> {
          startGate.await();
          loader.loadModules(FirstModule.class, SecondModule.class);
          return null;
        });
        futures.add(future);
      }
      startGate.countDown();
      for (final Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    final FirstModule first = loader.getModule(FirstModule.class);
    final SecondModule second = loader.getModule(SecondModule.class);
    final Collection<MCAVModule> modules = loader.getModules();
    final int firstStarts = first.starts.get();
    final int secondStarts = second.starts.get();
    assertEquals(1, firstStarts);
    assertEquals(1, secondStarts);
    final int modulesCount = modules.size();
    assertEquals(2, modulesCount);
  }

  @Test
  void stopsAModuleWhoseStartFailed() {
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
  void keepsTheStartFailureWhenStoppingTheModuleFailsToo() {
    final ModuleLoader loader = new ModuleLoader();
    final ModuleException exception = assertThrows(ModuleException.class, () -> loader.loadModules(FailingStartAndStopModule.class));
    final String message = exception.getMessage();
    final Throwable[] suppressed = exception.getSuppressed();
    assertEquals("Module FailingStartAndStopModule failed to start: port in use", message);
    assertEquals(1, suppressed.length);
    final Throwable stopFailure = suppressed[0];
    final String stopMessage = stopFailure.getMessage();
    assertEquals("socket already closed", stopMessage);
  }

  @Test
  void passesVirtualMachineErrorsOfAStopThroughTheShutdown() {
    final ModuleLoader loader = new ModuleLoader();
    loader.loadModules(FatalStopModule.class);
    final StackOverflowError thrown = assertThrows(StackOverflowError.class, loader::shutdownModules);
    assertSame(FatalStopModule.FAILURE, thrown, "a virtual machine error is never only logged");
  }

  @Test
  void passesVirtualMachineErrorsOfAStopAfterAFailedStartThrough() {
    final ModuleLoader loader = new ModuleLoader();
    final StackOverflowError thrown = assertThrows(StackOverflowError.class, () -> loader.loadModules(FailingStartAndFatalStopModule.class)
    );
    assertSame(FailingStartAndFatalStopModule.FAILURE, thrown, "a virtual machine error is never only attached");
  }

  @Test
  void passesVirtualMachineErrorsOfAConstructorThroughUnwrapped() {
    final ModuleLoader loader = new ModuleLoader();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> loader.loadModules(FatalConstructorModule.class));
    final Collection<MCAVModule> modules = loader.getModules();
    assertSame(FatalConstructorModule.FAILURE, thrown, "a virtual machine error is never wrapped into a module failure");
    final boolean modulesEmpty = modules.isEmpty();
    assertTrue(modulesEmpty);
  }

  @Test
  void createsExceptionsWithMessageAndCause() {
    final IllegalStateException cause = new IllegalStateException("cause");
    final ModuleException withMessage = new ModuleException("message");
    final ModuleException withCause = new ModuleException("wrapped", cause);
    final ModuleException withNulls = new ModuleException(null, null);
    final String message = withMessage.getMessage();
    final Throwable noCause = withMessage.getCause();
    final String wrappedMessage = withCause.getMessage();
    final Throwable wrappedCause = withCause.getCause();
    final String nullMessage = withNulls.getMessage();
    assertEquals("message", message);
    assertNull(noCause);
    assertEquals("wrapped", wrappedMessage);
    assertSame(cause, wrappedCause);
    assertNull(nullMessage);
    assertInstanceOf(RuntimeException.class, withMessage, "a module failure is recoverable, so it is no Error");
  }
}

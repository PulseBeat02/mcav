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
import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.brandonli.mcav.capability.Availability;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.KeyFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Prepares VLC and yt-dlp on daemon threads, which MCAV.release() joins; callers get only asynchronous stages, so callbacks can call it.
final class BackgroundInstallation {

  private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundInstallation.class);

  private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);

  private static final String THREAD_NAME_PREFIX = "MCAV Installer ";

  private final DependencyLoader dependencyLoader;
  private final CapabilityGuard guard;
  private final Logger logger;
  private final Duration cancelTimeout;
  private final Map<Capability, Runnable> steps;
  private final Map<Capability, CompletableFuture<Boolean>> preparations;
  private final List<Thread> threads;
  private final Object lock;

  private boolean cancelled;

  BackgroundInstallation(final DependencyLoader dependencyLoader, final CapabilityGuard guard) {
    this(dependencyLoader, guard, LOGGER, CANCEL_TIMEOUT);
  }

  // Takes the logger and the timeout, so a test sees each log call, which kills the mutants that skip them, and need not wait ten seconds.
  @VisibleForTesting
  BackgroundInstallation(
    final DependencyLoader dependencyLoader,
    final CapabilityGuard guard,
    final Logger logger,
    final Duration cancelTimeout
  ) {
    this.dependencyLoader = dependencyLoader;
    this.guard = guard;
    this.logger = logger;
    this.cancelTimeout = cancelTimeout;
    this.steps = this.createSteps(dependencyLoader);
    this.preparations = new EnumMap<>(Capability.class);
    this.threads = new ArrayList<>();
    this.lock = new Object();
  }

  private Map<Capability, Runnable> createSteps(@UnderInitialization BackgroundInstallation this, final DependencyLoader dependencyLoader) {
    final Map<Capability, Runnable> steps = new EnumMap<>(Capability.class);
    steps.put(Capability.VLC, dependencyLoader::installVlc);
    steps.put(Capability.YT_DLP, dependencyLoader::installYtdlp);
    return steps;
  }

  // Once this returns, the guard reports every program as preparing, so no feature can use one the installation still prepares.
  void start() {
    final Set<Map.Entry<@KeyFor("this.steps") Capability, Runnable>> entries = this.steps.entrySet();
    for (final Map.Entry<@KeyFor("this.steps") Capability, Runnable> entry : entries) {
      final Capability capability = entry.getKey();
      final Runnable step = entry.getValue();
      final CompletableFuture<Boolean> preparation = new CompletableFuture<>();
      this.preparations.put(capability, preparation);
      this.guard.markPreparing(capability);
      final Thread thread = this.createThread(capability, step);
      this.threads.add(thread);
    }

    for (final Thread thread : this.threads) {
      thread.start();
    }
  }

  private Thread createThread(final Capability capability, final Runnable step) {
    final String name = capability.getDisplayName();
    final Runnable task = () -> this.prepare(capability, step);
    final Thread thread = new Thread(task, THREAD_NAME_PREFIX + name);
    thread.setDaemon(true);
    thread.setUncaughtExceptionHandler(this::reportUncaughtError);
    return thread;
  }

  // An Error still ends the preparation, so nobody waits forever, then ends the thread, whose uncaught exception handler logs it.
  private void prepare(final Capability capability, final Runnable step) {
    try {
      final Availability reported = this.runStep(capability, step);
      this.finish(capability, reported);
    } catch (final Error exception) {
      this.finish(capability, Availability.UNAVAILABLE);
      throw exception;
    }
  }

  private Availability runStep(final Capability capability, final Runnable step) {
    try {
      step.run();

      return Availability.AVAILABLE;
    } catch (final RuntimeException exception) {
      final String name = capability.getDisplayName();
      this.logger.warn("{} could not be prepared because of an unexpected failure.", name, exception);

      return Availability.UNAVAILABLE;
    }
  }

  private void reportUncaughtError(final Thread thread, final Throwable failure) {
    final String threadName = thread.getName();
    this.logger.error("{} ended with an error.", threadName, failure);
  }

  // A preparation that ends after cancel() reports its program unavailable and leaves the guard alone, as cancel() forgot it.
  private void finish(final Capability capability, final Availability reported) {
    final CompletableFuture<Boolean> preparation = this.getPreparation(capability);
    synchronized (this.lock) {
      final boolean loaded = this.dependencyLoader.hasCapability(capability);
      final boolean available = reported == Availability.AVAILABLE && loaded && !this.cancelled;
      final Availability availability = available ? Availability.AVAILABLE : Availability.UNAVAILABLE;
      if (!this.cancelled) {
        this.guard.markPrepared(capability, availability);
      }

      preparation.complete(available);
    }
  }

  boolean isAvailable(final Capability capability) {
    final CompletableFuture<Boolean> preparation = this.preparations.get(capability);
    if (preparation == null) {
      return this.dependencyLoader.hasCapability(capability);
    }

    return preparation.getNow(false);
  }

  CompletableFuture<Boolean> whenReady(final Capability capability) {
    final CompletableFuture<Boolean> preparation = this.preparations.get(capability);
    if (preparation == null) {
      final boolean available = this.dependencyLoader.hasCapability(capability);
      return CompletableFuture.completedFuture(available);
    }

    final boolean done = preparation.isDone();
    if (done) {
      final Boolean available = preparation.join();
      return CompletableFuture.completedFuture(available);
    }

    // an asynchronous stage, so callbacks of the caller never run on an installation thread
    final Function<Boolean, Boolean> identity = Function.identity();
    return preparation.thenApplyAsync(identity);
  }

  void cancel() {
    synchronized (this.lock) {
      this.cancelled = true;
    }

    for (final Thread thread : this.threads) {
      thread.interrupt();
    }

    this.awaitThreads();
    synchronized (this.lock) {
      final Set<Map.Entry<@KeyFor("this.preparations") Capability, CompletableFuture<Boolean>>> entries = this.preparations.entrySet();
      for (final Map.Entry<@KeyFor("this.preparations") Capability, CompletableFuture<Boolean>> entry : entries) {
        final Capability capability = entry.getKey();
        final CompletableFuture<Boolean> preparation = entry.getValue();
        this.guard.forget(capability);
        preparation.complete(false);
      }
    }
  }

  private void awaitThreads() {
    final long timeoutNanoseconds = this.cancelTimeout.toNanos();
    final long deadline = System.nanoTime() + timeoutNanoseconds;
    for (final Thread thread : this.threads) {
      final long now = System.nanoTime();
      final long remainingNanoseconds = Math.max(0L, deadline - now);
      final Duration remaining = Duration.ofNanos(remainingNanoseconds);
      final boolean ended = this.join(thread, remaining);
      if (!ended) {
        final String threadName = thread.getName();
        final long timeoutMilliseconds = this.cancelTimeout.toMillis();
        this.logger.warn("{} did not stop within {} ms after it was cancelled.", threadName, timeoutMilliseconds);
      }
    }
  }

  private boolean join(final Thread thread, final Duration timeout) {
    try {
      return thread.join(timeout);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      return false;
    }
  }

  private CompletableFuture<Boolean> getPreparation(final Capability capability) {
    final CompletableFuture<Boolean> preparation = this.preparations.get(capability);
    return Preconditions.checkNotNull(preparation, "Every prepared capability has a future!");
  }

  @VisibleForTesting
  List<Thread> getThreads() {
    return List.copyOf(this.threads);
  }
}

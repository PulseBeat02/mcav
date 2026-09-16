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
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import org.checkerframework.checker.nullness.qual.KeyFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prepares the external programs of the library, VLC and yt-dlp, in the background after
 * {@link MCAV#install(Class[])} returned, each on a daemon thread of its own, so a slow download never delays the
 * start of the application.
 *
 * <p>Each program has a future that completes once its preparation finished: with true if its capability is
 * available, with false if it is not supported, failed, or was cancelled. The futures are completed directly, but
 * callers only ever see asynchronous stages of them, so their callbacks never run on the installation threads and can
 * safely call back into the library, even {@link MCAV#release()}, which waits for those threads.
 *
 * <p>{@link #cancel()} interrupts both threads, which makes a running download fail at once and delete its temporary
 * file, and waits a bounded time for the threads to end.
 */
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

  /**
   * Constructs an installation that prepares VLC and yt-dlp with the specified loader.
   *
   * @param dependencyLoader installs the programs and knows which capabilities are available
   * @param guard            records which capabilities are being prepared, for the features that consult it
   */
  BackgroundInstallation(final DependencyLoader dependencyLoader, final CapabilityGuard guard) {
    this(dependencyLoader, guard, LOGGER, CANCEL_TIMEOUT);
  }

  /**
   * Constructs an installation with a custom logger and cancel timeout.
   *
   * @param dependencyLoader installs the programs and knows which capabilities are available
   * @param guard            records which capabilities are being prepared, for the features that consult it
   * @param logger           reports unexpected failures of the installation threads
   * @param cancelTimeout    how long {@link #cancel()} waits at most for the installation threads to end
   */
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
    this.steps = createSteps(dependencyLoader);
    this.preparations = new EnumMap<>(Capability.class);
    this.threads = new ArrayList<>();
    this.lock = new Object();
  }

  private static Map<Capability, Runnable> createSteps(final DependencyLoader dependencyLoader) {
    final Map<Capability, Runnable> steps = new EnumMap<>(Capability.class);
    steps.put(Capability.VLC, dependencyLoader::installVLC);
    steps.put(Capability.YT_DLP, dependencyLoader::installYTDLP);
    return steps;
  }

  /**
   * Marks every program as preparing and starts preparing them. When this method returns, the guard already
   * reports the programs as preparing, so no feature can use a program the installation is still preparing.
   */
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

  /**
   * Runs the step of one program on its installation thread. An error, such as an {@link OutOfMemoryError}, still
   * ends the preparation, so nobody waits forever, and then ends the thread; its uncaught exception handler logs it.
   */
  private void prepare(final Capability capability, final Runnable step) {
    try {
      final boolean succeeded = this.runStep(capability, step);
      this.finish(capability, succeeded);
    } catch (final Error error) {
      this.finish(capability, false);
      throw error;
    }
  }

  private boolean runStep(final Capability capability, final Runnable step) {
    try {
      step.run();
      return true;
    } catch (final RuntimeException exception) {
      // the loader reports every expected failure itself, so only a bug of the step gets here
      final String name = capability.getDisplayName();
      this.logger.warn("{} could not be prepared because of an unexpected failure", name, exception);
      return false;
    }
  }

  private void reportUncaughtError(final Thread thread, final Throwable error) {
    final String threadName = thread.getName();
    this.logger.error("{} ended with an error", threadName, error);
  }

  /**
   * Records the outcome of a preparation. A preparation that ends after {@link #cancel()} reports its program as
   * unavailable and leaves the guard alone, because the cancellation already forgot about the program.
   */
  private void finish(final Capability capability, final boolean succeeded) {
    final CompletableFuture<Boolean> preparation = this.getPreparation(capability);
    synchronized (this.lock) {
      final boolean loaded = this.dependencyLoader.hasCapability(capability);
      final boolean available = succeeded && loaded && !this.cancelled;
      if (!this.cancelled) {
        this.guard.markPrepared(capability, available);
      }
      preparation.complete(available);
    }
  }

  /**
   * Checks whether a capability is available right now. The programs prepared in the background are unavailable
   * until their preparation finished successfully; every other capability is answered by the loader.
   *
   * @param capability the capability
   * @return true if the capability can be used now
   */
  boolean isAvailable(final Capability capability) {
    final CompletableFuture<Boolean> preparation = this.preparations.get(capability);
    if (preparation == null) {
      return this.dependencyLoader.hasCapability(capability);
    }
    return preparation.getNow(false);
  }

  /**
   * Gets a future that completes once a capability is ready. A capability that is not prepared in the background, or
   * whose preparation already finished, gets a future that is complete already.
   *
   * @param capability the capability
   * @return a future that completes with true if the capability is available, or false if it is not
   */
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

  /**
   * Cancels the preparations that are still running: interrupts their threads, waits up to the cancel timeout for
   * them to end, reports every unfinished program as unavailable, and forgets the programs in the guard.
   */
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
    final long timeoutNanos = this.cancelTimeout.toNanos();
    final long deadline = System.nanoTime() + timeoutNanos;
    for (final Thread thread : this.threads) {
      final long now = System.nanoTime();
      final long remainingNanos = Math.max(0L, deadline - now);
      final Duration remaining = Duration.ofNanos(remainingNanos);
      final boolean ended = this.join(thread, remaining);
      if (!ended) {
        final String threadName = thread.getName();
        final long timeoutMillis = this.cancelTimeout.toMillis();
        this.logger.warn("{} did not stop within {} ms after it was cancelled", threadName, timeoutMillis);
      }
    }
  }

  private boolean join(final Thread thread, final Duration timeout) {
    try {
      return thread.join(timeout);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      return false;
    }
  }

  /**
   * Gets the threads the programs are prepared on.
   *
   * @return the installation threads, in the order they were started
   */
  @VisibleForTesting
  List<Thread> getThreads() {
    return List.copyOf(this.threads);
  }

  private CompletableFuture<Boolean> getPreparation(final Capability capability) {
    final CompletableFuture<Boolean> preparation = this.preparations.get(capability);
    return Preconditions.checkNotNull(preparation, "Every prepared capability has a future");
  }
}

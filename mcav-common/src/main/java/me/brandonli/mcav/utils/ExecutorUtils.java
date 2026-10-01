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
package me.brandonli.mcav.utils;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers for shutting down executors.
 */
public final class ExecutorUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorUtils.class);
  private static final String EXECUTOR_NOT_FINISHED = "Executor did not finish in {} ms, interrupted {} pending tasks";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private ExecutorUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Shuts down the executor, waiting up to five seconds for running tasks to finish before interrupting them. Interruption restores the caller's interrupt flag and requests immediate shutdown.
   * Failures thrown by the executor itself propagate; no second termination wait follows shutdownNow.
   *
   * @param service the executor to shut down
   * @return true if every task finished in time, false if tasks had to be interrupted or the wait was interrupted
   * @throws NullPointerException if the executor is null
   */
  public static boolean shutdownExecutorGracefully(final ExecutorService service) {
    Preconditions.checkNotNull(service, "Executor must not be null");
    return shutdownExecutorGracefully(service, DEFAULT_TIMEOUT);
  }

  /**
   * Shuts down the executor, waiting up to the timeout for running tasks to finish before interrupting them. Interruption restores the caller's interrupt flag and requests immediate shutdown.
   * Failures thrown by the executor itself propagate; no second termination wait follows shutdownNow.
   *
   * @param service the executor to shut down
   * @param timeout the non-null wait duration, converted to whole milliseconds; zero or negative values
   *                request an immediate termination check
   * @return true if every task finished in time, false if tasks had to be interrupted or the wait was interrupted
   * @throws NullPointerException if the executor or the timeout is null
   * @throws ArithmeticException if the timeout cannot be represented as a {@code long} number of milliseconds
   */
  public static boolean shutdownExecutorGracefully(final ExecutorService service, final Duration timeout) {
    Preconditions.checkNotNull(service, "Executor must not be null");
    Preconditions.checkNotNull(timeout, "Timeout must not be null");

    service.shutdown();
    try {
      final long millis = timeout.toMillis();
      final boolean terminated = service.awaitTermination(millis, TimeUnit.MILLISECONDS);
      if (terminated) {
        return true;
      }
      final List<Runnable> unfinished = service.shutdownNow();
      final int unfinishedCount = unfinished.size();
      LOGGER.warn(EXECUTOR_NOT_FINISHED, millis, unfinishedCount);
      return false;
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      service.shutdownNow();
      return false;
    }
  }
}

/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A utility class for managing {@link ExecutorService} shutdown operations in a controlled and safe manner.
 * This class is designed to prevent instances from being created and solely provides static utility methods.
 */
public final class ExecutorUtils {

  private ExecutorUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Attempts to shut down the given {@link ExecutorService} gracefully by waiting for ongoing tasks to complete.
   * If tasks cannot complete within the specified timeout, it performs a forced shutdown to terminate remaining tasks.
   * An {@link AssertionError} is thrown if the graceful or forced shutdown fails due to unresolved tasks or interruptions.
   *
   * @param service the {@link ExecutorService} instance to be shut down
   * @return {@code true} if the shutdown was completed successfully with no unresolved tasks; {@code false} otherwise
   * @throws AssertionError if unresolved tasks remain after a forced shutdown or the thread is interrupted
   */
  public static boolean shutdownExecutorGracefully(final ExecutorService service) {
    service.shutdown();
    try {
      final boolean await = service.awaitTermination(5, TimeUnit.SECONDS);
      if (!await) {
        final List<Runnable> tasks = service.shutdownNow();
        if (tasks.isEmpty()) {
          return true;
        }
        final String msg = createExecutorShutdownErrorMessage(tasks);
        throw new CriticalTaskException(msg);
      }
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt(); // yeah... we're fucked
      throw new CriticalTaskException(e.getMessage());
    }
    return false;
  }

  private static String createExecutorShutdownErrorMessage(final List<Runnable> tasks) {
    final int count = tasks.size();
    return String.format("%s tasks uncompleted!", count);
  }
}

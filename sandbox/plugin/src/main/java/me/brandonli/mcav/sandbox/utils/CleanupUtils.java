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
package me.brandonli.mcav.sandbox.utils;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import me.brandonli.mcav.utils.ThrowableUtils;

/**
 * Runs independent resource cleanup actions even when an earlier action fails.
 */
public final class CleanupUtils {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private CleanupUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Attempts every action in order, then rethrows the first recoverable failure with later failures suppressed.
   * Fatal virtual-machine failures are rethrown immediately without attempting further cleanup.
   *
   * @param actions the independent cleanup actions
   */
  public static void runAll(final Runnable... actions) {
    Preconditions.checkNotNull(actions, "Cleanup actions must not be null");
    Throwable failure = null;
    for (final Runnable action : actions) {
      try {
        action.run();
      } catch (final RuntimeException | Error exception) {
        ThrowableUtils.throwIfFatal(exception);
        if (failure == null) {
          failure = exception;
        } else {
          final boolean sameFailure = FAILURE_IDENTITY.equivalent(failure, exception);
          if (!sameFailure) {
            failure.addSuppressed(exception);
          }
        }
      }
    }
    if (failure instanceof final RuntimeException exception) {
      throw exception;
    }
    if (failure instanceof final Error error) {
      throw error;
    }
  }
}

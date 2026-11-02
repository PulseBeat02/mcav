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
package me.brandonli.mcav.browser;

import com.google.common.base.Equivalence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

// Running sessions, so a module stop ends every helper, even of players nobody released. A stop starts a new generation,
// so a session that began before it is refused even after the module started again.
final class HelperProcesses {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private static final Set<HelperSession> SESSIONS = new LinkedHashSet<>();
  private static boolean stopped;
  private static long generation;

  private HelperProcesses() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static synchronized void open() {
    stopped = false;
  }

  static synchronized long requireOpen() {
    if (stopped) {
      final String message = "The browser module is stopped!";
      throw new BrowserException(message);
    }

    return generation;
  }

  static synchronized boolean register(final HelperSession session, final long started) {
    if (stopped || started != generation) {
      return false;
    }

    SESSIONS.add(session);

    return true;
  }

  static synchronized void unregister(final HelperSession session) {
    SESSIONS.remove(session);
  }

  static synchronized int count() {
    return SESSIONS.size();
  }

  // every session ends even if the listener of another fails; the first failure is thrown once all ended
  static void closeAll() {
    final List<HelperSession> running;
    synchronized (HelperProcesses.class) {
      stopped = true;
      generation++;
      running = new ArrayList<>(SESSIONS);
    }

    RuntimeException failure = null;
    for (final HelperSession session : running) {
      try {
        session.endAndClose("The browser module was stopped!");
      } catch (final RuntimeException exception) {
        failure = combineFailures(failure, exception);
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException combineFailures(final @Nullable RuntimeException failure, final RuntimeException exception) {
    if (failure == null) {
      return exception;
    }

    if (!FAILURE_IDENTITY.equivalent(failure, exception)) {
      failure.addSuppressed(exception);
    }

    return failure;
  }
}

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.brandonli.mcav.media.player.PlayerException;

/**
 * The browser helper sessions that are running in this JVM, so that stopping the browser module ends every helper
 * process, including those of players nobody released.
 *
 * <p>Once {@link #closeAll()} has run, no session may start until {@link #open()}: a browser that was still starting,
 * for example while the browser was downloaded, is refused when its helper connects, and ends. A stop also begins a
 * new generation, so a session that began to start before it is refused even when the module has started again
 * meanwhile, as after a plugin disable and enable.
 */
final class HelperProcesses {

  private static final Set<HelperSession> SESSIONS = new LinkedHashSet<>();
  private static boolean stopped;
  private static long generation;

  private HelperProcesses() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Lets sessions start again, once the browser module starts.
   */
  static synchronized void open() {
    stopped = false;
  }

  /**
   * Checks that sessions may start, before anything is installed or launched for one.
   *
   * @return the generation of the module, which a session that starts now registers with
   * @throws PlayerException if the browser module has been stopped
   */
  static synchronized long requireOpen() {
    if (stopped) {
      throw new PlayerException("The browser module is stopped");
    }
    return generation;
  }

  /**
   * Remembers a running session, unless the browser module has been stopped since the session began to start.
   *
   * @param session the session
   * @param started the generation {@link #requireOpen()} gave when the session began to start
   * @return true if the session is remembered, false if it must end because the module has been stopped since
   */
  static synchronized boolean register(final HelperSession session, final long started) {
    if (stopped || started != generation) {
      return false;
    }
    SESSIONS.add(session);
    return true;
  }

  /**
   * Forgets a session that is closing.
   *
   * @param session the session
   */
  static synchronized void deregister(final HelperSession session) {
    SESSIONS.remove(session);
  }

  /**
   * Counts the running sessions.
   *
   * @return the number of sessions
   */
  static synchronized int count() {
    return SESSIONS.size();
  }

  /**
   * Ends every running session, which its player hears of, and lets no session start until {@link #open()}. Every
   * session ends even if the listener of another one fails, and the first such failure is thrown once all have ended.
   */
  static void closeAll() {
    final List<HelperSession> running;
    synchronized (HelperProcesses.class) {
      stopped = true;
      generation++;
      running = new ArrayList<>(SESSIONS);
    }
    // every helper ends, also when the listener of one fails; the first failure is thrown afterwards
    RuntimeException failure = null;
    for (final HelperSession session : running) {
      try {
        session.endAndClose("The browser module was stopped");
      } catch (final RuntimeException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}

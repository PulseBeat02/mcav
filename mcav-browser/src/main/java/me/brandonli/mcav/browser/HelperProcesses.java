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

/**
 * The browser helper sessions that are running in this JVM, so that stopping the browser module ends every helper
 * process, including those of players nobody released.
 */
final class HelperProcesses {

  private static final Set<HelperSession> SESSIONS = new LinkedHashSet<>();

  private HelperProcesses() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Remembers a running session.
   *
   * @param session the session
   */
  static synchronized void register(final HelperSession session) {
    SESSIONS.add(session);
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
   * Closes every running session.
   */
  static void closeAll() {
    final List<HelperSession> running;
    synchronized (HelperProcesses.class) {
      running = new ArrayList<>(SESSIONS);
    }
    for (final HelperSession session : running) {
      session.close();
    }
  }
}

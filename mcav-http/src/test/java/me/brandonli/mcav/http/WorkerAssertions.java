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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Relays failures from fixture callbacks to the JUnit thread after every callback has finished. */
final class WorkerAssertions {

  private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
  private final List<Throwable> failures = new CopyOnWriteArrayList<>();

  void observeCurrentThread() {
    final Thread worker = Thread.currentThread();
    worker.setUncaughtExceptionHandler((_, failure) -> this.failures.add(failure));
    this.workers.add(worker);
  }

  void assertFinished() throws InterruptedException {
    for (final Thread worker : this.workers) {
      worker.join(10_000L);
      assertFalse(worker.isAlive(), "the callback worker finished after fixture cleanup");
    }
    assertEquals(List.of(), this.failures, "worker assertions reach the JUnit thread");
  }
}

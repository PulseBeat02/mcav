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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static java.util.Objects.requireNonNull;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("all") // checker
final class PhantomDebug {

  private PhantomDebug() {}

  private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
  private static final Map<PhantomReference<?>, String> NAMES = new ConcurrentHashMap<>();

  static {
    final Thread watcher = new Thread(
      () -> {
        try {
          while (true) {
            @SuppressWarnings("unchecked")
            final PhantomReference<Object> ref = (PhantomReference<Object>) QUEUE.remove();
            final String id = NAMES.remove(ref);
            System.err.println("[PhantomDebug] GC enqueued: " + id);
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      },
      "PhantomDebug-Watcher"
    );
    watcher.setDaemon(true);
    watcher.start();
  }

  static <T> T watch(final T obj) {
    requireNonNull(obj);
    final Class<?> clazz = obj.getClass();
    final String simpleName = clazz.getSimpleName();
    final int hashCode = System.identityHashCode(obj);
    final String name = simpleName + "@" + Integer.toHexString(hashCode);
    return watch(obj, name);
  }

  static <T> T watch(final T obj, final String name) {
    requireNonNull(obj);
    requireNonNull(name);
    final PhantomReference<T> pr = new PhantomReference<>(obj, QUEUE);
    NAMES.put(pr, name);
    return obj;
  }
}

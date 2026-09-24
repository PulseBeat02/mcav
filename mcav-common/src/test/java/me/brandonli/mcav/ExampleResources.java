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

import com.google.common.base.Equivalence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Owns the three interactive examples' resources during setup, playback and JVM shutdown. */
final class ExampleResources implements AutoCloseable {

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private final Deque<Runnable> releases = new ArrayDeque<>();
  private final Runtime runtime = Runtime.getRuntime();
  private final Thread shutdownHook;
  private boolean closed;
  private @Nullable Thread closingThread;
  private final CompletableFuture<Void> completion = new CompletableFuture<>();

  ExampleResources() {
    this.shutdownHook = new Thread(this::close, "mcav-example-shutdown");
    this.runtime.addShutdownHook(this.shutdownHook);
  }

  void add(final Runnable release) {
    synchronized (this) {
      if (!this.closed) {
        this.releases.addFirst(release);
        return;
      }
    }
    // Shutdown may race a just-completed acquisition; never leave that new resource without an owner.
    release.run();
    throw new IllegalStateException("The example has already closed");
  }

  @Override
  public void close() {
    final Thread current = Thread.currentThread();
    @Nullable final List<Runnable> pending;
    final boolean wait;
    synchronized (this) {
      if (this.closed) {
        pending = null;
        wait = !IDENTITY.equivalent(this.closingThread, current);
      } else {
        this.closed = true;
        this.closingThread = current;
        pending = new ArrayList<>(this.releases);
        this.releases.clear();
        wait = false;
      }
    }
    if (pending == null) {
      if (wait) {
        // A shutdown hook must not finish while another thread is still releasing native resources.
        this.completion.join();
      }
      return;
    }
    try {
      releaseAll(pending);
    } finally {
      try {
        this.runtime.removeShutdownHook(this.shutdownHook);
      } catch (final IllegalStateException shutdownInProgress) {
        // Hooks cannot be removed after JVM shutdown has begun.
      } finally {
        this.completion.complete(null);
      }
    }
  }

  private static void releaseAll(final List<Runnable> releases) {
    Throwable first = null;
    for (final Runnable release : releases) {
      try {
        release.run();
      } catch (final RuntimeException | Error failure) {
        ThrowableUtils.throwIfFatal(failure);
        if (first == null) {
          first = failure;
        } else if (!IDENTITY.equivalent(first, failure)) {
          first.addSuppressed(failure);
        }
      }
    }
    if (first instanceof Error error) {
      throw error;
    }
    if (first instanceof RuntimeException exception) {
      throw exception;
    }
  }
}

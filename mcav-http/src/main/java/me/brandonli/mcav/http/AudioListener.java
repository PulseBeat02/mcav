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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

final class AudioListener {

  private static final String LISTENER_THREAD_PREFIX = "mcav-http-listener-";
  private static final String CLOSING_THREAD_PREFIX = "mcav-http-close-";

  private final WebSocketSession session;
  private final long sendTimeLimitNanoseconds;
  private final int bufferLimitBytes;
  private final Consumer<AudioListener> failureHandler;
  private final LongSupplier nanosecondClock;
  private final Deque<byte[]> pending;
  private final ReentrantLock lock;
  private final Condition changed;

  private int pendingBytes;
  private long sendStartNanoseconds;
  private boolean sending;
  private boolean stopped;

  AudioListener(
    final WebSocketSession session,
    final long sendTimeLimitMilliseconds,
    final int bufferLimitBytes,
    final Consumer<AudioListener> failureHandler
  ) {
    this(session, sendTimeLimitMilliseconds, bufferLimitBytes, failureHandler, System::nanoTime);
  }

  @VisibleForTesting
  AudioListener(
    final WebSocketSession session,
    final long sendTimeLimitMilliseconds,
    final int bufferLimitBytes,
    final Consumer<AudioListener> failureHandler,
    final LongSupplier nanosecondClock
  ) {
    this.session = session;
    this.sendTimeLimitNanoseconds = TimeUnit.MILLISECONDS.toNanos(sendTimeLimitMilliseconds);
    this.bufferLimitBytes = bufferLimitBytes;
    this.failureHandler = failureHandler;
    this.nanosecondClock = nanosecondClock;
    this.pending = new ArrayDeque<>();
    this.lock = new ReentrantLock();
    this.changed = this.lock.newCondition();
  }

  Thread start() {
    final String id = this.session.getId();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    builder.name(LISTENER_THREAD_PREFIX + id);
    return builder.start(this::sendPending);
  }

  void stop() {
    this.lock.lock();
    try {
      this.stopped = true;
      this.pending.clear();
      this.pendingBytes = 0;
      this.changed.signalAll();
    } finally {
      this.lock.unlock();
    }
  }

  private void sendPending() {
    byte[] chunk = this.takeNext();
    while (chunk != null) {
      final BinaryMessage message = new BinaryMessage(chunk);
      try {
        this.session.sendMessage(message);
      } catch (final IOException | RuntimeException exception) {
        this.stop();
        this.failureHandler.accept(this);
        return;
      }
      chunk = this.takeNext();
    }
  }

  private byte @Nullable [] takeNext() {
    this.lock.lock();
    try {
      this.sending = false;
      while (this.pending.isEmpty() && !this.stopped) {
        // interrupts are ignored: one in the middle of a write would leave the connection in an unknown state
        this.changed.awaitUninterruptibly();
      }

      if (this.stopped) {
        return null;
      }

      final byte[] chunk = this.pending.removeFirst();
      this.pendingBytes -= chunk.length;
      this.sending = true;
      this.sendStartNanoseconds = this.nanosecondClock.getAsLong();
      return chunk;
    } finally {
      this.lock.unlock();
    }
  }

  boolean offer(final byte[] chunk) {
    final boolean open = this.session.isOpen();
    if (!open) {
      return false;
    }

    this.lock.lock();
    try {
      if (this.stopped || this.isStuck()) {
        return false;
      }

      this.queue(chunk);

      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private boolean isStuck() {
    if (!this.sending) {
      return false;
    }

    final long now = this.nanosecondClock.getAsLong();
    final long elapsed = now - this.sendStartNanoseconds;
    return elapsed > this.sendTimeLimitNanoseconds;
  }

  private void queue(final byte[] chunk) {
    this.pending.addLast(chunk);
    this.pendingBytes += chunk.length;
    this.dropOldestOverTheLimit();
    this.changed.signalAll();
  }

  private void dropOldestOverTheLimit() {
    while (this.pendingBytes > this.bufferLimitBytes && this.pending.size() > 1) {
      final byte[] oldest = this.pending.removeFirst();
      this.pendingBytes -= oldest.length;
    }
  }

  Thread closeAsync(final CloseStatus status) {
    this.stop();

    // closing waits for a blocked write, so it runs on its own thread
    final String id = this.session.getId();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    builder.name(CLOSING_THREAD_PREFIX + id);
    return builder.start(() -> this.closeQuietly(this.session, status));
  }

  private void closeQuietly(final WebSocketSession session, final CloseStatus status) {
    try {
      session.close(status);
    } catch (final IOException | RuntimeException exception) {
      // the connection is already gone
    }
  }

  WebSocketSession getSession() {
    return this.session;
  }

  @VisibleForTesting
  int getPendingBytes() {
    this.lock.lock();
    try {
      return this.pendingBytes;
    } finally {
      this.lock.unlock();
    }
  }

  @VisibleForTesting
  int getPendingChunkCount() {
    this.lock.lock();
    try {
      return this.pending.size();
    } finally {
      this.lock.unlock();
    }
  }
}

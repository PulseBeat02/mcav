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

/**
 * One browser connected to the audio socket.
 *
 * <p>The audio thread only queues samples with {@link #offer(byte[])}; a virtual thread of the listener writes them
 * to the connection in order. A slow connection therefore never stalls the pipeline or the other listeners: when
 * more than the buffer limit is waiting, the oldest chunks are dropped, and a listener whose current write has been
 * blocked for longer than the send time limit is reported as unreliable so that it can be disconnected.
 *
 * <p>The writing thread waits for new samples without reacting to interrupts, because an interrupt in the middle of a
 * write would leave the connection in an unknown state. {@link #stop()} is therefore the only way to end it, and every
 * path that drops a listener calls it.
 */
final class AudioListener {

  private final WebSocketSession session;
  private final long sendTimeLimitNanos;
  private final int bufferLimitBytes;
  private final Consumer<AudioListener> failureHandler;
  private final LongSupplier nanoClock;
  private final Deque<byte[]> pending;
  private final ReentrantLock lock;
  private final Condition changed;

  private int pendingBytes;
  private long sendStartNanos;
  private boolean sending;
  private boolean stopped;

  /**
   * Creates a listener. Call {@link #start()} to begin sending.
   *
   * @param session             the WebSocket session of the browser
   * @param sendTimeLimitMillis how long one write may block before the listener counts as unreliable
   * @param bufferLimitBytes    how many bytes may wait before the oldest chunks are dropped
   * @param failureHandler      called on the sender thread when a write fails
   */
  AudioListener(
    final WebSocketSession session,
    final long sendTimeLimitMillis,
    final int bufferLimitBytes,
    final Consumer<AudioListener> failureHandler
  ) {
    this(session, sendTimeLimitMillis, bufferLimitBytes, failureHandler, System::nanoTime);
  }

  /**
   * Creates a listener that reads the time from the specified clock, so that a test can decide how long a write has
   * been running. Visible for testing.
   *
   * @param session             the WebSocket session of the browser
   * @param sendTimeLimitMillis how long one write may block before the listener counts as unreliable
   * @param bufferLimitBytes    how many bytes may wait before the oldest chunks are dropped
   * @param failureHandler      called on the sender thread when a write fails
   * @param nanoClock           the clock the send time limit is measured with, in nanoseconds
   */
  @VisibleForTesting
  AudioListener(
    final WebSocketSession session,
    final long sendTimeLimitMillis,
    final int bufferLimitBytes,
    final Consumer<AudioListener> failureHandler,
    final LongSupplier nanoClock
  ) {
    this.session = session;
    this.sendTimeLimitNanos = TimeUnit.MILLISECONDS.toNanos(sendTimeLimitMillis);
    this.bufferLimitBytes = bufferLimitBytes;
    this.failureHandler = failureHandler;
    this.nanoClock = nanoClock;
    this.pending = new ArrayDeque<>();
    this.lock = new ReentrantLock();
    this.changed = this.lock.newCondition();
  }

  /**
   * Starts the virtual thread that writes the queued chunks. Call it once.
   *
   * @return the sender thread
   */
  Thread start() {
    final String id = this.session.getId();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    builder.name("mcav-http-listener-" + id);
    return builder.start(this::sendPending);
  }

  /**
   * Gets the WebSocket session of the browser.
   *
   * @return the session
   */
  WebSocketSession getSession() {
    return this.session;
  }

  /**
   * Queues a chunk of samples without blocking. When more than the buffer limit is waiting afterwards, the oldest
   * chunks are dropped, but the newest chunk is always kept.
   *
   * @param chunk the samples; the array must not be changed afterwards
   * @return false if the listener is closed, stopped, or its current write is blocked for longer than the send
   * time limit, in which case the chunk was not queued and the listener should be disconnected
   */
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
      this.pending.addLast(chunk);
      this.pendingBytes += chunk.length;
      while (this.pendingBytes > this.bufferLimitBytes && this.pending.size() > 1) {
        final byte[] oldest = this.pending.removeFirst();
        this.pendingBytes -= oldest.length;
      }
      this.changed.signalAll();
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private boolean isStuck() {
    if (!this.sending) {
      return false;
    }
    // takeNext starts the clock under this lock right before it hands the chunk to the write, so a write that never
    // returns is measured from the moment it began
    final long now = this.nanoClock.getAsLong();
    final long elapsed = now - this.sendStartNanos;
    return elapsed > this.sendTimeLimitNanos;
  }

  /**
   * Gets the number of bytes waiting to be written, not counting a write in progress.
   *
   * @return the queued byte count
   */
  @VisibleForTesting
  int getPendingBytes() {
    this.lock.lock();
    try {
      return this.pendingBytes;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Gets the number of chunks waiting to be written, not counting a write in progress.
   *
   * @return the queued chunk count
   */
  @VisibleForTesting
  int getPendingChunkCount() {
    this.lock.lock();
    try {
      return this.pending.size();
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Stops sending and drops the queued chunks without closing the session, for example because the browser
   * already disconnected. A write in progress is finished first.
   */
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

  /**
   * Stops sending and closes the session on another thread, because closing waits for a blocked write.
   *
   * @param status the close status sent to the browser
   * @return the thread that closes the session
   */
  Thread closeAsync(final CloseStatus status) {
    this.stop();
    final String id = this.session.getId();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    builder.name("mcav-http-close-" + id);
    return builder.start(() -> closeQuietly(this.session, status));
  }

  private static void closeQuietly(final WebSocketSession session, final CloseStatus status) {
    try {
      session.close(status);
    } catch (final IOException | RuntimeException exception) {
      // the connection is already gone
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

  private byte@Nullable[] takeNext() {
    this.lock.lock();
    try {
      this.sending = false;
      while (this.pending.isEmpty() && !this.stopped) {
        this.changed.awaitUninterruptibly();
      }
      if (this.stopped) {
        return null;
      }
      final byte[] chunk = this.pending.removeFirst();
      this.pendingBytes -= chunk.length;
      this.sending = true;
      this.sendStartNanos = this.nanoClock.getAsLong();
      return chunk;
    } finally {
      this.lock.unlock();
    }
  }
}

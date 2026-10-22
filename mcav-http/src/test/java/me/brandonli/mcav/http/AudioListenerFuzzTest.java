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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.LockSupport;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Tag;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Fuzzes the queue of one browser of the audio page against a model of what {@link AudioListener} documents, with a
 * connection whose writes finish only when the fuzzer says and a clock the fuzzer moves. For every sequence of chunks,
 * finished writes, passing time, closed connections and stops: a chunk is queued exactly when the connection is open,
 * the listener runs and its current write is not stuck for longer than the send time limit; when more than the buffer
 * limit waits, the oldest chunks are dropped and the newest is kept; and the browser receives the chunks that were
 * kept, once and in order.
 */
@Tag("fuzz")
final class AudioListenerFuzzTest {

  private static final long WAIT_SECONDS = 10;

  private static final int MAXIMUM_LIMIT_BYTES = 4096;
  private static final long MAXIMUM_SEND_TIME_LIMIT_MILLISECONDS = 10_000;
  private static final int MAXIMUM_OPERATIONS = 40;
  private static final int MAXIMUM_CHUNK_TO_LIMIT_RATIO = 2;
  private static final long MAXIMUM_CLOCK_STEP_NANOSECONDS = 20_000_000_000L;

  private static final int OFFER_CHUNK = 0;
  private static final int FINISH_WRITE = 1;
  private static final int ADVANCE_CLOCK = 2;
  private static final int STOP_LISTENER = 3;
  private static final int CLOSE_SESSION = 4;

  private static final int REMAINING_WRITE_PERMITS = 1_000;

  @FuzzTest(maxDuration = "30s")
  void shouldQueueDropAndSendAsDocumented(final FuzzedDataProvider data) throws Exception {
    final int limit = data.consumeInt(1, MAXIMUM_LIMIT_BYTES);
    final long sendTimeLimitMilliseconds = data.consumeLong(1, MAXIMUM_SEND_TIME_LIMIT_MILLISECONDS);
    final ListenerModel model = new ListenerModel(limit, sendTimeLimitMilliseconds);
    try {
      this.runOperations(data, model);
    } finally {
      model.stopAndAwaitTheSender();
    }

    model.assertDelivered();
  }

  private void runOperations(final FuzzedDataProvider data, final ListenerModel model) throws InterruptedException {
    final int operations = data.consumeInt(0, MAXIMUM_OPERATIONS);
    for (int operation = 0; operation < operations; operation++) {
      this.runOperation(data, model);
      model.assertPending();
    }
  }

  private void runOperation(final FuzzedDataProvider data, final ListenerModel model) throws InterruptedException {
    final int code = data.consumeInt(OFFER_CHUNK, CLOSE_SESSION);
    switch (code) {
      case OFFER_CHUNK -> model.offerChunk(data);
      case FINISH_WRITE -> model.finishWrite();
      case ADVANCE_CLOCK -> model.advanceClock(data);
      case STOP_LISTENER -> model.stop();
      default -> model.closeSession();
    }
  }

  private static final class ListenerModel {

    private final int limit;
    private final long sendTimeLimitNanoseconds;
    private final AtomicLong clock;
    private final GatedSession gate;
    private final AudioListener listener;
    private final Thread sender;
    private final Deque<byte[]> queued;
    private final List<byte[]> expectedDeliveries;

    private byte @Nullable [] inFlight;
    private long sendStart;
    private long queuedBytes;
    private boolean stopped;
    private int chunkNumber;

    ListenerModel(final int limit, final long sendTimeLimitMilliseconds) {
      this.limit = limit;
      this.sendTimeLimitNanoseconds = TimeUnit.MILLISECONDS.toNanos(sendTimeLimitMilliseconds);
      this.clock = new AtomicLong();
      this.gate = new GatedSession();
      this.listener = new AudioListener(this.gate.session, sendTimeLimitMilliseconds, limit, _ -> {}, this.clock::get);
      this.sender = this.listener.start();
      this.queued = new ArrayDeque<>();
      this.expectedDeliveries = new ArrayList<>();
    }

    void offerChunk(final FuzzedDataProvider data) throws InterruptedException {
      this.chunkNumber++;
      final int length = data.consumeInt(0, MAXIMUM_CHUNK_TO_LIMIT_RATIO * this.limit);
      final byte[] chunk = new byte[length];
      Arrays.fill(chunk, (byte) this.chunkNumber);

      final boolean expected = this.isQueueingChunks();
      final boolean accepted = this.listener.offer(chunk);
      assertEquals(expected, accepted, "whether a chunk is queued");
      if (accepted) {
        this.queue(chunk);
      }
    }

    private boolean isQueueingChunks() {
      final boolean stuck = this.inFlight != null && this.clock.get() - this.sendStart > this.sendTimeLimitNanoseconds;
      final boolean open = this.gate.open.get();
      return open && !this.stopped && !stuck;
    }

    private void queue(final byte[] chunk) throws InterruptedException {
      this.queued.addLast(chunk);
      this.queuedBytes += chunk.length;
      this.dropOldestOverTheLimit();
      if (this.inFlight == null) {
        this.sendNext();
      }
    }

    private void dropOldestOverTheLimit() {
      while (this.queuedBytes > this.limit && this.queued.size() > 1) {
        final byte[] dropped = this.queued.removeFirst();
        this.queuedBytes -= dropped.length;
      }
    }

    private void sendNext() throws InterruptedException {
      this.gate.awaitWrite();

      final byte[] next = this.queued.removeFirst();
      this.inFlight = next;
      this.queuedBytes -= next.length;
      this.sendStart = this.clock.get();
      this.expectedDeliveries.add(next);
    }

    void finishWrite() throws InterruptedException {
      if (this.inFlight == null) {
        return;
      }

      this.gate.finishWrite();
      this.inFlight = null;
      if (this.stopped || this.queued.isEmpty()) {
        this.awaitIdleOrEnded();
        return;
      }

      this.sendNext();
    }

    /**
     * Waits until the sender waits for its next chunk, or has ended. Its state alone does not tell: a virtual thread that
     * is being woken up from the write it just finished reports itself as waiting for a moment, while the listener still
     * counts that write as the current one, which is what the fuzzer found the first time. Only a sender that waits on
     * the condition of the listener has taken up waiting for the next chunk.
     */
    private void awaitIdleOrEnded() {
      final long start = System.nanoTime();
      final long timeout = TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
      final long deadline = start + timeout;
      while (System.nanoTime() < deadline) {
        final Thread.State state = this.sender.getState();
        final Object blocker = LockSupport.getBlocker(this.sender);
        final boolean waitingForAChunk = state == Thread.State.WAITING && blocker instanceof AbstractQueuedSynchronizer.ConditionObject;
        if (waitingForAChunk || state == Thread.State.TERMINATED) {
          return;
        }

        Thread.onSpinWait();
      }

      final String message = "The sender neither waited for a chunk nor ended!";
      throw new AssertionError(message);
    }

    void advanceClock(final FuzzedDataProvider data) {
      final long step = data.consumeLong(0, MAXIMUM_CLOCK_STEP_NANOSECONDS);
      this.clock.addAndGet(step);
    }

    void stop() {
      this.listener.stop();
      this.stopped = true;
      this.queued.clear();
      this.queuedBytes = 0;
    }

    void closeSession() {
      this.gate.open.set(false);
    }

    void assertPending() {
      final int pendingBytes = this.listener.getPendingBytes();
      final int pendingChunks = this.listener.getPendingChunkCount();
      assertEquals(this.queuedBytes, pendingBytes, "bytes waiting");

      final int queuedChunks = this.queued.size();
      assertEquals(queuedChunks, pendingChunks, "chunks waiting");
    }

    void stopAndAwaitTheSender() throws InterruptedException {
      this.listener.stop();
      this.gate.finishAll();

      final long waitMilliseconds = TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
      this.sender.join(waitMilliseconds);
    }

    void assertDelivered() {
      final boolean ended = !this.sender.isAlive();
      assertTrue(ended, "the sender ends once the listener is stopped");

      final List<byte[]> delivered = this.gate.written();
      final int expectedCount = this.expectedDeliveries.size();
      final int deliveredCount = delivered.size();
      assertEquals(expectedCount, deliveredCount, "the number of chunks the browser received");
      for (int index = 0; index < deliveredCount; index++) {
        final byte[] expectedChunk = this.expectedDeliveries.get(index);
        final byte[] deliveredChunk = delivered.get(index);
        assertArrayEquals(expectedChunk, deliveredChunk, "the chunks the browser received, in order");
      }
    }
  }

  /**
   * A connection whose writes block until they are finished, one at a time, and which records what was written. It is
   * a plain proxy rather than a mock: a mock records every call, and one created per input kept the chunks of every
   * input alive until a long fuzzing run ran out of heap.
   */
  private static final class GatedSession implements InvocationHandler {

    private final WebSocketSession session;
    private final AtomicBoolean open;
    private final Semaphore writing;
    private final Semaphore finished;
    private final List<byte[]> written;

    GatedSession() {
      this.open = new AtomicBoolean(true);
      this.writing = new Semaphore(0);
      this.finished = new Semaphore(0);
      this.written = new ArrayList<>();
      final ClassLoader loader = WebSocketSession.class.getClassLoader();
      final Class<?>[] interfaces = { WebSocketSession.class };
      this.session = (WebSocketSession) Proxy.newProxyInstance(loader, interfaces, this);
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] arguments) {
      final String name = method.getName();
      return switch (name) {
        case "getId" -> "fuzz";
        case "isOpen" -> this.open.get();
        case "sendMessage" -> {
          this.write(arguments[0]);
          yield null;
        }
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> false;
        case "toString" -> "GatedSession";
        default -> {
          final String message = "Method %s is never called by the listener!".formatted(name);
          throw new UnsupportedOperationException(message);
        }
      };
    }

    /**
     * Records a chunk and blocks until the fuzzer finishes the write.
     */
    private void write(final Object message) {
      final BinaryMessage binary = (BinaryMessage) message;
      final ByteBuffer payload = binary.getPayload();
      final ByteBuffer copy = payload.duplicate();
      final byte[] bytes = new byte[copy.remaining()];
      copy.get(bytes);
      synchronized (this.written) {
        this.written.add(bytes);
      }
      this.writing.release();
      this.finished.acquireUninterruptibly();
    }

    private void awaitWrite() throws InterruptedException {
      final boolean started = this.writing.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started, "the sender did not start writing the chunk it had to take");
    }

    private void finishWrite() {
      this.finished.release();
    }

    private void finishAll() {
      this.finished.release(REMAINING_WRITE_PERMITS);
    }

    List<byte[]> written() {
      synchronized (this.written) {
        return new ArrayList<>(this.written);
      }
    }
  }
}

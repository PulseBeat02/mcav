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

  @FuzzTest(maxDuration = "30s")
  void queuesDropsAndSendsAsDocumented(final FuzzedDataProvider data) throws Exception {
    final int limit = data.consumeInt(1, 4096);
    final long sendTimeLimitMillis = data.consumeLong(1, 10_000);
    final long sendTimeLimitNanos = TimeUnit.MILLISECONDS.toNanos(sendTimeLimitMillis);
    final AtomicLong clock = new AtomicLong();
    final GatedSession gate = new GatedSession();
    final AudioListener listener = new AudioListener(gate.session, sendTimeLimitMillis, limit, _ -> {}, clock::get);
    final Thread sender = listener.start();

    final Deque<byte[]> queued = new ArrayDeque<>();
    final List<byte[]> expectedDeliveries = new ArrayList<>();
    byte[] inFlight = null;
    long sendStart = 0;
    long queuedBytes = 0;
    boolean stopped = false;
    int chunkNumber = 0;
    try {
      final int operations = data.consumeInt(0, 40);
      for (int operation = 0; operation < operations; operation++) {
        switch (data.consumeInt(0, 4)) {
          case 0 -> {
            chunkNumber++;
            final byte[] chunk = new byte[data.consumeInt(0, 2 * limit)];
            Arrays.fill(chunk, (byte) chunkNumber);
            final boolean stuck = inFlight != null && clock.get() - sendStart > sendTimeLimitNanos;
            final boolean expected = gate.open.get() && !stopped && !stuck;
            final boolean accepted = listener.offer(chunk);
            assertEquals(expected, accepted, "whether a chunk is queued");
            if (accepted) {
              queued.addLast(chunk);
              queuedBytes += chunk.length;
              while (queuedBytes > limit && queued.size() > 1) {
                final byte[] dropped = queued.removeFirst();
                queuedBytes -= dropped.length;
              }
              if (inFlight == null) {
                // the idle sender takes the oldest chunk and starts writing it
                gate.awaitWrite();
                inFlight = queued.removeFirst();
                queuedBytes -= inFlight.length;
                sendStart = clock.get();
                expectedDeliveries.add(inFlight);
              }
            }
          }
          case 1 -> {
            if (inFlight != null) {
              gate.finishWrite();
              inFlight = null;
              if (!stopped && !queued.isEmpty()) {
                gate.awaitWrite();
                inFlight = queued.removeFirst();
                queuedBytes -= inFlight.length;
                sendStart = clock.get();
                expectedDeliveries.add(inFlight);
              } else {
                awaitIdleOrEnded(sender);
              }
            }
          }
          case 2 -> clock.addAndGet(data.consumeLong(0, 20_000_000_000L));
          case 3 -> {
            listener.stop();
            stopped = true;
            queued.clear();
            queuedBytes = 0;
          }
          default -> gate.open.set(false);
        }
        final int pendingBytes = listener.getPendingBytes();
        final int pendingChunks = listener.getPendingChunkCount();
        assertEquals(queuedBytes, pendingBytes, "bytes waiting");
        assertEquals(queued.size(), pendingChunks, "chunks waiting");
      }
    } finally {
      listener.stop();
      gate.finishAll();
      sender.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }
    final boolean ended = !sender.isAlive();
    assertTrue(ended, "the sender ends once the listener is stopped");
    final List<byte[]> delivered = gate.written();
    assertEquals(expectedDeliveries.size(), delivered.size(), "the number of chunks the browser received");
    for (int index = 0; index < delivered.size(); index++) {
      assertArrayEquals(expectedDeliveries.get(index), delivered.get(index), "the chunks the browser received, in order");
    }
  }

  /**
   * Waits until the sender waits for its next chunk, or has ended. Its state alone does not tell: a virtual thread that
   * is being woken up from the write it just finished reports itself as waiting for a moment, while the listener still
   * counts that write as the current one, which is what the fuzzer found the first time. Only a sender that waits on
   * the condition of the listener has taken up waiting for the next chunk.
   */
  private static void awaitIdleOrEnded(final Thread sender) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Thread.State state = sender.getState();
      final Object blocker = LockSupport.getBlocker(sender);
      final boolean waitingForAChunk = state == Thread.State.WAITING && blocker instanceof AbstractQueuedSynchronizer.ConditionObject;
      if (waitingForAChunk || state == Thread.State.TERMINATED) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the sender neither waited for a chunk nor ended");
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
        // the listener never compares sessions
        case "equals" -> false;
        case "toString" -> "GatedSession";
        default -> throw new UnsupportedOperationException("The listener does not call " + name);
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

    void awaitWrite() throws InterruptedException {
      final boolean started = this.writing.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started, "the sender did not start writing the chunk it had to take");
    }

    void finishWrite() {
      this.finished.release();
    }

    void finishAll() {
      this.finished.release(1_000);
    }

    List<byte[]> written() {
      synchronized (this.written) {
        return new ArrayList<>(this.written);
      }
    }
  }
}

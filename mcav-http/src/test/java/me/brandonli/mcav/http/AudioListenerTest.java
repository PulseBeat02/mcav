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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Stubber;
import org.mockito.verification.VerificationMode;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests {@link AudioListener}.
 */
final class AudioListenerTest {

  private static final long TIMEOUT_SECONDS = 10;
  private static final long TIMEOUT_MILLISECONDS = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);

  private static final long SEND_TIME_LIMIT_MILLISECONDS = 1000;
  private static final long SHORT_SEND_TIME_LIMIT_MILLISECONDS = 50;

  private static final long SILENCE_MILLISECONDS = 100;

  private static final int BUFFER_LIMIT_BYTES = 1024;
  private static final int SMALL_BUFFER_LIMIT_BYTES = 250;

  private static final int SMALL_CHUNK_BYTES = 10;
  private static final int MEDIUM_CHUNK_BYTES = 100;

  private static final String SESSION_ID = "listener-1";

  private WorkerAssertions workers;
  private List<AudioListener> listeners;

  private WebSocketSession session;
  private BlockingQueue<byte[]> sent;
  private CountDownLatch firstSendEntered;
  private CountDownLatch releaseSends;
  private CompletableFuture<AudioListener> failure;

  @BeforeEach
  void setUp() {
    this.workers = new WorkerAssertions();
    this.listeners = new ArrayList<>();
    this.session = Mockito.mock(WebSocketSession.class);
    this.sent = new LinkedBlockingQueue<>();
    this.firstSendEntered = new CountDownLatch(1);
    this.releaseSends = new CountDownLatch(1);
    this.failure = new CompletableFuture<>();
  }

  @AfterEach
  void releaseBlockedSenders() throws InterruptedException {
    this.releaseSends.countDown();
    this.listeners.forEach(AudioListener::stop);
    this.workers.assertFinished();
  }

  @Test
  void shouldReleaseTheLockNeededByOtherProducersAfterInspectingTheQueueDepth() throws Exception {
    final AudioListener listener = this.createListener();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    final boolean queued = listener.offer(first);
    assertTrue(queued);

    final int pending = listener.getPendingChunkCount();
    assertEquals(1, pending);

    final ReentrantLock lock = this.readLock(listener);
    final boolean held = lock.isHeldByCurrentThread();
    assertFalse(held, "reading queue depth must not retain the lock needed by other producer and sender threads");

    final CompletableFuture<Boolean> offered = new CompletableFuture<>();
    final byte[] second = this.chunk(20, 2);
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    final Thread producer = builder.start(() -> this.offerAndReport(listener, second, offered));
    final boolean accepted = offered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    producer.join(TIMEOUT_MILLISECONDS);
    final boolean alive = producer.isAlive();
    final int bytes = listener.getPendingBytes();
    final int expectedBytes = first.length + second.length;
    assertTrue(accepted);
    assertFalse(alive);
    assertEquals(expectedBytes, bytes, "a different producer can still append samples after a queue-depth query");

    listener.stop();
  }

  private AudioListener createListener() {
    return this.createListener(SEND_TIME_LIMIT_MILLISECONDS, BUFFER_LIMIT_BYTES);
  }

  private AudioListener createListener(final long sendTimeLimitMilliseconds, final int bufferLimitBytes) {
    Mockito.when(this.session.getId()).thenReturn(SESSION_ID);
    Mockito.when(this.session.isOpen()).thenReturn(true);

    final AudioListener listener = new AudioListener(this.session, sendTimeLimitMilliseconds, bufferLimitBytes, this.failure::complete);
    this.listeners.add(listener);

    return listener;
  }

  private AudioListener createListener(
    final long sendTimeLimitMilliseconds,
    final int bufferLimitBytes,
    final LongSupplier nanosecondClock
  ) {
    Mockito.when(this.session.getId()).thenReturn(SESSION_ID);
    Mockito.when(this.session.isOpen()).thenReturn(true);

    final AudioListener listener = new AudioListener(
      this.session,
      sendTimeLimitMilliseconds,
      bufferLimitBytes,
      this.failure::complete,
      nanosecondClock
    );
    this.listeners.add(listener);

    return listener;
  }

  private byte[] chunk(final int size, final int value) {
    final byte[] bytes = new byte[size];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private ReentrantLock readLock(final AudioListener listener) throws ReflectiveOperationException {
    final Field field = AudioListener.class.getDeclaredField("lock");
    field.setAccessible(true);
    return (ReentrantLock) field.get(listener);
  }

  private void offerAndReport(final AudioListener listener, final byte[] chunk, final CompletableFuture<Boolean> offered) {
    try {
      final boolean accepted = listener.offer(chunk);
      offered.complete(accepted);
    } catch (final RuntimeException | Error exception) {
      offered.completeExceptionally(exception);
    }
  }

  @Test
  void shouldExposeItsSession() {
    final AudioListener listener = this.createListener();
    final WebSocketSession listenerSession = listener.getSession();
    assertSame(this.session, listenerSession);
  }

  @Test
  void shouldSendQueuedChunksInOrderAsBinaryMessages() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener();
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    final byte[] second = this.chunk(20, 2);
    final byte[] third = this.chunk(30, 3);
    final boolean firstQueued = listener.offer(first);
    final boolean secondQueued = listener.offer(second);
    final boolean thirdQueued = listener.offer(third);
    assertTrue(firstQueued);
    assertTrue(secondQueued);
    assertTrue(thirdQueued);

    final byte[] firstSent = this.nextSent();
    final byte[] secondSent = this.nextSent();
    final byte[] thirdSent = this.nextSent();
    assertArrayEquals(first, firstSent);
    assertArrayEquals(second, secondSent);
    assertArrayEquals(third, thirdSent);

    listener.stop();
  }

  private void recordSends() throws IOException {
    final Stubber recording = Mockito.doAnswer(this::record);
    final WebSocketSession stubbedSession = recording.when(this.session);
    stubbedSession.sendMessage(ArgumentMatchers.any());
  }

  private Void record(final InvocationOnMock invocation) {
    final WebSocketMessage<?> message = invocation.getArgument(0);
    final byte[] payload = this.payloadOf(message);
    this.sent.add(payload);

    return null;
  }

  private byte[] payloadOf(final WebSocketMessage<?> message) {
    final BinaryMessage binary = (BinaryMessage) message;
    final ByteBuffer payload = binary.getPayload();
    final ByteBuffer copy = payload.duplicate();
    final int remaining = copy.remaining();
    final byte[] bytes = new byte[remaining];
    copy.get(bytes);
    return bytes;
  }

  private byte[] nextSent() throws InterruptedException {
    return this.sent.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void shouldQueueWithoutBlockingWhileAWriteIsInProgressAndDropTheOldestChunks() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLISECONDS, SMALL_BUFFER_LIMIT_BYTES);
    listener.start();
    final byte[] first = this.chunk(MEDIUM_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    this.offerFiveMoreMediumChunks(listener);
    final int pending = listener.getPendingBytes();
    assertEquals(2 * MEDIUM_CHUNK_BYTES, pending);

    this.releaseSends.countDown();
    final byte[] firstSent = this.nextSent();
    final byte[] secondSent = this.nextSent();
    final byte[] thirdSent = this.nextSent();
    final byte[] expectedSecond = this.chunk(MEDIUM_CHUNK_BYTES, 5);
    final byte[] expectedThird = this.chunk(MEDIUM_CHUNK_BYTES, 6);
    assertArrayEquals(first, firstSent);
    assertArrayEquals(expectedSecond, secondSent);
    assertArrayEquals(expectedThird, thirdSent);

    final byte[] none = this.sent.poll(SILENCE_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertNull(none);

    listener.stop();
  }

  private void blockTheFirstSend() throws IOException {
    final Stubber blocking = Mockito.doAnswer(this::recordAndWaitForRelease);
    final WebSocketSession stubbedSession = blocking.when(this.session);
    stubbedSession.sendMessage(ArgumentMatchers.any());
  }

  private Void recordAndWaitForRelease(final InvocationOnMock invocation) throws InterruptedException {
    this.record(invocation);
    this.workers.observeCurrentThread();
    this.firstSendEntered.countDown();

    final boolean released = this.releaseSends.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(released);

    return null;
  }

  private void awaitFirstSend() throws InterruptedException {
    final boolean entered = this.firstSendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(entered);
  }

  /**
   * Offers five medium chunks filled with the values 2 to 6, which must all be queued.
   */
  private void offerFiveMoreMediumChunks(final AudioListener listener) {
    for (int value = 2; value <= 6; value++) {
      final byte[] next = this.chunk(MEDIUM_CHUNK_BYTES, value);
      final boolean queued = listener.offer(next);
      assertTrue(queued);
    }
  }

  @Test
  void shouldKeepTheNewestChunkEvenWhenItAloneExceedsTheLimit() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLISECONDS, SMALL_BUFFER_LIMIT_BYTES);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] small = this.chunk(MEDIUM_CHUNK_BYTES, 2);
    final byte[] large = this.chunk(1000, 3);
    listener.offer(small);
    listener.offer(large);
    final int pending = listener.getPendingBytes();
    assertEquals(1000, pending);

    this.releaseSends.countDown();
    this.nextSent();
    final byte[] largeSent = this.nextSent();
    assertArrayEquals(large, largeSent);

    listener.stop();
  }

  @Test
  void shouldReportAListenerWhoseWriteIsBlockedForTooLong() throws Exception {
    this.blockTheFirstSend();
    final long limitNanoseconds = TimeUnit.MILLISECONDS.toNanos(SHORT_SEND_TIME_LIMIT_MILLISECONDS);
    final LongSupplier clock = this.scriptedClock(0L, limitNanoseconds + 1);
    final AudioListener listener = this.createListener(SHORT_SEND_TIME_LIMIT_MILLISECONDS, BUFFER_LIMIT_BYTES, clock);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] next = this.chunk(SMALL_CHUNK_BYTES, 2);
    final boolean queued = listener.offer(next);
    assertFalse(queued);

    listener.stop();
  }

  /**
   * Creates a clock that hands out the specified times, one per call, and repeats the last one afterwards.
   *
   * @param times the times in nanoseconds, in the order they are read
   * @return the clock
   */
  private LongSupplier scriptedClock(final long... times) {
    final AtomicInteger reads = new AtomicInteger();
    return () -> this.readNextTime(times, reads);
  }

  private long readNextTime(final long[] times, final AtomicInteger reads) {
    final int read = reads.getAndIncrement();
    final int last = times.length - 1;
    final int index = Math.min(read, last);
    return times[index];
  }

  @Test
  void shouldKeepTheChunksThatFillTheBufferLimitExactly() throws Exception {
    this.blockTheFirstSend();
    final int limitBytes = 2 * MEDIUM_CHUNK_BYTES;
    final AudioListener listener = this.createListener(TIMEOUT_MILLISECONDS, limitBytes);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] second = this.chunk(MEDIUM_CHUNK_BYTES, 2);
    final byte[] third = this.chunk(MEDIUM_CHUNK_BYTES, 3);
    listener.offer(second);
    listener.offer(third);
    final int pending = listener.getPendingBytes();
    final int chunks = listener.getPendingChunkCount();
    assertEquals(limitBytes, pending, "the oldest chunks are only dropped above the limit");
    assertEquals(2, chunks);

    listener.stop();
  }

  @Test
  void shouldAcceptChunksWhileTheWriteHasRunForExactlyTheSendTimeLimit() throws Exception {
    this.blockTheFirstSend();
    final long limitNanoseconds = TimeUnit.MILLISECONDS.toNanos(SHORT_SEND_TIME_LIMIT_MILLISECONDS);
    final LongSupplier clock = this.scriptedClock(0L, limitNanoseconds);
    final AudioListener listener = this.createListener(SHORT_SEND_TIME_LIMIT_MILLISECONDS, BUFFER_LIMIT_BYTES, clock);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] next = this.chunk(SMALL_CHUNK_BYTES, 2);
    final boolean queued = listener.offer(next);
    assertTrue(queued, "a write that has run for exactly the limit is not blocked for longer than it");

    listener.stop();
  }

  @Test
  void shouldReleaseTheQueuedChunksThemselvesWhenStopping() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLISECONDS, 4096);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] second = this.chunk(SMALL_CHUNK_BYTES, 2);
    final byte[] third = this.chunk(SMALL_CHUNK_BYTES, 3);
    listener.offer(second);
    listener.offer(third);
    final int queuedBefore = listener.getPendingChunkCount();
    listener.stop();
    final int queuedAfter = listener.getPendingChunkCount();
    assertEquals(2, queuedBefore);
    assertEquals(0, queuedAfter, "stopping releases the chunks that were waiting");
  }

  @Test
  void shouldRefuseChunksForClosedSessions() {
    final AudioListener listener = this.createListener();
    Mockito.when(this.session.isOpen()).thenReturn(false);
    final byte[] next = this.chunk(SMALL_CHUNK_BYTES, 1);
    final boolean queued = listener.offer(next);
    final int pending = listener.getPendingBytes();
    assertFalse(queued);
    assertEquals(0, pending);
  }

  @Test
  void shouldDropQueuedChunksAndRefuseNewOnesWhenStopping() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLISECONDS, BUFFER_LIMIT_BYTES);
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] second = this.chunk(SMALL_CHUNK_BYTES, 2);
    listener.offer(second);
    listener.stop();
    final int pending = listener.getPendingBytes();
    final boolean queuedAfterStop = listener.offer(second);
    assertEquals(0, pending);
    assertFalse(queuedAfterStop);

    this.releaseSends.countDown();
    this.nextSent();
    final byte[] none = this.sent.poll(SILENCE_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertNull(none);

    final VerificationMode never = Mockito.never();
    final WebSocketSession verified = Mockito.verify(this.session, never);
    verified.close(ArgumentMatchers.any());
  }

  @Test
  void shouldReturnFromStartTheSenderThatStopWakesAndEnds() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener();
    final Thread sender = listener.start();
    final boolean virtual = sender.isVirtual();
    final String name = sender.getName();
    assertNotNull(sender, "start hands the sender thread to the caller");
    assertTrue(virtual);
    assertEquals("mcav-http-listener-listener-1", name);

    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    final byte[] firstSent = this.nextSent();
    assertArrayEquals(first, firstSent);

    this.awaitWaiting(sender);
    listener.stop();
    sender.join(TIMEOUT_MILLISECONDS);
    final boolean alive = sender.isAlive();
    assertFalse(alive, "stop wakes the waiting sender so its thread ends");
  }

  /**
   * Waits until a thread is waiting for a signal, so that only a signal can let it continue.
   *
   * @param thread the thread to watch
   */
  private void awaitWaiting(final Thread thread) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Thread.State state = thread.getState();
      if (state == Thread.State.WAITING) {
        return;
      }
      Thread.onSpinWait();
    }

    fail("Sender never waited for a chunk!");
  }

  @Test
  void shouldStopAnIdleSender() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener();
    listener.start();
    listener.stop();
    final byte[] next = this.chunk(SMALL_CHUNK_BYTES, 1);
    final boolean queued = listener.offer(next);
    assertFalse(queued);

    final byte[] none = this.sent.poll(SILENCE_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertNull(none);
  }

  @Test
  void shouldReportFailedWritesAndStop() throws Exception {
    Mockito.doThrow(new IOException("broken pipe!")).when(this.session).sendMessage(ArgumentMatchers.any());
    final AudioListener listener = this.createListener();
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    final AudioListener failed = this.failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertSame(listener, failed);

    final boolean queued = listener.offer(first);
    assertFalse(queued);
  }

  @Test
  void shouldReportWritesFailingWithRuntimeExceptions() throws Exception {
    Mockito.doThrow(new IllegalStateException("closed!")).when(this.session).sendMessage(ArgumentMatchers.any());
    final AudioListener listener = this.createListener();
    listener.start();
    final byte[] first = this.chunk(SMALL_CHUNK_BYTES, 1);
    listener.offer(first);
    final AudioListener failed = this.failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertSame(listener, failed);
  }

  @Test
  void shouldIgnoreConnectionsThatAreAlreadyGoneWhenClosingAsynchronously() throws Exception {
    Mockito.doThrow(new IOException("gone!")).when(this.session).close(CloseStatus.GOING_AWAY);
    Mockito.doThrow(new IllegalStateException("gone!")).when(this.session).close(CloseStatus.SERVER_ERROR);
    final AudioListener listener = this.createListener();
    final Thread first = listener.closeAsync(CloseStatus.GOING_AWAY);
    final Thread second = listener.closeAsync(CloseStatus.SERVER_ERROR);
    first.join(TIMEOUT_MILLISECONDS);
    second.join(TIMEOUT_MILLISECONDS);
    final boolean firstAlive = first.isAlive();
    final boolean secondAlive = second.isAlive();
    assertFalse(firstAlive);
    assertFalse(secondAlive);

    final VerificationMode once = Mockito.times(1);
    this.verifySession(once, CloseStatus.GOING_AWAY);
    this.verifySession(once, CloseStatus.SERVER_ERROR);
  }

  private void verifySession(final VerificationMode mode, final CloseStatus status) throws IOException {
    final WebSocketSession verified = Mockito.verify(this.session, mode);
    verified.close(status);
  }

  @Test
  void shouldCloseTheSessionOnAVirtualThreadWhenClosingAsynchronously() throws Exception {
    final AudioListener listener = this.createListener();
    listener.start();
    final Thread closer = listener.closeAsync(CloseStatus.SESSION_NOT_RELIABLE);
    final boolean virtual = closer.isVirtual();
    final String name = closer.getName();
    closer.join(TIMEOUT_MILLISECONDS);
    assertTrue(virtual);
    assertEquals("mcav-http-close-listener-1", name);

    final VerificationMode once = Mockito.times(1);
    this.verifySession(once, CloseStatus.SESSION_NOT_RELIABLE);

    final byte[] next = this.chunk(SMALL_CHUNK_BYTES, 1);
    final boolean queued = listener.offer(next);
    assertFalse(queued);
  }
}

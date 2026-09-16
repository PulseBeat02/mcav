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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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
  private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);

  private final WebSocketSession session = Mockito.mock(WebSocketSession.class);
  private final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
  private final CountDownLatch firstSendEntered = new CountDownLatch(1);
  private final CountDownLatch releaseSends = new CountDownLatch(1);
  private final CompletableFuture<AudioListener> failure = new CompletableFuture<>();

  private AudioListener createListener(final long sendTimeLimitMillis, final int bufferLimitBytes) {
    Mockito.when(this.session.getId()).thenReturn("listener-1");
    Mockito.when(this.session.isOpen()).thenReturn(true);
    return new AudioListener(this.session, sendTimeLimitMillis, bufferLimitBytes, this.failure::complete);
  }

  private AudioListener createListener(final long sendTimeLimitMillis, final int bufferLimitBytes, final LongSupplier nanoClock) {
    Mockito.when(this.session.getId()).thenReturn("listener-1");
    Mockito.when(this.session.isOpen()).thenReturn(true);
    return new AudioListener(this.session, sendTimeLimitMillis, bufferLimitBytes, this.failure::complete, nanoClock);
  }

  /**
   * Creates a clock that hands out the specified times, one per call, and repeats the last one afterwards.
   *
   * @param times the times in nanoseconds, in the order they are read
   * @return the clock
   */
  private static LongSupplier scriptedClock(final long... times) {
    final AtomicInteger reads = new AtomicInteger();
    return () -> {
      final int read = reads.getAndIncrement();
      final int last = times.length - 1;
      final int index = Math.min(read, last);
      return times[index];
    };
  }

  /**
   * Waits until a thread is waiting for a signal, so that only a signal can let it continue.
   *
   * @param thread the thread to watch
   */
  private static void awaitWaiting(final Thread thread) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      final Thread.State state = thread.getState();
      if (state == Thread.State.WAITING) {
        return;
      }
      Thread.onSpinWait();
    }
    fail("the sender never waited for a chunk");
  }

  private void recordSends() throws IOException {
    Mockito.doAnswer(invocation -> {
      final WebSocketMessage<?> message = invocation.getArgument(0);
      final byte[] payload = payloadOf(message);
      this.sent.add(payload);
      return null;
    })
      .when(this.session)
      .sendMessage(ArgumentMatchers.any());
  }

  private void blockTheFirstSend() throws IOException {
    Mockito.doAnswer(invocation -> {
      final WebSocketMessage<?> message = invocation.getArgument(0);
      final byte[] payload = payloadOf(message);
      this.sent.add(payload);
      this.firstSendEntered.countDown();
      final boolean released = this.releaseSends.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(released);
      return null;
    })
      .when(this.session)
      .sendMessage(ArgumentMatchers.any());
  }

  @AfterEach
  void releaseBlockedSenders() {
    this.releaseSends.countDown();
  }

  private static byte[] payloadOf(final WebSocketMessage<?> message) {
    final BinaryMessage binary = (BinaryMessage) message;
    final ByteBuffer payload = binary.getPayload();
    final ByteBuffer copy = payload.duplicate();
    final int remaining = copy.remaining();
    final byte[] bytes = new byte[remaining];
    copy.get(bytes);
    return bytes;
  }

  private static byte[] chunk(final int size, final int value) {
    final byte[] bytes = new byte[size];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private byte[] nextSent() throws InterruptedException {
    return this.sent.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private void awaitFirstSend() throws InterruptedException {
    final boolean entered = this.firstSendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(entered);
  }

  private void verifySession(final VerificationMode mode, final CloseStatus status) throws IOException {
    final WebSocketSession verified = Mockito.verify(this.session, mode);
    verified.close(status);
  }

  @Test
  void exposesItsSession() {
    final AudioListener listener = this.createListener(1000, 1024);
    final WebSocketSession listenerSession = listener.getSession();
    assertSame(this.session, listenerSession);
  }

  @Test
  void sendsQueuedChunksInOrderAsBinaryMessages() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener(1000, 1024);
    listener.start();
    final byte[] first = chunk(10, 1);
    final byte[] second = chunk(20, 2);
    final byte[] third = chunk(30, 3);
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

  /**
   * Offers five chunks of 100 bytes filled with the values 2 to 6, which must all be queued.
   */
  private static void offerFiveMoreHundredByteChunks(final AudioListener listener) {
    for (int value = 2; value <= 6; value++) {
      final byte[] next = chunk(100, value);
      final boolean queued = listener.offer(next);
      assertTrue(queued);
    }
  }

  @Test
  void queuesWithoutBlockingWhileAWriteIsInProgressAndDropsTheOldestChunks() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLIS, 250);
    listener.start();
    final byte[] first = chunk(100, 1);
    listener.offer(first);
    this.awaitFirstSend();
    offerFiveMoreHundredByteChunks(listener);
    final int pending = listener.getPendingBytes();
    assertEquals(200, pending);

    this.releaseSends.countDown();
    final byte[] firstSent = this.nextSent();
    final byte[] secondSent = this.nextSent();
    final byte[] thirdSent = this.nextSent();
    final byte[] expectedSecond = chunk(100, 5);
    final byte[] expectedThird = chunk(100, 6);
    assertArrayEquals(first, firstSent);
    assertArrayEquals(expectedSecond, secondSent);
    assertArrayEquals(expectedThird, thirdSent);
    final byte[] none = this.sent.poll(50, TimeUnit.MILLISECONDS);
    assertNull(none);
    listener.stop();
  }

  @Test
  void keepsTheNewestChunkEvenWhenItAloneExceedsTheLimit() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLIS, 250);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] small = chunk(100, 2);
    final byte[] large = chunk(1000, 3);
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
  void reportsAListenerWhoseWriteIsBlockedForTooLong() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(50, 1024);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    // the write started before the answer was entered, so it is now blocked for twice the send time limit
    Thread.sleep(100);
    final byte[] next = chunk(10, 2);
    final boolean queued = listener.offer(next);
    assertFalse(queued);
    listener.stop();
  }

  @Test
  void keepsTheChunksThatFillTheBufferLimitExactly() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLIS, 200);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    // the queue holds exactly the limit afterwards, which is still within it, so nothing is dropped
    final byte[] second = chunk(100, 2);
    final byte[] third = chunk(100, 3);
    listener.offer(second);
    listener.offer(third);
    final int pending = listener.getPendingBytes();
    final int chunks = listener.getPendingChunkCount();
    assertEquals(200, pending, "the oldest chunks are only dropped above the limit");
    assertEquals(2, chunks);
    listener.stop();
  }

  @Test
  void acceptsChunksWhileTheWriteHasRunForExactlyTheSendTimeLimit() throws Exception {
    this.blockTheFirstSend();
    final long limitMillis = 50L;
    final long limitNanos = TimeUnit.MILLISECONDS.toNanos(limitMillis);
    // the sender reads the clock when it takes the chunk, and the next offer reads it again to time the write
    final LongSupplier clock = scriptedClock(0L, limitNanos);
    final AudioListener listener = this.createListener(limitMillis, 1024, clock);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] next = chunk(10, 2);
    final boolean queued = listener.offer(next);
    assertTrue(queued, "a write that has run for exactly the limit is not blocked for longer than it");
    listener.stop();
  }

  @Test
  void stopReleasesTheQueuedChunksThemselves() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLIS, 4096);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] second = chunk(10, 2);
    final byte[] third = chunk(10, 3);
    listener.offer(second);
    listener.offer(third);
    final int queuedBefore = listener.getPendingChunkCount();
    listener.stop();
    final int queuedAfter = listener.getPendingChunkCount();
    assertEquals(2, queuedBefore);
    assertEquals(0, queuedAfter, "stopping releases the chunks that were waiting");
  }

  @Test
  void refusesChunksForClosedSessions() {
    final AudioListener listener = this.createListener(1000, 1024);
    Mockito.when(this.session.isOpen()).thenReturn(false);
    final byte[] next = chunk(10, 1);
    final boolean queued = listener.offer(next);
    final int pending = listener.getPendingBytes();
    assertFalse(queued);
    assertEquals(0, pending);
  }

  @Test
  void stopDropsQueuedChunksAndRefusesNewOnes() throws Exception {
    this.blockTheFirstSend();
    final AudioListener listener = this.createListener(TIMEOUT_MILLIS, 1024);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    this.awaitFirstSend();
    final byte[] second = chunk(10, 2);
    listener.offer(second);
    listener.stop();
    final int pending = listener.getPendingBytes();
    final boolean queuedAfterStop = listener.offer(second);
    assertEquals(0, pending);
    assertFalse(queuedAfterStop);

    this.releaseSends.countDown();
    this.nextSent();
    final byte[] none = this.sent.poll(100, TimeUnit.MILLISECONDS);
    assertNull(none);
    final VerificationMode never = Mockito.never();
    final WebSocketSession verified = Mockito.verify(this.session, never);
    verified.close(ArgumentMatchers.any());
  }

  @Test
  void startReturnsTheSenderThatStopWakesAndEnds() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener(1000, 1024);
    final Thread sender = listener.start();
    final boolean virtual = sender.isVirtual();
    final String name = sender.getName();
    assertNotNull(sender, "start hands the sender thread to the caller");
    assertTrue(virtual);
    assertEquals("mcav-http-listener-listener-1", name);

    // the sender is only waiting for a chunk once it wrote one, so the first chunk is sent before it is stopped
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    final byte[] firstSent = this.nextSent();
    assertArrayEquals(first, firstSent);
    awaitWaiting(sender);

    // the sender waits for a chunk, so stopping has to wake it; otherwise it would wait forever
    listener.stop();
    sender.join(TIMEOUT_MILLIS);
    final boolean alive = sender.isAlive();
    assertFalse(alive, "stop wakes the waiting sender so its thread ends");
  }

  @Test
  void stopsAnIdleSender() throws Exception {
    this.recordSends();
    final AudioListener listener = this.createListener(1000, 1024);
    listener.start();
    listener.stop();
    final byte[] next = chunk(10, 1);
    final boolean queued = listener.offer(next);
    assertFalse(queued);
    final byte[] none = this.sent.poll(50, TimeUnit.MILLISECONDS);
    assertNull(none);
  }

  @Test
  void reportsFailedWritesAndStops() throws Exception {
    Mockito.doThrow(new IOException("broken pipe")).when(this.session).sendMessage(ArgumentMatchers.any());
    final AudioListener listener = this.createListener(1000, 1024);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    final AudioListener failed = this.failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertSame(listener, failed);
    final boolean queued = listener.offer(first);
    assertFalse(queued);
  }

  @Test
  void reportsWritesFailingWithRuntimeExceptions() throws Exception {
    Mockito.doThrow(new IllegalStateException("closed")).when(this.session).sendMessage(ArgumentMatchers.any());
    final AudioListener listener = this.createListener(1000, 1024);
    listener.start();
    final byte[] first = chunk(10, 1);
    listener.offer(first);
    final AudioListener failed = this.failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertSame(listener, failed);
  }

  @Test
  void closeAsyncIgnoresConnectionsThatAreAlreadyGone() throws Exception {
    Mockito.doThrow(new IOException("gone")).when(this.session).close(CloseStatus.GOING_AWAY);
    Mockito.doThrow(new IllegalStateException("gone")).when(this.session).close(CloseStatus.SERVER_ERROR);
    final AudioListener listener = this.createListener(1000, 1024);
    final Thread first = listener.closeAsync(CloseStatus.GOING_AWAY);
    final Thread second = listener.closeAsync(CloseStatus.SERVER_ERROR);
    first.join(TIMEOUT_MILLIS);
    second.join(TIMEOUT_MILLIS);
    final boolean firstAlive = first.isAlive();
    final boolean secondAlive = second.isAlive();
    assertFalse(firstAlive);
    assertFalse(secondAlive);

    final VerificationMode once = Mockito.times(1);
    this.verifySession(once, CloseStatus.GOING_AWAY);
    this.verifySession(once, CloseStatus.SERVER_ERROR);
  }

  @Test
  void closeAsyncClosesTheSessionOnAVirtualThread() throws Exception {
    final AudioListener listener = this.createListener(1000, 1024);
    listener.start();
    final Thread closer = listener.closeAsync(CloseStatus.SESSION_NOT_RELIABLE);
    final boolean virtual = closer.isVirtual();
    final String name = closer.getName();
    closer.join(TIMEOUT_MILLIS);
    assertTrue(virtual);
    assertEquals("mcav-http-close-listener-1", name);

    final VerificationMode once = Mockito.times(1);
    this.verifySession(once, CloseStatus.SESSION_NOT_RELIABLE);
    final byte[] next = chunk(10, 1);
    final boolean queued = listener.offer(next);
    assertFalse(queued);
  }
}

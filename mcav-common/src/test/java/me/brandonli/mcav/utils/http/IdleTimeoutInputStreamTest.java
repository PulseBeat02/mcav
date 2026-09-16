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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link IdleTimeoutInputStream}.
 */
final class IdleTimeoutInputStreamTest {

  private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(10);

  @Test
  void passesTheDataThroughByteByByteAndInBlocks() throws IOException {
    final byte[] content = { 1, 2, (byte) 200, 4 };
    final ByteArrayInputStream source = new ByteArrayInputStream(content);
    try (final IdleTimeoutInputStream stream = new IdleTimeoutInputStream(source, GENEROUS_TIMEOUT)) {
      final int first = stream.read();
      final int second = stream.read();
      final int unsigned = stream.read();
      final byte[] rest = new byte[4];
      final int count = stream.read(rest, 0, rest.length);
      final int endOfBlocks = stream.read(rest, 0, rest.length);
      final int endOfBytes = stream.read();
      final byte[] restContent = Arrays.copyOf(rest, count);
      assertEquals(1, first);
      assertEquals(2, second);
      assertEquals(200, unsigned, "bytes are returned as unsigned values");
      assertEquals(1, count);
      assertArrayEquals(new byte[] { 4 }, restContent);
      assertEquals(-1, endOfBlocks);
      assertEquals(-1, endOfBytes);
    }
  }

  @Test
  void failsAndClosesTheSourceWhenNoDataArrives() throws IOException {
    final BlockingSource source = new BlockingSource();
    final Duration idleTimeout = Duration.ofMillis(100);
    try (final IdleTimeoutInputStream stream = new IdleTimeoutInputStream(source, idleTimeout)) {
      final byte[] buffer = new byte[8];
      final HttpTimeoutException exception = assertThrows(HttpTimeoutException.class, () -> stream.read(buffer, 0, buffer.length));
      final String message = exception.getMessage();
      final boolean closed = source.isClosed();
      final boolean namesTheTimeout = message.contains("100 ms");
      assertTrue(namesTheTimeout, message);
      assertTrue(closed, "the stalled source is closed, which releases its connection");
    }
  }

  @Test
  void reportsFailuresOfTheSource() throws IOException {
    final IOException failure = new IOException("connection reset");
    final InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw failure;
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw failure;
      }
    };
    try (final IdleTimeoutInputStream stream = new IdleTimeoutInputStream(broken, GENEROUS_TIMEOUT)) {
      final IOException thrown = assertThrows(IOException.class, stream::read);
      final Throwable cause = thrown.getCause();
      final String message = thrown.getMessage();
      assertSame(failure, cause);
      final boolean namesTheFailure = message.contains("connection reset");
      assertTrue(namesTheFailure, message);
    }
  }

  @Test
  void stopsWaitingWhenInterrupted() throws IOException {
    final BlockingSource source = new BlockingSource();
    try (final IdleTimeoutInputStream stream = new IdleTimeoutInputStream(source, GENEROUS_TIMEOUT)) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      assertThrows(InterruptedIOException.class, stream::read);
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be restored");
    }
  }

  @Test
  void closesTheSource() throws IOException {
    final BlockingSource source = new BlockingSource();
    final IdleTimeoutInputStream stream = new IdleTimeoutInputStream(source, GENEROUS_TIMEOUT);
    stream.close();
    final boolean closed = source.isClosed();
    assertTrue(closed);
  }

  /**
   * A source that delivers no data until it is closed, like a server that stopped sending.
   */
  private static final class BlockingSource extends InputStream {

    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      return this.awaitClosing();
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      return this.awaitClosing();
    }

    /**
     * Blocks until the source is closed and then reports its end, like a connection that ends without sending.
     */
    private int awaitClosing() throws IOException {
      final boolean closedInTime;
      try {
        closedInTime = this.closed.await(1, TimeUnit.MINUTES);
      } catch (final InterruptedException exception) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        throw new InterruptedIOException("interrupted");
      }
      if (!closedInTime) {
        throw new IOException("the source was never closed");
      }
      return -1;
    }

    @Override
    public void close() {
      this.closed.countDown();
    }

    boolean isClosed() {
      return this.closed.getCount() == 0;
    }
  }
}

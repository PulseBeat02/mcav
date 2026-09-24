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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An input stream that fails when its source delivers no data for too long.
 *
 * <p>The HTTP client only limits how long the response headers may take; once the body is flowing, a server that
 * stops sending would block a read forever. This stream waits for every read of its source on a virtual thread for
 * at most the idle timeout. When the timeout passes, the source is closed and the read fails with an
 * {@link HttpTimeoutException}, which the downloader treats like any other network failure.
 */
final class IdleTimeoutInputStream extends InputStream {

  private final InputStream delegate;
  private final Duration idleTimeout;
  private final ExecutorService reader;

  /**
   * Constructs a new stream.
   *
   * @param delegate    the source, which this stream closes
   * @param idleTimeout how long a read may wait for data
   */
  IdleTimeoutInputStream(final InputStream delegate, final Duration idleTimeout) {
    this.delegate = delegate;
    this.idleTimeout = idleTimeout;
    this.reader = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public int read() throws IOException {
    final byte[] single = new byte[1];
    final int count = this.read(single, 0, 1);
    if (count == -1) {
      return -1;
    }
    return single[0] & 0xFF;
  }

  @Override
  public int read(final byte@NonNull[] buffer, final int offset, final int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    if (length == 0) {
      return 0;
    }
    final Callable<Integer> readTask = () -> this.delegate.read(buffer, offset, length);
    final Future<Integer> pending = this.reader.submit(readTask);
    return this.await(pending);
  }

  private int await(final Future<Integer> pending) throws IOException {
    final long timeoutNanos = this.idleTimeout.toNanos();
    try {
      return pending.get(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (final TimeoutException exception) {
      pending.cancel(true);
      this.delegate.close();
      final long timeoutMillis = this.idleTimeout.toMillis();
      throw new HttpTimeoutException("No data was received for " + timeoutMillis + " ms");
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      pending.cancel(true);
      throw new InterruptedIOException("Interrupted while waiting for data");
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof final RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof final Error error) {
        throw error;
      }
      final String reason = exception.getMessage();
      throw new IOException("Reading the response failed: " + reason, cause);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      this.delegate.close();
    } finally {
      this.reader.shutdownNow();
    }
  }
}

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

// Fails a read idle past the timeout with an HttpTimeoutException, closing the source: the HTTP client limits only the headers.
final class IdleTimeoutInputStream extends InputStream {

  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final boolean INTERRUPT_IF_RUNNING = true;

  private final InputStream delegate;
  private final Duration idleTimeout;
  private final ExecutorService reader;

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

    return single[0] & UNSIGNED_BYTE_MASK;
  }

  @Override
  public int read(final byte @NonNull [] buffer, final int offset, final int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);

    if (length == 0) {
      return 0;
    }

    final Callable<Integer> readTask = () -> this.delegate.read(buffer, offset, length);
    final Future<Integer> pending = this.reader.submit(readTask);
    return this.await(pending);
  }

  private int await(final Future<Integer> pending) throws IOException {
    final long timeoutNanoseconds = this.idleTimeout.toNanos();
    try {
      return pending.get(timeoutNanoseconds, TimeUnit.NANOSECONDS);
    } catch (final TimeoutException exception) {
      pending.cancel(INTERRUPT_IF_RUNNING);
      this.delegate.close();
      final long timeoutMilliseconds = this.idleTimeout.toMillis();
      final String message = "No data was received for %s ms!".formatted(timeoutMilliseconds);
      final HttpTimeoutException timedOut = new HttpTimeoutException(message);
      timedOut.initCause(exception);
      throw timedOut;
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      pending.cancel(INTERRUPT_IF_RUNNING);

      final String message = "Interrupted while waiting for data!";
      final InterruptedIOException interrupted = new InterruptedIOException(message);
      interrupted.initCause(exception);
      throw interrupted;
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof final RuntimeException runtimeException) {
        throw runtimeException;
      }

      if (cause instanceof final Error error) {
        throw error;
      }

      final String reason = exception.getMessage();
      final String message = "Because of \"%s\", reading the response failed!".formatted(reason);
      throw new IOException(message, cause);
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

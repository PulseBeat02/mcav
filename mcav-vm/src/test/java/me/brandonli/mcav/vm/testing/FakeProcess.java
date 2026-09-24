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
package me.brandonli.mcav.vm.testing;

import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A process for tests that runs no program. It prints prepared output, stays alive until it is told to exit or is
 * destroyed, and counts how it was destroyed.
 */
public final class FakeProcess extends Process {

  private static final long LONGEST_WAIT_MILLIS = 200L;

  private final InputStream output;
  private final CountDownLatch exited;
  private final AtomicInteger destroyCalls;
  private final AtomicInteger forcibleDestroyCalls;

  private volatile int exitCode;
  private volatile boolean exitsOnDestroy;
  private volatile boolean interruptsWaits;
  private volatile boolean interruptsFirstWait;
  private volatile int exitCodeOnWait = -1;
  private volatile long forcedExitDelayMillis;

  private FakeProcess(final InputStream output) {
    this.output = output;
    this.exited = new CountDownLatch(1);
    this.destroyCalls = new AtomicInteger();
    this.forcibleDestroyCalls = new AtomicInteger();
    this.exitsOnDestroy = true;
  }

  /**
   * Creates a running process that prints the given text and then nothing more.
   *
   * @param text the output
   * @return the process
   */
  public static FakeProcess running(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    final InputStream stream = new ByteArrayInputStream(bytes);
    return new FakeProcess(stream);
  }

  /**
   * Creates a running process with the given output stream.
   *
   * @param stream the output
   * @return the process
   */
  public static FakeProcess running(final InputStream stream) {
    Preconditions.checkNotNull(stream, "Stream must not be null");
    return new FakeProcess(stream);
  }

  /**
   * Creates a process that printed the given text and already exited.
   *
   * @param exitCode the exit code
   * @param text     the output
   * @return the process
   */
  public static FakeProcess exited(final int exitCode, final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final FakeProcess process = running(text);
    process.exit(exitCode);
    return process;
  }

  /**
   * Makes {@link #destroy()} leave the process running, like a program that ignores termination requests.
   */
  public void ignoringDestroy() {
    this.exitsOnDestroy = false;
  }

  /**
   * Delays actual termination after the force request, as an operating system may do.
   *
   * @param delayMillis the delay before the process exits
   */
  public void delayingForcedExit(final long delayMillis) {
    Preconditions.checkArgument(delayMillis > 0, "Delay must be positive");
    this.forcedExitDelayMillis = delayMillis;
  }

  /**
   * Makes every timed wait throw an {@link InterruptedException}.
   */
  public void interruptingWaits() {
    this.interruptsWaits = true;
  }

  /**
   * Makes only the first timed wait throw an {@link InterruptedException}; every later wait behaves normally, as a
   * real process does once the interrupt of the thread was consumed.
   */
  public void interruptingFirstWait() {
    this.interruptsFirstWait = true;
  }

  /**
   * Makes the process exit as soon as it is waited on with a timeout, as QEMU does when it fails right after
   * starting.
   *
   * @param code the exit code
   */
  public void exitingWhenWaitedOn(final int code) {
    this.exitCodeOnWait = code;
  }

  /**
   * Ends the process.
   *
   * @param code the exit code
   */
  public void exit(final int code) {
    if (this.exited.getCount() > 0) {
      this.exitCode = code;
      this.exited.countDown();
    }
  }

  /**
   * Gets how often {@link #destroy()} was called.
   *
   * @return the number of calls
   */
  public int getDestroyCalls() {
    return this.destroyCalls.get();
  }

  /**
   * Gets how often {@link #destroyForcibly()} was called.
   *
   * @return the number of calls
   */
  public int getForcibleDestroyCalls() {
    return this.forcibleDestroyCalls.get();
  }

  @Override
  public OutputStream getOutputStream() {
    return OutputStream.nullOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return this.output;
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    this.exited.await();
    return this.exitCode;
  }

  @Override
  public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
    Preconditions.checkNotNull(unit, "Unit must not be null");
    if (this.interruptsWaits) {
      throw new InterruptedException("interrupted on purpose");
    }
    if (this.interruptsFirstWait) {
      this.interruptsFirstWait = false;
      throw new InterruptedException("the first wait is interrupted on purpose");
    }
    final int codeOnWait = this.exitCodeOnWait;
    if (codeOnWait >= 0) {
      this.exit(codeOnWait);
    }
    // waits are capped so tests of long timeouts stay fast
    final long requested = unit.toMillis(timeout);
    final long wait = Math.min(requested, LONGEST_WAIT_MILLIS);
    return this.exited.await(wait, TimeUnit.MILLISECONDS);
  }

  @Override
  public int exitValue() {
    if (this.exited.getCount() > 0) {
      throw new IllegalThreadStateException("process has not exited");
    }
    return this.exitCode;
  }

  @Override
  public boolean isAlive() {
    return this.exited.getCount() > 0;
  }

  @Override
  public void destroy() {
    this.destroyCalls.incrementAndGet();
    if (this.exitsOnDestroy) {
      this.exit(143);
    }
  }

  @Override
  public Process destroyForcibly() {
    final int calls = this.forcibleDestroyCalls.incrementAndGet();
    final long delayMillis = this.forcedExitDelayMillis;
    if (delayMillis == 0) {
      this.exit(137);
    } else if (calls == 1) {
      final Thread termination = new Thread(() -> this.exitAfterDelay(delayMillis), "fake-qemu-termination");
      termination.setDaemon(true);
      termination.start();
    }
    return this;
  }

  private void exitAfterDelay(final long delayMillis) {
    try {
      Thread.sleep(delayMillis);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } finally {
      this.exit(137);
    }
  }
}

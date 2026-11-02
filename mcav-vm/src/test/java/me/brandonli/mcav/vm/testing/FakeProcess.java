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

public final class FakeProcess extends Process {

  private static final long LONGEST_WAIT_MILLISECONDS = 200L;

  private static final String TEXT_NULL_MESSAGE = "Text must not be null!";

  private static final int TERMINATED_EXIT_CODE = 143;
  private static final int KILLED_EXIT_CODE = 137;

  private static final int NO_EXIT_CODE_ON_WAIT = -1;

  private static final String TERMINATION_THREAD_NAME = "fake-qemu-termination";

  private final InputStream output;
  private final CountDownLatch exited;
  private final AtomicInteger destroyCalls;
  private final AtomicInteger forcibleDestroyCalls;

  private volatile int exitCode;
  private volatile boolean exitsOnDestroy;
  private volatile boolean interruptsWaits;
  private volatile boolean interruptsFirstWait;
  private volatile int exitCodeOnWait;
  private volatile long forcedExitDelayMilliseconds;

  private FakeProcess(final InputStream output) {
    this.output = output;
    this.exited = new CountDownLatch(1);
    this.destroyCalls = new AtomicInteger();
    this.forcibleDestroyCalls = new AtomicInteger();
    this.exitsOnDestroy = true;
    this.exitCodeOnWait = NO_EXIT_CODE_ON_WAIT;
  }

  public static FakeProcess running(final String text) {
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    final InputStream stream = new ByteArrayInputStream(bytes);
    return new FakeProcess(stream);
  }

  public static FakeProcess running(final InputStream stream) {
    Preconditions.checkNotNull(stream, "Stream must not be null!");
    return new FakeProcess(stream);
  }

  public static FakeProcess exited(final int exitCode, final String text) {
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);
    final FakeProcess process = running(text);
    process.exit(exitCode);
    return process;
  }

  public void ignoringDestroy() {
    this.exitsOnDestroy = false;
  }

  public void delayingForcedExit(final long delayMilliseconds) {
    Preconditions.checkArgument(delayMilliseconds > 0, "Delay %s must be positive!", delayMilliseconds);
    this.forcedExitDelayMilliseconds = delayMilliseconds;
  }

  public void interruptingWaits() {
    this.interruptsWaits = true;
  }

  public void interruptingFirstWait() {
    this.interruptsFirstWait = true;
  }

  public void exitingWhenWaitedOn(final int code) {
    this.exitCodeOnWait = code;
  }

  public void exit(final int code) {
    if (this.exited.getCount() > 0) {
      this.exitCode = code;
      this.exited.countDown();
    }
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
    Preconditions.checkNotNull(unit, "Unit must not be null!");
    if (this.interruptsWaits) {
      throw new InterruptedException("Interrupted on purpose!");
    }

    if (this.interruptsFirstWait) {
      this.interruptsFirstWait = false;
      throw new InterruptedException("The first wait is interrupted on purpose!");
    }

    final int codeOnWait = this.exitCodeOnWait;
    if (codeOnWait >= 0) {
      this.exit(codeOnWait);
    }

    final long requested = unit.toMillis(timeout);
    final long wait = Math.min(requested, LONGEST_WAIT_MILLISECONDS);
    return this.exited.await(wait, TimeUnit.MILLISECONDS);
  }

  @Override
  public int exitValue() {
    if (this.exited.getCount() > 0) {
      throw new IllegalThreadStateException("Process has not exited!");
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
      this.exit(TERMINATED_EXIT_CODE);
    }
  }

  @Override
  public Process destroyForcibly() {
    final int calls = this.forcibleDestroyCalls.incrementAndGet();
    final long delayMilliseconds = this.forcedExitDelayMilliseconds;
    if (delayMilliseconds == 0) {
      this.exit(KILLED_EXIT_CODE);
    } else if (calls == 1) {
      final Thread termination = new Thread(() -> this.exitAfterDelay(delayMilliseconds), TERMINATION_THREAD_NAME);
      termination.setDaemon(true);
      termination.start();
    }

    return this;
  }

  private void exitAfterDelay(final long delayMilliseconds) {
    try {
      Thread.sleep(delayMilliseconds);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } finally {
      this.exit(KILLED_EXIT_CODE);
    }
  }

  public int getDestroyCalls() {
    return this.destroyCalls.get();
  }

  public int getForcibleDestroyCalls() {
    return this.forcibleDestroyCalls.get();
  }
}

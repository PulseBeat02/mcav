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
package me.brandonli.mcav.vm;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;

/**
 * A thread waiting for QEMU to come up while another starts it. Pass 1 made {@code VMProcess.process} volatile: it is
 * written while QEMU starts and stops, but read by {@link VMProcess#isAlive()} on other threads without a lock. With a
 * plain field the compiler may read it once and keep the value, so a thread that polls liveness never sees QEMU start.
 * The actor polls the real {@code isAlive()}; the signal publishes a running process into the field the way
 * {@code launch} does, through the field itself, because starting a real QEMU is not a thing to do millions of times.
 */
@JCStressTest(Mode.Termination)
@Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the poller saw QEMU start")
@Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the poller never saw QEMU start: it kept a stale null")
@State
public class ProcessPublication {

  private static final Field PROCESS = findProcessField();

  private final VMProcess machine = new VMProcess(null, null, null, null, null, null, null, 0);

  private static Field findProcessField() {
    try {
      final Field field = VMProcess.class.getDeclaredField("process");
      field.setAccessible(true);
      return field;
    } catch (final NoSuchFieldException exception) {
      throw new IllegalStateException("VMProcess has no process field any more", exception);
    }
  }

  /**
   * Polls liveness until QEMU runs, as every input method of the player does before it forwards anything.
   */
  @Actor
  public void pollUntilAlive() {
    boolean alive = this.machine.isAlive();
    while (!alive) {
      alive = this.machine.isAlive();
    }
  }

  /**
   * Publishes a running QEMU.
   *
   * @throws IllegalAccessException never, the field was made accessible
   */
  @Signal
  public void launch() throws IllegalAccessException {
    final Process running = new RunningProcess();
    PROCESS.set(this.machine, running);
  }

  /**
   * A process that runs until the test is over.
   */
  private static final class RunningProcess extends Process {

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      throw new IllegalThreadStateException("still running");
    }

    @Override
    public void destroy() {
      // nothing runs
    }

    @Override
    public boolean isAlive() {
      return true;
    }
  }
}

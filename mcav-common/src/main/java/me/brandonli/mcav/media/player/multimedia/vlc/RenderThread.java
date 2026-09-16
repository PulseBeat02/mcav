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
package me.brandonli.mcav.media.player.multimedia.vlc;

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A daemon thread that runs one step of a rendering loop again and again until it is stopped.
 *
 * <p>The renderers of {@link VLCPlayer} hand work from VLC's threads to render threads, so VLC's threads never wait
 * for a pipeline. A step that throws a runtime exception is reported to the failure handler and the loop goes on
 * with the next step, so one broken frame does not end the playback; errors such as running out of memory are not
 * caught. Stopping ends the loop after the current step and waits for the thread to exit, so no step runs after
 * {@link #stop()} returns, unless {@link #stop()} is called from the render thread itself.
 */
final class RenderThread {

  private static final long JOIN_TIMEOUT_MILLIS = 5_000L;

  /**
   * One step of a rendering loop.
   */
  @FunctionalInterface
  interface Step {
    /**
     * Renders the next piece of media, waiting briefly when none is available.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void render() throws InterruptedException;
  }

  private final String name;
  private final Consumer<RuntimeException> failureHandler;
  private final AtomicBoolean running;

  private @Nullable Thread thread;

  /**
   * Constructs a new render thread. Nothing runs until {@link #start(Step, Runnable)} is called.
   *
   * @param name           the name of the thread
   * @param failureHandler receives the runtime exceptions thrown by steps, on the render thread
   */
  RenderThread(final String name, final Consumer<RuntimeException> failureHandler) {
    this.name = name;
    this.failureHandler = failureHandler;
    this.running = new AtomicBoolean(false);
  }

  /**
   * Starts the thread. A render thread can only be started once.
   *
   * @param step    the step that is run repeatedly while the thread is running
   * @param cleanup the action that is run on the thread once the loop exits
   */
  void start(final Step step, final Runnable cleanup) {
    Preconditions.checkState(this.thread == null, "Render thread %s was already started", this.name);
    this.running.set(true);
    final AtomicBoolean flag = this.running;
    final Consumer<RuntimeException> failures = this.failureHandler;
    final Thread created = new Thread(() -> loop(flag, step, failures, cleanup), this.name);
    created.setDaemon(true);
    this.thread = created;
    created.start();
  }

  /**
   * Checks whether the thread has been started and not stopped yet.
   *
   * @return true if the thread accepts work
   */
  boolean isRunning() {
    return this.running.get();
  }

  /**
   * Stops the thread: the loop ends after the current step, a waiting step is interrupted, and the calling thread
   * waits up to five seconds for the thread to exit. Stopping a thread that was never started, or stopping it again,
   * has no effect.
   */
  void stop() {
    this.running.set(false);
    final Thread current = this.thread;
    if (current == null) {
      return;
    }
    current.interrupt();
    joinUnlessSelf(current);
  }

  private static void joinUnlessSelf(final Thread target) {
    final Thread caller = Thread.currentThread();
    final boolean self = caller.equals(target);
    if (self) {
      // a pipeline that stops the playback from its own render thread cannot wait for itself
      return;
    }
    try {
      target.join(JOIN_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      caller.interrupt();
    }
  }

  private static void loop(
    final AtomicBoolean running,
    final Step step,
    final Consumer<RuntimeException> failures,
    final Runnable cleanup
  ) {
    try {
      while (running.get()) {
        runStep(step, failures);
      }
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    } finally {
      cleanup.run();
    }
  }

  private static void runStep(final Step step, final Consumer<RuntimeException> failures) throws InterruptedException {
    try {
      step.render();
    } catch (final RuntimeException exception) {
      failures.accept(exception);
    }
  }
}

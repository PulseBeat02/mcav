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

// A daemon thread that runs one step of a rendering loop again and again until it is stopped. The renderers of
// VlcPlayer hand work from VLC's threads to render threads, so VLC's threads never wait for a pipeline. A step that
// throws a runtime exception is reported to the failure handler and the loop goes on with the next step, so one broken
// frame does not end the playback; errors such as running out of memory are not caught. Stopping interrupts the current
// step and waits up to five seconds for the thread to exit. A step that ignores interruption can outlive that wait; a
// render thread never waits for itself.
final class RenderThread {

  private static final long JOIN_TIMEOUT_MILLISECONDS = 5_000L;

  private final String name;
  private final Consumer<RuntimeException> failureHandler;
  private final AtomicBoolean running;

  private @Nullable Thread thread;

  // Nothing runs until start(Step, Runnable) is called.
  RenderThread(final String name, final Consumer<RuntimeException> failureHandler) {
    this.name = name;
    this.failureHandler = failureHandler;
    this.running = new AtomicBoolean(false);
  }

  // A render thread can only be started once.
  void start(final Step step, final Runnable cleanup) {
    Preconditions.checkState(this.thread == null, "Render thread %s was already started!", this.name);

    this.running.set(true);
    final Thread created = new Thread(() -> this.loop(step, cleanup), this.name);
    created.setDaemon(true);
    this.thread = created;
    created.start();
  }

  // Stops the thread: the loop ends after the current step, a waiting step is interrupted, and the calling thread waits
  // up to five seconds for the thread to exit. Stopping a thread that was never started, or stopping it again, has no
  // effect.
  void stop() {
    this.running.set(false);
    final Thread current = this.thread;
    if (current == null) {
      return;
    }

    current.interrupt();
    this.joinUnlessSelf(current);
  }

  private void joinUnlessSelf(final Thread target) {
    final Thread current = Thread.currentThread();
    final boolean isTargetThread = current.equals(target);
    if (isTargetThread) {
      return;
    }

    try {
      target.join(JOIN_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      current.interrupt();
    }
  }

  private void loop(final Step step, final Runnable cleanup) {
    try {
      while (this.running.get()) {
        this.runStep(step);
      }
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } finally {
      this.running.set(false);
      cleanup.run();
    }
  }

  private void runStep(final Step step) throws InterruptedException {
    try {
      step.render();
    } catch (final RuntimeException exception) {
      this.failureHandler.accept(exception);
    }
  }

  boolean isRunning() {
    return this.running.get();
  }

  @FunctionalInterface
  interface Step {
    void render() throws InterruptedException;
  }
}

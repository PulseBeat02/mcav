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
package me.brandonli.mcav.bukkit.media.mcv2;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The newest frame the video of a screen handed over that its encoder did not take yet. A newer frame replaces one
 * that was not taken, since the encoder only ever wants the newest; the encoder waits for a frame while the slot is
 * open, and closing the slot wakes it. Every method may be called from any thread.
 *
 * @param <T> the type of the frames
 */
final class Mcv2LatestFrame<T extends @NonNull Object> {

  private final Object lock;

  private @Nullable T frame;

  private boolean open;

  /**
   * Creates a closed slot without a frame.
   */
  Mcv2LatestFrame() {
    this.lock = new Object();
  }

  /**
   * Opens the slot, so the encoder waits for frames again.
   */
  void open() {
    synchronized (this.lock) {
      this.open = true;
    }
  }

  /**
   * Hands a frame over, in place of one not taken yet, and wakes an encoder that waits.
   *
   * @param newest the frame
   */
  void offer(final T newest) {
    synchronized (this.lock) {
      this.frame = newest;
      this.lock.notifyAll();
    }
  }

  /**
   * Drops the frame not taken yet, if there is one.
   */
  void clear() {
    synchronized (this.lock) {
      this.frame = null;
    }
  }

  /**
   * Takes the frame not taken yet, without waiting.
   *
   * @return the frame, or null if there is none
   */
  @Nullable T poll() {
    synchronized (this.lock) {
      final T taken = this.frame;
      this.frame = null;
      return taken;
    }
  }

  /**
   * Waits for a frame while the slot is open and takes it.
   *
   * @return the frame, or null once the slot is closed
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  @Nullable T take() throws InterruptedException {
    synchronized (this.lock) {
      while (this.open && this.frame == null) {
        this.lock.wait();
      }
      final T taken = this.frame;
      this.frame = null;
      return this.open ? taken : null;
    }
  }

  /**
   * Closes the slot and wakes an encoder that waits, which then takes nothing.
   */
  void close() {
    synchronized (this.lock) {
      this.open = false;
      this.lock.notifyAll();
    }
  }
}

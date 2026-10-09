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

final class Mcv2LatestFrame<FrameType extends @NonNull Object> {

  private final Object lock;

  private @Nullable FrameType frame;

  private boolean open;

  Mcv2LatestFrame() {
    this.lock = new Object();
  }

  void open() {
    synchronized (this.lock) {
      this.open = true;
    }
  }

  void offer(final FrameType newest) {
    synchronized (this.lock) {
      this.frame = newest;
      this.lock.notifyAll();
    }
  }

  void clear() {
    synchronized (this.lock) {
      this.frame = null;
    }
  }

  @Nullable FrameType poll() {
    synchronized (this.lock) {
      final FrameType taken = this.frame;
      this.frame = null;
      return taken;
    }
  }

  @Nullable FrameType take() throws InterruptedException {
    synchronized (this.lock) {
      while (this.open && this.frame == null) {
        this.lock.wait();
      }
      final FrameType taken = this.frame;
      this.frame = null;
      return this.open ? taken : null;
    }
  }

  void close() {
    synchronized (this.lock) {
      this.open = false;
      this.lock.notifyAll();
    }
  }
}

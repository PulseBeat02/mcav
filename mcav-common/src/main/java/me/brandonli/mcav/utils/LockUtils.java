/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils;

import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

public final class LockUtils {

  private LockUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static boolean executeWithLock(final Lock lockable, final Runnable task) {
    lockable.lock();
    try {
      task.run();
      return true;
    } finally {
      lockable.unlock();
    }
  }

  public static <T> T executeWithLock(final Lock lockable, final Supplier<T> task) {
    lockable.lock();
    try {
      return task.get();
    } finally {
      lockable.unlock();
    }
  }
}

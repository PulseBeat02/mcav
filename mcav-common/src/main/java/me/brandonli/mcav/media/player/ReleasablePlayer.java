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
package me.brandonli.mcav.media.player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * The ReleasablePlayer interface defines a contract for releasing resources
 * associated with a player once it is no longer needed. This interface is useful
 * for ensuring proper resource management and avoiding resource leaks in media
 * player implementations.
 * <p>
 * Any class implementing this interface should provide an implementation for
 * the release method, which may throw exceptions if resource cleanup fails.
 * This is particularly important in applications where proper resource handling
 * is critical, such as media processing or playback.
 * <p>
 * Implementations of this interface should aim to release all allocated resources,
 * making the player instance unusable after the release method is invoked.
 */
@FunctionalInterface
public interface ReleasablePlayer {
  /**
   * Releases all resources associated with the current instance.
   * Once this method is invoked, the instance should no longer be used.
   * It ensures proper cleanup to prevent resource leaks.
   *
   * @return true if the resources were successfully released, false otherwise
   */
  boolean release();

  /**
   * Asynchronously releases all resources associated with the current instance
   * by delegating the operation to the {@link ForkJoinPool#commonPool()} executor.
   * Once the resources are released, the instance should no longer be used.
   *
   * @return a {@link CompletableFuture} that resolves to {@code true} if the resources
   * were successfully released, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> releaseAsync() {
    return this.releaseAsync(ForkJoinPool.commonPool());
  }

  /**
   * Asynchronously releases all resources associated with the current instance
   * using the provided {@link ExecutorService}. Once this method is invoked,
   * the instance should no longer be used. The execution of the release operation
   * will be performed in a non-blocking manner on the given executor.
   *
   * @param executor the {@link ExecutorService} to execute the release operation asynchronously
   * @return a {@link CompletableFuture} that resolves to {@code true} if the resources
   * were successfully released, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> releaseAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::release, executor);
  }
}

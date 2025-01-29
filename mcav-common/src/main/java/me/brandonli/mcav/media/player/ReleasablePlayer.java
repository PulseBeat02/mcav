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
package me.brandonli.mcav.media.player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * Represents a player that can release its resources.
 */
@FunctionalInterface
public interface ReleasablePlayer {
  /**
   * Releases all resources associated with the current instance.
   *
   * @return true if the resources were successfully released, false otherwise
   */
  boolean release();

  /**
   * Asynchronously releases all resources associated with the current instance.
   *
   * @return a {@link CompletableFuture} that resolves to {@code true} if the resources
   * were successfully released, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> releaseAsync() {
    return this.releaseAsync(ForkJoinPool.commonPool());
  }

  /**
   * Asynchronously releases all resources associated with the current instance
   * using the provided {@link ExecutorService}.
   *
   * @param executor the {@link ExecutorService} to execute the release operation asynchronously
   * @return a {@link CompletableFuture} that resolves to {@code true} if the resources
   * were successfully released, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> releaseAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::release, executor);
  }
}

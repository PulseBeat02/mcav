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
package me.brandonli.mcav.sandbox.command.interaction;

import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The maps an interactive player is shown on, together with the pipeline that dithers its frames onto them.
 */
final class Screen {

  private final CompressedMapResult maps;
  private final VideoPipelineStep pipeline;
  private final int firstMapId;
  private final long mapCount;
  private boolean cancelled;
  private boolean starting;
  private @Nullable CompletableFuture<Boolean> start;

  /**
   * Constructs the screen.
   *
   * @param maps     the maps
   * @param pipeline the pipeline to attach to the player
   * @param firstMapId the first map identifier of the screen
   * @param mapCount the number of consecutive maps in the screen
   */
  Screen(final CompressedMapResult maps, final VideoPipelineStep pipeline, final int firstMapId, final long mapCount) {
    this.maps = maps;
    this.pipeline = pipeline;
    this.firstMapId = firstMapId;
    this.mapCount = mapCount;
  }

  boolean ownsMap(final int mapId) {
    return mapId >= this.firstMapId && (long) mapId < this.firstMapId + this.mapCount;
  }

  synchronized boolean beginStart() {
    if (this.cancelled) {
      return false;
    }
    this.starting = true;
    return true;
  }

  synchronized boolean finishStart() {
    this.starting = false;
    return this.cancelled;
  }

  synchronized boolean isCancelled() {
    return this.cancelled;
  }

  void bindStart(final CompletableFuture<Boolean> future) {
    final boolean cancel;
    synchronized (this) {
      this.start = future;
      cancel = this.cancelled;
    }
    if (cancel) {
      future.cancel(false);
    }
  }

  // Returns whether the worker owns final player cleanup because startup is currently executing.
  boolean cancel() {
    final CompletableFuture<Boolean> future;
    final boolean running;
    synchronized (this) {
      this.cancelled = true;
      future = this.start;
      running = this.starting;
    }
    if (future != null) {
      future.cancel(false);
    }
    return running;
  }

  /**
   * Gets the maps the player is shown on.
   *
   * @return the maps
   */
  CompressedMapResult getMaps() {
    return this.maps;
  }

  /**
   * Gets the pipeline that dithers the frames of the player onto the maps.
   *
   * @return the pipeline
   */
  VideoPipelineStep getPipeline() {
    return this.pipeline;
  }
}

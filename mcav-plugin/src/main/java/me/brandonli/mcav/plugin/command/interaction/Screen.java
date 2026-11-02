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
package me.brandonli.mcav.plugin.command.interaction;

import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

final class Screen {

  private final FunctionalVideoFilter output;
  private final VideoPipelineStep pipeline;
  private final int firstMapId;
  private final long mapCount;
  private final WallPicture picture;
  private boolean cancelled;
  private boolean starting;
  private @Nullable CompletableFuture<Boolean> start;

  Screen(
    final FunctionalVideoFilter output,
    final VideoPipelineStep pipeline,
    final int firstMapId,
    final long mapCount,
    final WallPicture picture
  ) {
    this.output = output;
    this.pipeline = pipeline;
    this.firstMapId = firstMapId;
    this.mapCount = mapCount;
    this.picture = picture;
  }

  int @Nullable [] toSource(final int wallX, final int wallY) {
    return this.picture.toSource(wallX, wallY);
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

  void bindStart(final CompletableFuture<Boolean> future) {
    final boolean shouldCancel;
    synchronized (this) {
      this.start = future;
      shouldCancel = this.cancelled;
    }
    if (shouldCancel) {
      future.cancel(false);
    }
  }

  // true while the start runs: its worker then owns the player's final cleanup
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

  synchronized boolean isCancelled() {
    return this.cancelled;
  }

  FunctionalVideoFilter getOutput() {
    return this.output;
  }

  VideoPipelineStep getPipeline() {
    return this.pipeline;
  }
}

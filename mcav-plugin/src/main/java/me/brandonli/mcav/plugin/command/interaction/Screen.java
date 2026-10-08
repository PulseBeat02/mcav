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

/**
 * The maps an interactive player is shown on, together with the pipeline that brings its frames onto them: dithered, or
 * encoded with MCV2 for the viewers with the pack.
 */
final class Screen {

  private final FunctionalVideoFilter output;
  private final VideoPipelineStep pipeline;
  private final int firstMapId;
  private final long mapCount;
  private final WallPicture picture;
  private boolean cancelled;
  private boolean starting;
  private @Nullable CompletableFuture<Boolean> start;

  /**
   * Constructs the screen.
   *
   * @param output     the output the frames go to: the dithered maps or the MCV2 screen, which releasing releases
   * @param pipeline   the pipeline to attach to the player
   * @param firstMapId the first map identifier of the screen
   * @param mapCount   the number of consecutive maps in the screen
   * @param picture    how the picture of the source sits on the wall
   */
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

  /**
   * Gets the pixel of the source a player clicks at a pixel of the wall.
   *
   * @param wallX the column of the pixel on the wall
   * @param wallY the row of the pixel on the wall
   * @return the column and row of the source's pixel, or null for the empty border around a smaller picture
   */
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

  synchronized boolean isCancelled() {
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
   * Gets the output the frames go to.
   *
   * @return the dithered maps or the MCV2 screen
   */
  FunctionalVideoFilter getOutput() {
    return this.output;
  }

  /**
   * Gets the pipeline that brings the frames of the player onto the maps.
   *
   * @return the pipeline
   */
  VideoPipelineStep getPipeline() {
    return this.pipeline;
  }
}

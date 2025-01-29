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
package me.brandonli.mcav.media.player.image;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FrameSource;

public interface ImagePlayer extends ReleasablePlayer {
  boolean start(final VideoPipelineStep videoPipeline, final FrameSource source);

  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final FrameSource source,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, source), service);
  }

  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final FrameSource source) {
    return this.startAsync(videoPipeline, source, ForkJoinPool.commonPool());
  }

  static ImagePlayer player() {
    return new ImagePlayerImpl();
  }
}

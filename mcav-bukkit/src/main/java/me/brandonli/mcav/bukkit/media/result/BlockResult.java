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
package me.brandonli.mcav.bukkit.media.result;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.render.BlockRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;

/**
 * A video filter that displays every frame as a wall of colored blocks using fake block changes.
 *
 * <p>Call {@link #start()} before playback and {@link #release()} afterward. Blocks update at most once per server
 * tick, and only blocks that changed are sent. The filter may run on any thread.
 */
public class BlockResult implements FunctionalVideoFilter {

  private final BlockRenderer renderer;

  /**
   * Constructs a new {@code BlockResult}.
   *
   * @param configuration the configuration describing the viewers, position, and size of the block wall
   */
  public BlockResult(final BlockConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Block configuration must not be null");
    this.renderer = new BlockRenderer(configuration);
  }

  /**
   * Remembers the blocks at the location of the wall, so they can be restored later.
   */
  @Override
  public void start() {
    this.renderer.show();
  }

  /**
   * Converts the frame into blocks and queues them for the next server tick. Only blocks that changed since the
   * previous frame are sent to the viewers.
   *
   * @param data     the frame to display, which may be resized by the renderer
   * @param metadata the metadata of the original video, which is not used
   * @return always true, because the renderer may resize the frame
   */
  @Override
  public boolean applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(data, "Frame must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    this.renderer.render(data);
    return true;
  }

  /**
   * Shows the original blocks to the viewers again. Call this method on the main thread during shutdown, because a
   * disabled plugin cannot schedule the restore anymore.
   */
  @Override
  public void release() {
    this.renderer.hide();
  }
}

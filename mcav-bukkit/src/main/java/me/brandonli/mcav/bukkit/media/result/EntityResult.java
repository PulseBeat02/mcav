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
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.render.EntityRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import org.bukkit.entity.TextDisplay;

/**
 * A video filter that displays every frame as colored text inside a {@link TextDisplay} entity.
 *
 * <p>Call {@link #start()} before playback and {@link #release()} afterward. The entity updates at most once per
 * server tick. The filter may run on any thread.
 */
public class EntityResult implements FunctionalVideoFilter {

  private final EntityRenderer renderer;

  /**
   * Constructs a new {@code EntityResult}.
   *
   * @param configuration the configuration describing the viewers, character, position, and size of the entity
   */
  public EntityResult(final EntityConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Entity configuration must not be null");
    this.renderer = new EntityRenderer(configuration);
  }

  /**
   * Spawns the text display and shows it to the viewers.
   */
  @Override
  public void start() {
    this.renderer.show();
  }

  /**
   * Converts the frame into colored text and queues it for the next server tick.
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
   * Removes the text display. Call this method on the main thread during shutdown, because a disabled plugin cannot
   * schedule the removal anymore.
   */
  @Override
  public void release() {
    this.renderer.hide();
  }
}

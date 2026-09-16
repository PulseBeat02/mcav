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
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.render.ScoreboardRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;

/**
 * A video filter that displays every frame on the sidebar scoreboard of the viewers.
 *
 * <p>Call {@link #start()} before playback and {@link #release()} afterward. The scoreboard updates at most once
 * per server tick. The filter may run on any thread.
 */
public class ScoreboardResult implements FunctionalVideoFilter {

  private final ScoreboardRenderer renderer;

  /**
   * Constructs a new {@code ScoreboardResult}.
   *
   * @param configuration the configuration describing the viewers, character, and size of the scoreboard image
   */
  public ScoreboardResult(final ScoreboardConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Scoreboard configuration must not be null");
    this.renderer = new ScoreboardRenderer(configuration);
  }

  /**
   * Creates the scoreboard and shows it to the viewers.
   */
  @Override
  public void start() {
    this.renderer.show();
  }

  /**
   * Converts the frame into colored scoreboard lines and queues them for the next server tick.
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
   * Removes the scoreboard and restores the previous scoreboard of every viewer. Call this method on the main thread
   * during shutdown, because a disabled plugin cannot schedule the restore anymore.
   */
  @Override
  public void release() {
    this.renderer.hide();
  }
}

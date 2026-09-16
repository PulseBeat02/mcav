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

import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * The maps an interactive player is shown on, together with the pipeline that dithers its frames onto them.
 */
final class Screen {

  private final CompressedMapResult maps;
  private final VideoPipelineStep pipeline;

  /**
   * Constructs the screen.
   *
   * @param maps     the maps
   * @param pipeline the pipeline to attach to the player
   */
  Screen(final CompressedMapResult maps, final VideoPipelineStep pipeline) {
    this.maps = maps;
    this.pipeline = pipeline;
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

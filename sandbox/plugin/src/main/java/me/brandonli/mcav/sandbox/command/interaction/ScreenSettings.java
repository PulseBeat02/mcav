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

import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * The map screen an interactive player is shown on, as entered by the command sender.
 */
final class ScreenSettings {

  private final MultiplePlayerSelector viewers;
  private final Pair<Integer, Integer> blocks;
  private final Pair<Integer, Integer> resolution;
  private final int mapId;
  private final DitheringArgument dithering;

  /**
   * Constructs the settings of a screen.
   *
   * @param viewers    the players who see the screen
   * @param blocks     the width and height of the screen in blocks
   * @param resolution the width and height of the picture in pixels
   * @param mapId      the id of the first map of the screen
   * @param dithering  the dithering algorithm that reduces the picture to map colors
   */
  ScreenSettings(
    final MultiplePlayerSelector viewers,
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final DitheringArgument dithering
  ) {
    this.viewers = viewers;
    this.blocks = blocks;
    this.resolution = resolution;
    this.mapId = mapId;
    this.dithering = dithering;
  }

  /**
   * Gets the players who see the screen.
   *
   * @return the player selector
   */
  MultiplePlayerSelector getViewers() {
    return this.viewers;
  }

  /**
   * Gets the width and height of the screen in blocks.
   *
   * @return the size in blocks
   */
  Pair<Integer, Integer> getBlocks() {
    return this.blocks;
  }

  /**
   * Gets the width and height of the picture in pixels.
   *
   * @return the resolution
   */
  Pair<Integer, Integer> getResolution() {
    return this.resolution;
  }

  /**
   * Gets the id of the first map of the screen.
   *
   * @return the map id
   */
  int getMapId() {
    return this.mapId;
  }

  /**
   * Gets the dithering algorithm that reduces the picture to map colors.
   *
   * @return the dithering argument
   */
  DitheringArgument getDithering() {
    return this.dithering;
  }
}

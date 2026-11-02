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

import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

final class ScreenSettings {

  private final CommandSender sender;
  private final MultiplePlayerSelector viewers;
  private final Pair<Integer, Integer> blocks;
  private final Pair<Integer, Integer> resolution;
  private final int mapId;
  private final DitheringArgument dithering;
  private final MapCodec codec;

  ScreenSettings(
    final CommandSender sender,
    final MultiplePlayerSelector viewers,
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final DitheringArgument dithering,
    final MapCodec codec
  ) {
    this.sender = sender;
    this.viewers = viewers;
    this.blocks = blocks;
    this.resolution = resolution;
    this.mapId = mapId;
    this.dithering = dithering;
    this.codec = codec;
  }

  CommandSender getSender() {
    return this.sender;
  }

  MultiplePlayerSelector getViewers() {
    return this.viewers;
  }

  Pair<Integer, Integer> getBlocks() {
    return this.blocks;
  }

  Pair<Integer, Integer> getResolution() {
    return this.resolution;
  }

  int getMapId() {
    return this.mapId;
  }

  DitheringArgument getDithering() {
    return this.dithering;
  }

  MapCodec getCodec() {
    return this.codec;
  }
}

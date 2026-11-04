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

import com.google.common.base.MoreObjects;
import java.util.Objects;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
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

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final ScreenSettings settings)) {
      return false;
    }

    return (
      this.sender.equals(settings.sender) &&
      this.viewers.equals(settings.viewers) &&
      this.blocks.equals(settings.blocks) &&
      this.resolution.equals(settings.resolution) &&
      this.mapId == settings.mapId &&
      this.dithering == settings.dithering &&
      this.codec == settings.codec
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.sender, this.viewers, this.blocks, this.resolution, this.mapId, this.dithering, this.codec);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("sender", this.sender)
      .add("viewers", this.viewers)
      .add("blocks", this.blocks)
      .add("resolution", this.resolution)
      .add("mapId", this.mapId)
      .add("dithering", this.dithering)
      .add("codec", this.codec)
      .toString();
  }
}

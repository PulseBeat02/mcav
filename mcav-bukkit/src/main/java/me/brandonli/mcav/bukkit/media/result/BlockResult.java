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

import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Represents a filter for displaying frames as blocks.
 */
public class BlockResult implements FunctionalVideoFilter {

  private final BlockConfiguration blockConfiguration;
  private final FilterLiteDither dither;

  private Location[] locationCache;

  /**
   * Constructs a new instance of the {@code BlockResult} class using the provided
   * {@code BlockConfiguration}.
   *
   * @param configuration the configuration object that defines the properties of the
   *                      block filter, including viewers, position, and block dimensions.
   */
  public BlockResult(final BlockConfiguration configuration) {
    this.blockConfiguration = configuration;
    this.dither = BlockPaletteLookup.getDitheringImpl();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("UnstableApiUsage")
  public void applyFilter(final ImageBuffer data, final VideoMetadata metadata) {
    final int blockWidth = this.blockConfiguration.getBlockWidth();
    final int blockHeight = this.blockConfiguration.getBlockHeight();
    final ResizeFilter resize = new ResizeFilter(blockWidth, blockHeight);
    resize.applyFilter(data, metadata);

    final int[] resizedData = data.getPixels();
    final int length = resizedData.length;
    this.dither.dither(resizedData, blockWidth);

    final Material[] materials = new Material[length];
    for (int i = 0; i < materials.length; i++) {
      materials[i] = BlockPaletteLookup.getMaterial(resizedData[i]);
    }

    final Collection<BlockState> blockStates = new HashSet<>();
    for (int i = 0; i < materials.length; i++) {
      final Location location = this.locationCache[i];
      final BlockData blockData = materials[i].createBlockData();
      final BlockState blockState = blockData.createBlockState();
      final BlockState copy = blockState.copy(location);
      blockStates.add(copy);
    }

    final Collection<UUID> viewers = this.blockConfiguration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.sendBlockChanges(blockStates);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final int blockWidth = this.blockConfiguration.getBlockWidth();
    final int blockHeight = this.blockConfiguration.getBlockHeight();
    final Location origin = this.blockConfiguration.getPosition();
    this.locationCache = new Location[blockWidth * blockHeight];
    for (int i = 0; i < this.locationCache.length; i++) {
      final int x = i % blockWidth;
      final int y = i / blockWidth;
      final int adjustedX = x - (blockWidth / 2);
      final int adjustedY = blockHeight - 1 - y;
      final Location clone = origin.clone();
      this.locationCache[i] = clone.add(adjustedX, adjustedY, 0);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    final BlockData empty = Material.AIR.createBlockData();
    final BlockState emptyState = empty.createBlockState();
    final Collection<BlockState> blockStates = new HashSet<>();
    for (final Location location : this.locationCache) {
      final BlockState copy = emptyState.copy(location);
      blockStates.add(copy);
    }
    final Collection<UUID> viewers = this.blockConfiguration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.sendBlockChanges(blockStates);
    }
  }
}

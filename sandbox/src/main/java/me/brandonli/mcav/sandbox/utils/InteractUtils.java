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
package me.brandonli.mcav.sandbox.utils;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class InteractUtils {

  private InteractUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static int@Nullable[] getBoardCoordinates(final Player player) {
    final Entity firstEntity = player.getTargetEntity(100);
    if (firstEntity == null) {
      return null;
    }
    return getBoardCoordinates(player, firstEntity);
  }

  public static int@Nullable[] getBoardCoordinates(final Player player, final Entity entity) {
    if (!(entity instanceof final ItemFrame frame)) {
      throw new IllegalArgumentException("Target entity is not an ItemFrame");
    }

    final double[] xzCoordinates = getAdjustedXZCoordinates(player, frame);
    final double x_res = xzCoordinates[0] * 128;
    final double z_res = xzCoordinates[1] * 128;

    final int[] relativeMapIndex = getRelativeMapIndex(player, frame);
    if (relativeMapIndex == null) {
      return null;
    }

    final int mapGridX = relativeMapIndex[0];
    final int mapGridY = relativeMapIndex[1];
    final int absoluteX = mapGridX * 128 + (int) x_res;
    final int absoluteY = mapGridY * 128 + (int) z_res;

    return new int[] { absoluteX, absoluteY };
  }

  private static int@Nullable[] getRelativeMapIndex(final Player player, final ItemFrame frame) {
    final ItemFrame@Nullable[] result = getCorners(player);
    if (result == null) {
      return null;
    }
    final ItemFrame firstCorner = result[0];
    final Location firstCornerLoc = firstCorner.getLocation();
    final Location hitFrameLoc = frame.getLocation();
    final BlockFace hitFace = player.getTargetBlockFace(100);
    int mapGridX = 0;
    int mapGridY = 0;
    if (hitFace == BlockFace.NORTH || hitFace == BlockFace.SOUTH) {
      mapGridX = Math.abs(hitFrameLoc.getBlockX() - firstCornerLoc.getBlockX());
      mapGridY = Math.abs(hitFrameLoc.getBlockY() - firstCornerLoc.getBlockY());
    } else if (hitFace == BlockFace.EAST || hitFace == BlockFace.WEST) {
      mapGridX = Math.abs(hitFrameLoc.getBlockZ() - firstCornerLoc.getBlockZ());
      mapGridY = Math.abs(hitFrameLoc.getBlockY() - firstCornerLoc.getBlockY());
    } else if (hitFace == BlockFace.UP || hitFace == BlockFace.DOWN) {
      mapGridX = Math.abs(hitFrameLoc.getBlockX() - firstCornerLoc.getBlockX());
      mapGridY = Math.abs(hitFrameLoc.getBlockZ() - firstCornerLoc.getBlockZ());
    }
    return new int[] { mapGridX, mapGridY };
  }

  private static double[] getAdjustedXZCoordinates(final Player player, final ItemFrame frame) {
    final double[] xzCoordinates = getXZCoordinates(player, frame);
    double x_map_scale = xzCoordinates[0];
    double z_map_scale = xzCoordinates[1];
    final Rotation rotation = frame.getRotation();
    if (rotation.equals(Rotation.FLIPPED_45) || rotation.equals(Rotation.CLOCKWISE_45)) {
      final double copy_z = z_map_scale;
      z_map_scale = 1 - x_map_scale;
      x_map_scale = copy_z;
    } else if (rotation.equals(Rotation.COUNTER_CLOCKWISE) || rotation.equals(Rotation.CLOCKWISE)) {
      x_map_scale = 1 - x_map_scale;
      z_map_scale = 1 - z_map_scale;
    } else if (rotation.equals(Rotation.COUNTER_CLOCKWISE_45) || rotation.equals(Rotation.CLOCKWISE_135)) {
      final double copy_x = x_map_scale;
      x_map_scale = 1 - z_map_scale;
      z_map_scale = copy_x;
    }
    return new double[] { x_map_scale, z_map_scale };
  }

  private static double[] getXZCoordinates(final Player player, final ItemFrame frame) {
    final World world = player.getWorld();
    final Location playerLoc = player.getEyeLocation();
    final BlockFace face = frame.getAttachedFace();
    double x_map_scale = 0.0, z_map_scale = 0.0;
    if (face.equals(BlockFace.EAST) || face.equals(BlockFace.WEST)) {
      Vector vector = playerLoc.getDirection().normalize();
      final double x_diff = frame.getLocation().getX() - playerLoc.getX();
      vector = vector.multiply(Math.abs(x_diff / vector.getX()));
      final Location clicked_location = playerLoc.toVector().add(vector).toLocation(world);
      z_map_scale = clicked_location.getY() - (int) clicked_location.getY();
      if (z_map_scale < 0) {
        z_map_scale++;
      }
      z_map_scale = 1 - z_map_scale;
      x_map_scale = clicked_location.getZ() - (int) clicked_location.getZ();
      if (x_map_scale < 0) {
        x_map_scale++;
      }
      if (face.equals(BlockFace.WEST)) {
        x_map_scale = 1 - x_map_scale;
      }
    } else if (face.equals(BlockFace.NORTH) || face.equals(BlockFace.SOUTH)) {
      Vector vector = playerLoc.getDirection().normalize();
      final double z_diff = frame.getLocation().getZ() - playerLoc.getZ();
      vector = vector.multiply(Math.abs(z_diff / vector.getZ()));
      final Location clicked_location = playerLoc.toVector().add(vector).toLocation(world);
      z_map_scale = clicked_location.getY() - (int) clicked_location.getY();
      if (z_map_scale < 0) {
        z_map_scale++;
      }
      z_map_scale = 1 - z_map_scale;
      x_map_scale = clicked_location.getX() - (int) clicked_location.getX();
      if (x_map_scale < 0) {
        x_map_scale++;
      }
      if (face.equals(BlockFace.SOUTH)) {
        x_map_scale = 1 - x_map_scale;
      }
    }
    return new double[] { x_map_scale, z_map_scale };
  }

  private static ItemFrame@Nullable[] getCorners(final Player player) {
    final Block targetBlock = player.getTargetBlock(null, 100);
    final BlockFace hitFace = player.getTargetBlockFace(100);
    final World world = player.getWorld();
    final Set<ItemFrame> boardFrames = new HashSet<>();
    final Set<Block> visited = new HashSet<>();
    final Queue<Block> queue = new LinkedList<>();
    queue.add(targetBlock);

    while (!queue.isEmpty()) {
      final Block currentBlock = queue.poll();
      if (currentBlock == null || !visited.add(currentBlock)) {
        continue;
      }
      final Location blockLocation = currentBlock.getLocation();
      final Location added = blockLocation.add(0.5, 0.5, 0.5);
      final Collection<Entity> nearbyEntities = world.getNearbyEntities(added, 2, 2, 2);
      for (final Entity entity : nearbyEntities) {
        if (!(entity instanceof final ItemFrame frame) || frame.getFacing() != hitFace) {
          continue;
        }
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
          final Location location = frame.getLocation();
          final Block block = location.getBlock();
          boardFrames.add(frame);
          queue.add(block);
        }
      }
      visited.add(currentBlock);
    }

    ItemFrame firstCorner = null;
    ItemFrame lastCorner = null;
    for (final ItemFrame frame : boardFrames) {
      final PersistentDataContainer data = frame.getPersistentDataContainer();
      if (data.has(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN)) {
        firstCorner = frame;
      }
      if (data.has(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN)) {
        lastCorner = frame;
      }
    }

    if (firstCorner == null || lastCorner == null) {
      return null;
    }

    return new ItemFrame[] { firstCorner, lastCorner };
  }
}

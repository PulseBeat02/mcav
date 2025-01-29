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

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import org.bukkit.Location;
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

  private static final BlockFace[] BLOCK_FACES = new BlockFace[] {
    BlockFace.NORTH,
    BlockFace.EAST,
    BlockFace.SOUTH,
    BlockFace.WEST,
    BlockFace.UP,
    BlockFace.DOWN,
  };

  private InteractUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static class Plane {

    private final Vector normal;
    private final Vector point;
    private final double d; // Plane equation: ax + by + cz + d = 0

    public Plane(final Vector normal, final Vector point) {
      this.normal = normal.clone().normalize();
      this.point = point.clone();
      this.d = -normal.dot(point); // Calculate d coefficient in plane equation
    }

    public @Nullable Vector rayIntersection(final Vector rayOrigin, final Vector rayDirection) {
      final double denom = this.normal.dot(rayDirection);
      if (Math.abs(denom) < 1e-6) {
        return null;
      }
      final double t = -(this.normal.dot(rayOrigin) + this.d) / denom;
      if (t < 0) {
        return null;
      }
      return rayOrigin.clone().add(rayDirection.clone().multiply(t));
    }
  }

  private static class PlaneCoordinateSystem {

    private final Vector origin;
    private final Vector xAxis;
    private final Vector yAxis;
    private final double width;
    private final double height;

    public PlaneCoordinateSystem(final Vector origin, final Vector xAxis, final Vector yAxis, final double width, final double height) {
      this.origin = origin.clone();
      this.xAxis = xAxis.clone().normalize();
      this.yAxis = yAxis.clone().normalize();
      this.width = width;
      this.height = height;
    }

    public int[] pointToCoordinates(final Vector point) {
      final Vector relative = point.clone().subtract(this.origin);
      final double xCoord = relative.dot(this.xAxis) / this.width;
      final double yCoord = relative.dot(this.yAxis) / this.height;
      final int mapX = (int) (xCoord * 128);
      final int mapY = (int) ((1 - yCoord) * 128);
      return new int[] { mapX, mapY };
    }
  }

  public static int[] getBoardCoordinates(final Player clicker) {
    final BoardInfo boardInfo = findBoard(clicker);
    final Location eyeLoc = clicker.getEyeLocation();
    final Vector rayOrigin = eyeLoc.toVector();
    final Vector rayDirection = eyeLoc.getDirection().normalize();
    final Vector intersection = boardInfo.plane.rayIntersection(rayOrigin, rayDirection);
    if (intersection == null) {
      throw new IllegalArgumentException("You're not looking at the board");
    }

    final Vector firstCornerPos = toVector(boardInfo.firstCorner.getLocation());
    final Vector lastCornerPos = toVector(boardInfo.lastCorner.getLocation());
    final Vector normal = getFaceNormal(boardInfo.firstCorner.getFacing());
    final Vector relativePos = intersection.clone().subtract(firstCornerPos);
    final int mapX;
    final int mapY;
    if (Math.abs(normal.getX()) > 0.9) {
      final double percentZ = relativePos.getZ() / (lastCornerPos.getZ() - firstCornerPos.getZ());
      final double percentY = relativePos.getY() / (lastCornerPos.getY() - firstCornerPos.getY());
      mapX = (int) (percentZ * 128);
      mapY = (int) ((1 - percentY) * 128);
    } else if (Math.abs(normal.getY()) > 0.9) {
      final double percentX = relativePos.getX() / (lastCornerPos.getX() - firstCornerPos.getX());
      final double percentZ = relativePos.getZ() / (lastCornerPos.getZ() - firstCornerPos.getZ());
      mapX = (int) (percentX * 128);
      mapY = (int) ((1 - percentZ) * 128);
    } else {
      final double percentX = relativePos.getX() / (lastCornerPos.getX() - firstCornerPos.getX());
      final double percentY = relativePos.getY() / (lastCornerPos.getY() - firstCornerPos.getY());
      mapX = (int) (percentX * 128);
      mapY = (int) ((1 - percentY) * 128);
    }

    return new int[] { mapX, mapY };
  }

  public static int[] getBoardCoordinates(final Player player, final Block brokenBlock) {
    final BlockFace face = determineBlockFace(requireNonNull(player.getLocation()).getDirection());
    return getBoardCoordinates(player, brokenBlock, face);
  }

  private static int[] getBoardCoordinates(final Player player, final Block brokenBlock, final BlockFace hitFace) {
    final BoardInfo boardInfo = findBoardFromBlock(brokenBlock, hitFace);
    final Location eyeLoc = player.getEyeLocation();
    final Vector rayOrigin = eyeLoc.toVector();
    final Vector rayDirection = eyeLoc.getDirection().normalize();
    final Vector intersection = boardInfo.plane.rayIntersection(rayOrigin, rayDirection);
    if (intersection == null) {
      throw new IllegalArgumentException("You're not looking at the board");
    }
    return boardInfo.coordSystem.pointToCoordinates(intersection);
  }

  private static class BoardInfo {

    final Plane plane;
    final PlaneCoordinateSystem coordSystem;
    final ItemFrame firstCorner;
    final ItemFrame lastCorner;

    BoardInfo(final Plane plane, final PlaneCoordinateSystem coordSystem, final ItemFrame firstCorner, final ItemFrame lastCorner) {
      this.plane = plane;
      this.coordSystem = coordSystem;
      this.firstCorner = firstCorner;
      this.lastCorner = lastCorner;
    }
  }

  private static BoardInfo findBoard(final Player player) {
    final Block targetBlock = player.getTargetBlock(null, 100);
    BlockFace hitFace;
    try {
      hitFace = player.getTargetBlockFace(100);
    } catch (final Exception e) {
      hitFace = determineBlockFace(requireNonNull(player.getLocation()).getDirection());
    }
    if (hitFace == null) {
      throw new IllegalArgumentException("Could not determine which face you're looking at");
    }
    return findBoardFromBlock(targetBlock, hitFace);
  }

  private static BoardInfo findBoardFromBlock(final Block startBlock, final BlockFace hitFace) {
    final World world = startBlock.getWorld();

    Block searchBlock = startBlock;
    if (hitFace != BlockFace.UP && hitFace != BlockFace.DOWN) {
      searchBlock = startBlock.getRelative(hitFace);
    }

    final Set<ItemFrame> boardFrames = new HashSet<>();
    final Set<Block> visited = new HashSet<>();
    final Queue<Block> queue = new LinkedList<>();
    queue.add(searchBlock);

    while (!queue.isEmpty()) {
      final Block currentBlock = queue.poll();
      if (currentBlock == null || !visited.add(currentBlock)) {
        continue;
      }
      for (final Entity entity : world.getNearbyEntities(currentBlock.getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
        if (!(entity instanceof final ItemFrame frame) || frame.getFacing() != hitFace) {
          continue;
        }
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
          boardFrames.add(frame);
          for (final BlockFace face : BLOCK_FACES) {
            final Block neighbor = currentBlock.getRelative(face);
            if (visited.add(neighbor)) {
              queue.add(neighbor);
            }
          }
        }
      }
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
      throw new IllegalArgumentException("Could not find both corners of the map screen");
    }

    final Vector normal = getFaceNormal(hitFace);
    final Vector topLeft = toVector(firstCorner.getLocation());
    final Vector bottomRight = toVector(lastCorner.getLocation());

    final Plane plane = new Plane(normal, topLeft);
    final Vector xAxis;
    final Vector yAxis;
    final double width;
    final double height;

    if (Math.abs(normal.getX()) > 0.9) {
      xAxis = new Vector(0, 0, 1);
      yAxis = new Vector(0, -1, 0);
      width = Math.abs(bottomRight.getZ() - topLeft.getZ());
      height = Math.abs(bottomRight.getY() - topLeft.getY());
    } else if (Math.abs(normal.getY()) > 0.9) {
      xAxis = new Vector(1, 0, 0);
      yAxis = new Vector(0, 0, 1);
      width = Math.abs(bottomRight.getX() - topLeft.getX());
      height = Math.abs(bottomRight.getZ() - topLeft.getZ());
    } else {
      xAxis = new Vector(1, 0, 0);
      yAxis = new Vector(0, -1, 0);
      width = Math.abs(bottomRight.getX() - topLeft.getX());
      height = Math.abs(bottomRight.getY() - topLeft.getY());
    }

    final PlaneCoordinateSystem coordSystem = new PlaneCoordinateSystem(topLeft, xAxis, yAxis, width, height);

    return new BoardInfo(plane, coordSystem, firstCorner, lastCorner);
  }

  private static Vector getFaceNormal(final BlockFace face) {
    return new Vector(face.getModX(), face.getModY(), face.getModZ()).normalize();
  }

  private static Vector toVector(final Location location) {
    return new Vector(location.getX(), location.getY(), location.getZ());
  }

  private static BlockFace determineBlockFace(final Vector direction) {
    final double x = direction.getX();
    final double y = direction.getY();
    final double z = direction.getZ();
    if (Math.abs(x) > Math.abs(y) && Math.abs(x) > Math.abs(z)) {
      return x > 0 ? BlockFace.EAST : BlockFace.WEST;
    } else if (Math.abs(y) > Math.abs(z)) {
      return y > 0 ? BlockFace.UP : BlockFace.DOWN;
    } else {
      return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
  }
}

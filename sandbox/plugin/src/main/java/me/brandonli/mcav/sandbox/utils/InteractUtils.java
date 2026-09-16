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
package me.brandonli.mcav.sandbox.utils;

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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

/**
 * Translates where a player looks on a wall of map item frames into pixel coordinates of the image on the wall.
 *
 * <p>The frames of a wall are tagged with {@link Keys#MAP_KEY}, and its top left and bottom right frames are
 * additionally tagged with {@link Keys#FIRST_MAP_KEY} and {@link Keys#LAST_MAP_KEY}. The pixel coordinates are
 * measured from the top left corner of the wall, with every map being {@value #MAP_PIXELS} pixels wide.
 */
public final class InteractUtils {

  private static final int MAP_PIXELS = 128;
  private static final int MAX_TARGET_DISTANCE = 100;
  private static final double FRAME_SEARCH_RADIUS = 2.0;
  private static final double BLOCK_CENTER_OFFSET = 0.5;

  private InteractUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the pixel the player looks at on the map wall in front of them.
   *
   * @param player the player
   * @return the x and y pixel coordinates on the wall, or {@code null} if the player does not look at a map frame
   * of a complete wall
   */
  public static int@Nullable[] getBoardCoordinates(final Player player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    final Entity targetEntity = player.getTargetEntity(MAX_TARGET_DISTANCE);
    if (targetEntity == null) {
      return null;
    }
    return getBoardCoordinates(player, targetEntity);
  }

  /**
   * Gets the pixel the player looks at on the specified map frame.
   *
   * @param player the player
   * @param entity the item frame the player looks at
   * @return the x and y pixel coordinates on the wall, or {@code null} if the frame does not belong to a complete
   * wall
   * @throws IllegalArgumentException if the entity is not an item frame
   */
  public static int@Nullable[] getBoardCoordinates(final Player player, final Entity entity) {
    Preconditions.checkNotNull(player, "Player must not be null");
    Preconditions.checkNotNull(entity, "Entity must not be null");
    if (!(entity instanceof final ItemFrame frame)) {
      throw new IllegalArgumentException("Target entity is not an ItemFrame");
    }
    final int[] mapIndex = getRelativeMapIndex(player, frame);
    if (mapIndex == null) {
      return null;
    }
    final double[] framePosition = getRotatedFramePosition(player, frame);
    final int pixelX = (int) (framePosition[0] * MAP_PIXELS);
    final int pixelY = (int) (framePosition[1] * MAP_PIXELS);
    final int absoluteX = mapIndex[0] * MAP_PIXELS + pixelX;
    final int absoluteY = mapIndex[1] * MAP_PIXELS + pixelY;
    return new int[] { absoluteX, absoluteY };
  }

  /**
   * Gets the column and row of the frame on its wall, counted from the first corner of the wall.
   *
   * @return the column and row, or {@code null} if the player does not look at a block face or the wall is not
   * complete
   */
  private static int@Nullable[] getRelativeMapIndex(final Player player, final ItemFrame frame) {
    final BlockFace hitFace = player.getTargetBlockFace(MAX_TARGET_DISTANCE);
    if (hitFace == null) {
      return null;
    }
    final ItemFrame firstCorner = findFirstCorner(player, hitFace);
    if (firstCorner == null) {
      return null;
    }

    final Location cornerLocation = firstCorner.getLocation();
    final Location frameLocation = frame.getLocation();
    return indexOnWall(hitFace, frameLocation, cornerLocation);
  }

  /**
   * Gets the column and row of a frame from its distance to the first corner of the wall, measured along the two
   * axes the wall spans.
   *
   * @return the column and row, or {@code null} if the wall does not face along an axis
   */
  private static int@Nullable[] indexOnWall(final BlockFace hitFace, final Location frameLocation, final Location cornerLocation) {
    final int frameX = frameLocation.getBlockX();
    final int frameY = frameLocation.getBlockY();
    final int frameZ = frameLocation.getBlockZ();
    final int cornerX = cornerLocation.getBlockX();
    final int cornerY = cornerLocation.getBlockY();
    final int cornerZ = cornerLocation.getBlockZ();
    final int deltaX = Math.abs(frameX - cornerX);
    final int deltaY = Math.abs(frameY - cornerY);
    final int deltaZ = Math.abs(frameZ - cornerZ);
    return switch (hitFace) {
      case NORTH, SOUTH -> new int[] { deltaX, deltaY };
      case EAST, WEST -> new int[] { deltaZ, deltaY };
      case UP, DOWN -> new int[] { deltaX, deltaZ };
      default -> null;
    };
  }

  /**
   * Gets the position the player looks at inside the frame, from 0 to 1 on both axes, taking the rotation of the
   * map inside the frame into account.
   */
  private static double[] getRotatedFramePosition(final Player player, final ItemFrame frame) {
    final double[] position = getFramePosition(player, frame);
    final double horizontal = position[0];
    final double vertical = position[1];
    final Rotation rotation = frame.getRotation();
    return switch (rotation) {
      case FLIPPED_45, CLOCKWISE_45 -> new double[] { vertical, 1 - horizontal };
      case COUNTER_CLOCKWISE, CLOCKWISE -> new double[] { 1 - horizontal, 1 - vertical };
      case COUNTER_CLOCKWISE_45, CLOCKWISE_135 -> new double[] { 1 - vertical, horizontal };
      default -> new double[] { horizontal, vertical };
    };
  }

  /**
   * Gets the position the player looks at inside the frame, from 0 to 1 on both axes, as if the map were not
   * rotated. Only frames on walls are supported; frames on floors and ceilings report the top left corner.
   */
  private static double[] getFramePosition(final Player player, final ItemFrame frame) {
    final BlockFace face = frame.getAttachedFace();
    final boolean alongX = face == BlockFace.EAST || face == BlockFace.WEST;
    final boolean alongZ = face == BlockFace.NORTH || face == BlockFace.SOUTH;
    if (!alongX && !alongZ) {
      return new double[] { 0.0, 0.0 };
    }
    final Vector lookedAt = findLookedAtPoint(player, frame, alongX);
    final double horizontalCoordinate = alongX ? lookedAt.getZ() : lookedAt.getX();
    final double horizontal = fractionalPart(horizontalCoordinate);
    final double verticalCoordinate = lookedAt.getY();
    final double verticalFraction = fractionalPart(verticalCoordinate);
    final boolean mirrored = face == BlockFace.WEST || face == BlockFace.SOUTH;
    final double x = mirrored ? 1 - horizontal : horizontal;
    final double y = 1 - verticalFraction;
    return new double[] { x, y };
  }

  /**
   * Follows the line of sight of the player to the plane of the frame.
   *
   * @param alongX true if the frame hangs on a wall facing east or west, false if it faces north or south
   */
  private static Vector findLookedAtPoint(final Player player, final ItemFrame frame, final boolean alongX) {
    final Location eyeLocation = player.getEyeLocation();
    final Location frameLocation = frame.getLocation();
    final Vector direction = eyeLocation.getDirection();
    direction.normalize();
    final double distance = alongX ? frameLocation.getX() - eyeLocation.getX() : frameLocation.getZ() - eyeLocation.getZ();
    final double directionComponent = alongX ? direction.getX() : direction.getZ();
    final double scale = Math.abs(distance / directionComponent);
    direction.multiply(scale);
    final Vector eyePosition = eyeLocation.toVector();
    return eyePosition.add(direction);
  }

  private static double fractionalPart(final double value) {
    return value - Math.floor(value);
  }

  /**
   * Finds the first corner of the wall the player looks at by walking from the targeted block through all
   * connected map frames that face the same direction.
   */
  private static @Nullable ItemFrame findFirstCorner(final Player player, final BlockFace hitFace) {
    final Set<ItemFrame> wallFrames = collectWallFrames(player, hitFace);
    final ItemFrame firstCorner = findTaggedFrame(wallFrames, Keys.FIRST_MAP_KEY);
    final ItemFrame lastCorner = findTaggedFrame(wallFrames, Keys.LAST_MAP_KEY);
    if (lastCorner == null) {
      return null;
    }
    return firstCorner;
  }

  private static Set<ItemFrame> collectWallFrames(final Player player, final BlockFace hitFace) {
    final Block targetBlock = player.getTargetBlock(null, MAX_TARGET_DISTANCE);
    final World world = player.getWorld();
    final Set<ItemFrame> wallFrames = new HashSet<>();
    // the block of a frame is only queued when the frame is found for the first time, so the search ends
    final Queue<Block> pending = new ArrayDeque<>();
    pending.add(targetBlock);
    while (!pending.isEmpty()) {
      final Block block = pending.remove();
      addNearbyWallFrames(world, block, hitFace, wallFrames, pending);
    }
    return wallFrames;
  }

  private static void addNearbyWallFrames(
    final World world,
    final Block block,
    final BlockFace hitFace,
    final Set<ItemFrame> wallFrames,
    final Queue<Block> pending
  ) {
    final Location blockLocation = block.getLocation();
    final Location center = blockLocation.add(BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET);
    final Collection<Entity> nearbyEntities = world.getNearbyEntities(
      center,
      FRAME_SEARCH_RADIUS,
      FRAME_SEARCH_RADIUS,
      FRAME_SEARCH_RADIUS
    );
    for (final Entity entity : nearbyEntities) {
      if (!(entity instanceof final ItemFrame frame) || frame.getFacing() != hitFace) {
        continue;
      }
      final boolean isMapFrame = hasTag(frame, Keys.MAP_KEY);
      if (isMapFrame && wallFrames.add(frame)) {
        final Location frameLocation = frame.getLocation();
        final Block frameBlock = frameLocation.getBlock();
        pending.add(frameBlock);
      }
    }
  }

  private static @Nullable ItemFrame findTaggedFrame(final Set<ItemFrame> frames, final NamespacedKey key) {
    ItemFrame tagged = null;
    for (final ItemFrame frame : frames) {
      if (hasTag(frame, key)) {
        tagged = frame;
      }
    }
    return tagged;
  }

  private static boolean hasTag(final ItemFrame frame, final NamespacedKey key) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    return container.has(key, PersistentDataType.BOOLEAN);
  }
}

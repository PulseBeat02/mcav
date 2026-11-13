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
package me.brandonli.mcav.plugin.utils;

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import org.bukkit.Axis;
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
 * additionally tagged with {@link Keys#FIRST_MAP_KEY} and {@link Keys#LAST_MAP_KEY}.
 * New screens also carry a shared {@link Keys#SCREEN_KEY}; neighboring legacy screens with ambiguous corners are
 * rejected. The pixel coordinates are measured from the top left corner of the wall, with every map being
 * {@value MapLayout#MAP_SIZE} pixels wide.
 *
 * <p>This class is stateless; call it on the server main thread, since it reads the entities of the world.
 *
 * @since 1.0.0
 */
public final class InteractUtils {

  private static final String PLAYER_NULL_MESSAGE = "Player must not be null!";

  private static final int MAXIMUM_TARGET_DISTANCE = 100;

  private static final double FRAME_SEARCH_RADIUS = 2.0;
  private static final double BLOCK_CENTER_OFFSET = 0.5;

  private InteractUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Returns the pixel the player looks at on the map wall in front of them.
   *
   * @param player the player
   * @return the x and y pixel coordinates on the wall, or empty if the player does not look at a map frame
   * of a complete vertical wall
   * @throws NullPointerException     if {@code player} is null
   * @throws IllegalArgumentException if the player looks at an entity that is not an item frame
   * @throws IllegalStateException    if the player targets a block face that is not a side of a block
   * @since 1.0.0
   */
  public static Optional<int[]> findBoardCoordinates(final Player player) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);

    final Entity targetEntity = player.getTargetEntity(MAXIMUM_TARGET_DISTANCE);
    if (targetEntity == null) {
      return Optional.empty();
    }

    return findBoardCoordinates(player, targetEntity);
  }

  /**
   * Returns the pixel the player looks at on the specified map frame.
   *
   * @param player the player
   * @param entity the item frame the player looks at
   * @return the x and y pixel coordinates on the wall, or empty if the frame does not belong to a complete
   * vertical wall or the line of sight misses the frame
   * @throws NullPointerException     if an argument is null
   * @throws IllegalArgumentException if the entity is not an item frame
   * @throws IllegalStateException    if the player targets a block face that is not a side of a block
   * @since 1.0.0
   */
  public static Optional<int[]> findBoardCoordinates(final Player player, final Entity entity) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);
    Preconditions.checkNotNull(entity, "Entity must not be null!");

    if (!(entity instanceof final ItemFrame frame)) {
      final String message = "Target entity is not an ItemFrame!";
      throw new IllegalArgumentException(message);
    }

    final int[] mapIndex = findRelativeMapIndex(player, frame);
    if (mapIndex == null) {
      return Optional.empty();
    }

    final double[] framePosition = findRotatedFramePosition(player, frame);
    if (framePosition == null) {
      return Optional.empty();
    }

    final int scaledX = (int) (framePosition[0] * MapLayout.MAP_SIZE);
    final int pixelX = Math.clamp(scaledX, 0, MapLayout.MAP_SIZE - 1);
    final int scaledY = (int) (framePosition[1] * MapLayout.MAP_SIZE);
    final int pixelY = Math.clamp(scaledY, 0, MapLayout.MAP_SIZE - 1);
    final int absoluteX = mapIndex[0] * MapLayout.MAP_SIZE + pixelX;
    final int absoluteY = mapIndex[1] * MapLayout.MAP_SIZE + pixelY;
    final int[] coordinates = new int[] { absoluteX, absoluteY };
    return Optional.of(coordinates);
  }

  private static int @Nullable [] findRelativeMapIndex(final Player player, final ItemFrame frame) {
    final BlockFace hitFace = player.getTargetBlockFace(MAXIMUM_TARGET_DISTANCE);
    if (hitFace == null) {
      return null;
    }

    final ItemFrame firstCorner = findFirstCorner(player, hitFace, frame);
    if (firstCorner == null) {
      return null;
    }

    final Location cornerLocation = firstCorner.getLocation();
    final Location frameLocation = frame.getLocation();
    return indexOnWall(hitFace, frameLocation, cornerLocation);
  }

  private static int[] indexOnWall(final BlockFace hitFace, final Location frameLocation, final Location cornerLocation) {
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
      case
        NORTH_EAST,
        NORTH_WEST,
        SOUTH_EAST,
        SOUTH_WEST,
        WEST_NORTH_WEST,
        NORTH_NORTH_WEST,
        NORTH_NORTH_EAST,
        EAST_NORTH_EAST,
        EAST_SOUTH_EAST,
        SOUTH_SOUTH_EAST,
        SOUTH_SOUTH_WEST,
        WEST_SOUTH_WEST,
        SELF -> {
        final String message = "Block face %s is not a side of a block!".formatted(hitFace);
        throw new IllegalStateException(message);
      }
    };
  }

  private static double @Nullable [] findRotatedFramePosition(final Player player, final ItemFrame frame) {
    final double[] position = findFramePosition(player, frame);
    if (position == null) {
      return null;
    }

    final double horizontal = position[0];
    final double vertical = position[1];
    final Rotation rotation = frame.getRotation();
    return switch (rotation) {
      case FLIPPED_45, CLOCKWISE_45 -> new double[] { vertical, 1 - horizontal };
      case COUNTER_CLOCKWISE, CLOCKWISE -> new double[] { 1 - horizontal, 1 - vertical };
      case COUNTER_CLOCKWISE_45, CLOCKWISE_135 -> new double[] { 1 - vertical, horizontal };
      case NONE, FLIPPED -> new double[] { horizontal, vertical };
    };
  }

  // as if the map were not rotated; null for misses and floor or ceiling frames
  private static double @Nullable [] findFramePosition(final Player player, final ItemFrame frame) {
    final BlockFace face = frame.getAttachedFace();
    final boolean alongX = face == BlockFace.EAST || face == BlockFace.WEST;
    final boolean alongZ = face == BlockFace.NORTH || face == BlockFace.SOUTH;
    if (!alongX && !alongZ) {
      return null;
    }

    final Axis axis = alongX ? Axis.X : Axis.Z;
    final Vector lookedAt = findLookedAtPoint(player, frame, axis);
    if (lookedAt == null) {
      return null;
    }

    final Location frameLocation = frame.getLocation();
    final double horizontalCoordinate = alongX ? lookedAt.getZ() : lookedAt.getX();
    final int horizontalOrigin = alongX ? frameLocation.getBlockZ() : frameLocation.getBlockX();
    final double horizontal = horizontalCoordinate - horizontalOrigin;
    final double verticalFraction = lookedAt.getY() - frameLocation.getBlockY();
    final boolean insideFrame = horizontal >= 0 && horizontal <= 1 && verticalFraction >= 0 && verticalFraction <= 1;
    if (!insideFrame) {
      return null;
    }

    final boolean mirrored = face == BlockFace.WEST || face == BlockFace.SOUTH;
    final double fromLeft = mirrored ? 1 - horizontal : horizontal;
    final double fromTop = 1 - verticalFraction;
    return new double[] { fromLeft, fromTop };
  }

  private static @Nullable Vector findLookedAtPoint(final Player player, final ItemFrame frame, final Axis axis) {
    final Location eyeLocation = player.getEyeLocation();
    final Location frameLocation = frame.getLocation();
    final Vector direction = eyeLocation.getDirection();
    direction.normalize();

    final double distance = axis == Axis.X ? frameLocation.getX() - eyeLocation.getX() : frameLocation.getZ() - eyeLocation.getZ();
    final double directionComponent = axis == Axis.X ? direction.getX() : direction.getZ();
    final double scale = distance / directionComponent;
    if (!Double.isFinite(scale) || scale < 0) {
      return null;
    }

    direction.multiply(scale);

    final Vector eyePosition = eyeLocation.toVector();
    return eyePosition.add(direction);
  }

  private static @Nullable ItemFrame findFirstCorner(final Player player, final BlockFace hitFace, final ItemFrame target) {
    final Set<ItemFrame> wallFrames = collectWallFrames(player, hitFace, target);
    final boolean onWall = wallFrames.contains(target);
    if (!onWall) {
      return null;
    }

    final ItemFrame firstCorner = findTaggedFrame(wallFrames, Keys.FIRST_MAP_KEY);
    final ItemFrame lastCorner = findTaggedFrame(wallFrames, Keys.LAST_MAP_KEY);
    if (lastCorner == null) {
      return null;
    }

    return firstCorner;
  }

  private static Set<ItemFrame> collectWallFrames(final Player player, final BlockFace hitFace, final ItemFrame target) {
    final Location targetLocation = target.getLocation();
    final Block targetBlock = targetLocation.getBlock();
    final PersistentDataContainer targetData = target.getPersistentDataContainer();
    final String screen = targetData.get(Keys.SCREEN_KEY, PersistentDataType.STRING);
    final World world = player.getWorld();
    final Set<ItemFrame> wallFrames = new HashSet<>();
    final Queue<Block> pending = new ArrayDeque<>();
    pending.add(targetBlock);
    while (!pending.isEmpty()) {
      final Block block = pending.remove();
      addNearbyWallFrames(world, block, hitFace, screen, wallFrames, pending);
    }

    return wallFrames;
  }

  private static void addNearbyWallFrames(
    final World world,
    final Block block,
    final BlockFace hitFace,
    final @Nullable String screen,
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
      final PersistentDataContainer data = frame.getPersistentDataContainer();
      final String candidateScreen = data.get(Keys.SCREEN_KEY, PersistentDataType.STRING);
      final boolean sameScreen = Objects.equals(screen, candidateScreen);
      final boolean isMapFrame = hasTag(frame, Keys.MAP_KEY);
      if (sameScreen && isMapFrame && wallFrames.add(frame)) {
        final Location frameLocation = frame.getLocation();
        final Block frameBlock = frameLocation.getBlock();
        pending.add(frameBlock);
      }
    }
  }

  private static @Nullable ItemFrame findTaggedFrame(final Set<ItemFrame> frames, final NamespacedKey key) {
    ItemFrame tagged = null;
    for (final ItemFrame frame : frames) {
      if (!hasTag(frame, key)) {
        continue;
      }
      if (tagged != null) {
        // Legacy screens have no identity: reject ambiguous neighboring corners instead of choosing arbitrarily.
        return null;
      }
      tagged = frame;
    }

    return tagged;
  }

  private static boolean hasTag(final ItemFrame frame, final NamespacedKey key) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    return container.has(key, PersistentDataType.BOOLEAN);
  }
}

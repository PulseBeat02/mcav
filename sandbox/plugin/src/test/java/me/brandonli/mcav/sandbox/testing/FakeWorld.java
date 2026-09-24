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
package me.brandonli.mcav.sandbox.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A world of mocked blocks and entities with real coordinates, for code that walks from block to block and looks
 * for entities around them.
 *
 * <p>Every coordinate has exactly one block, so blocks can be compared by identity. Blocks remember the material
 * they were set to, and item frames remember the persistent data set on them.
 */
public final class FakeWorld {

  private final World world;
  private final Map<List<Integer>, Block> blocks;
  private final Map<Block, Material> materials;
  private final List<Entity> entities;
  private final List<ItemFrame> spawnedFrames;

  /**
   * Creates an empty world.
   */
  public FakeWorld() {
    this.world = mock(World.class);
    this.blocks = new HashMap<>();
    this.materials = new HashMap<>();
    this.entities = new ArrayList<>();
    this.spawnedFrames = new ArrayList<>();
    when(this.world.getName()).thenReturn("world");
    when(this.world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
      final Location location = invocation.getArgument(0);
      final int x = location.getBlockX();
      final int y = location.getBlockY();
      final int z = location.getBlockZ();
      return this.block(x, y, z);
    });
    when(this.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
      final int x = invocation.getArgument(0);
      final int y = invocation.getArgument(1);
      final int z = invocation.getArgument(2);
      return this.block(x, y, z);
    });
    when(this.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenAnswer(invocation -> {
      final Location center = invocation.getArgument(0);
      final double x = invocation.getArgument(1);
      final double y = invocation.getArgument(2);
      final double z = invocation.getArgument(3);
      return this.findNearbyEntities(center, x, y, z);
    });
    when(this.world.spawn(any(Location.class), eq(ItemFrame.class))).thenAnswer(invocation -> {
      final Location location = invocation.getArgument(0);
      final ItemFrame frame = this.frame(location, BlockFace.SOUTH);
      this.spawnedFrames.add(frame);
      return frame;
    });
  }

  /**
   * Gets the mocked world.
   *
   * @return the world
   */
  public World world() {
    return this.world;
  }

  /**
   * Creates a location in this world.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @return the location
   */
  public Location location(final double x, final double y, final double z) {
    return new Location(this.world, x, y, z);
  }

  /**
   * Gets the block at a position.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @return the block, the same instance for the same position
   */
  public Block block(final int x, final int y, final int z) {
    final List<Integer> key = List.of(x, y, z);
    final Block existing = this.blocks.get(key);
    if (existing != null) {
      return existing;
    }
    final Block block = mock(Block.class);
    this.blocks.put(key, block);
    when(block.getX()).thenReturn(x);
    when(block.getY()).thenReturn(y);
    when(block.getZ()).thenReturn(z);
    when(block.getWorld()).thenReturn(this.world);
    when(block.getLocation()).thenAnswer(_ -> this.location(x, y, z));
    when(block.getRelative(any(BlockFace.class))).thenAnswer(invocation -> {
      final BlockFace face = invocation.getArgument(0);
      return this.relative(x, y, z, face, 1);
    });
    when(block.getRelative(any(BlockFace.class), anyInt())).thenAnswer(invocation -> {
      final BlockFace face = invocation.getArgument(0);
      final int distance = invocation.getArgument(1);
      return this.relative(x, y, z, face, distance);
    });
    when(block.getType()).thenAnswer(_ -> this.materials.getOrDefault(block, Material.AIR));
    doAnswer(invocation -> {
      final Material material = invocation.getArgument(0);
      this.materials.put(block, material);
      return null;
    })
      .when(block)
      .setType(any(Material.class));
    return block;
  }

  private Block relative(final int x, final int y, final int z, final BlockFace face, final int distance) {
    final int modX = face.getModX();
    final int modY = face.getModY();
    final int modZ = face.getModZ();
    return this.block(x + modX * distance, y + modY * distance, z + modZ * distance);
  }

  /**
   * Gets the material a block was set to.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @return the material, {@link Material#AIR} if it was never set
   */
  public Material material(final int x, final int y, final int z) {
    final Block block = this.block(x, y, z);
    return this.materials.getOrDefault(block, Material.AIR);
  }

  /**
   * Counts the blocks whose material was set.
   *
   * @return the number of blocks
   */
  public int changedBlocks() {
    return this.materials.size();
  }

  /**
   * Creates an item frame without adding it to the world.
   *
   * @param location where the frame is
   * @param facing   the direction the front of the frame faces
   * @return the frame
   */
  public ItemFrame frame(final Location location, final BlockFace facing) {
    final ItemFrame frame = mock(ItemFrame.class);
    final Map<NamespacedKey, Object> data = new HashMap<>();
    final PersistentDataContainer container = mock(PersistentDataContainer.class);
    doAnswer(invocation -> {
      final NamespacedKey key = invocation.getArgument(0);
      final Object value = invocation.getArgument(2);
      data.put(key, value);
      return null;
    })
      .when(container)
      .set(any(NamespacedKey.class), any(), any());
    when(container.has(any(NamespacedKey.class), eq(PersistentDataType.BOOLEAN))).thenAnswer(invocation -> {
      final NamespacedKey key = invocation.getArgument(0);
      return data.containsKey(key);
    });
    when(container.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> {
      final NamespacedKey key = invocation.getArgument(0);
      return data.get(key);
    });
    when(frame.getPersistentDataContainer()).thenReturn(container);
    when(frame.getLocation()).thenAnswer(_ -> location.clone());
    when(frame.getWorld()).thenReturn(this.world);
    when(frame.getFacing()).thenReturn(facing);
    final BlockFace attached = facing.getOppositeFace();
    when(frame.getAttachedFace()).thenReturn(attached);
    when(frame.getRotation()).thenReturn(Rotation.NONE);
    return frame;
  }

  /**
   * Creates an item frame of a map screen and adds it to the world.
   *
   * @param location where the frame is
   * @param facing   the direction the front of the frame faces
   * @param keys     the tags of the frame, such as {@link me.brandonli.mcav.sandbox.utils.Keys#MAP_KEY}
   * @return the frame
   */
  public ItemFrame addFrame(final Location location, final BlockFace facing, final NamespacedKey... keys) {
    final ItemFrame frame = this.frame(location, facing);
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    for (final NamespacedKey key : keys) {
      container.set(key, PersistentDataType.BOOLEAN, true);
    }
    this.entities.add(frame);
    return frame;
  }

  /**
   * Adds an entity to the world.
   *
   * @param entity the entity, whose location must be mocked
   */
  public void addEntity(final Entity entity) {
    this.entities.add(entity);
  }

  /**
   * Gets the item frames spawned through {@link World#spawn(Location, Class)}, in order.
   *
   * @return the frames
   */
  public List<ItemFrame> spawnedFrames() {
    return this.spawnedFrames;
  }

  /**
   * Checks whether an item frame has a tag.
   *
   * @param frame the frame
   * @param key   the tag
   * @return true if the tag was set
   */
  public static boolean hasTag(final ItemFrame frame, final NamespacedKey key) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    return container.has(key, PersistentDataType.BOOLEAN);
  }

  private Collection<Entity> findNearbyEntities(final Location center, final double x, final double y, final double z) {
    final List<Entity> nearby = new ArrayList<>();
    for (final Entity entity : this.entities) {
      final Location location = entity.getLocation();
      final boolean near = isWithin(location, center, x, y, z);
      if (near) {
        nearby.add(entity);
      }
    }
    return nearby;
  }

  private static boolean isWithin(final Location location, final Location center, final double x, final double y, final double z) {
    final double offsetX = location.getX() - center.getX();
    final double offsetY = location.getY() - center.getY();
    final double offsetZ = location.getZ() - center.getZ();
    final double distanceX = Math.abs(offsetX);
    final double distanceY = Math.abs(offsetY);
    final double distanceZ = Math.abs(offsetZ);
    return distanceX <= x && distanceY <= y && distanceZ <= z;
  }
}

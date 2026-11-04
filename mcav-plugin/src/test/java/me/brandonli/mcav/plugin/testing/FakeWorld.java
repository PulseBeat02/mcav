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
package me.brandonli.mcav.plugin.testing;

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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.invocation.InvocationOnMock;

// one block per coordinate, so blocks compare by identity
public final class FakeWorld {

  private final World world;
  private final Map<List<Integer>, Block> blocks;
  private final Map<Block, Material> materials;
  private final List<Entity> entities;
  private final List<ItemFrame> spawnedFrames;

  public FakeWorld() {
    this.world = mock(World.class);
    this.blocks = new HashMap<>();
    this.materials = new HashMap<>();
    this.entities = new ArrayList<>();
    this.spawnedFrames = new ArrayList<>();
    when(this.world.getName()).thenReturn("world");
    when(this.world.getBlockAt(any(Location.class))).thenAnswer(this::findBlockAtLocation);
    when(this.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(this::findBlockAtCoordinates);
    when(this.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenAnswer(
      this::findEntitiesNearLocation
    );
    when(this.world.spawn(any(Location.class), eq(ItemFrame.class))).thenAnswer(this::spawnFrame);
  }

  private Block findBlockAtLocation(final InvocationOnMock invocation) {
    final Location location = invocation.getArgument(0);
    final int blockX = location.getBlockX();
    final int blockY = location.getBlockY();
    final int blockZ = location.getBlockZ();
    return this.block(blockX, blockY, blockZ);
  }

  private Block findBlockAtCoordinates(final InvocationOnMock invocation) {
    final int blockX = invocation.getArgument(0);
    final int blockY = invocation.getArgument(1);
    final int blockZ = invocation.getArgument(2);
    return this.block(blockX, blockY, blockZ);
  }

  private Collection<Entity> findEntitiesNearLocation(final InvocationOnMock invocation) {
    final Location center = invocation.getArgument(0);
    final double radiusX = invocation.getArgument(1);
    final double radiusY = invocation.getArgument(2);
    final double radiusZ = invocation.getArgument(3);
    return this.findNearbyEntities(center, radiusX, radiusY, radiusZ);
  }

  private ItemFrame spawnFrame(final InvocationOnMock invocation) {
    final Location location = invocation.getArgument(0);
    final ItemFrame frame = this.createFrame(location, BlockFace.SOUTH);
    this.spawnedFrames.add(frame);

    return frame;
  }

  public Location createLocation(final double positionX, final double positionY, final double positionZ) {
    return new Location(this.world, positionX, positionY, positionZ);
  }

  public Block block(final int blockX, final int blockY, final int blockZ) {
    final List<Integer> key = List.of(blockX, blockY, blockZ);
    final Block existing = this.blocks.get(key);
    if (existing != null) {
      return existing;
    }

    final Block block = mock(Block.class);
    this.blocks.put(key, block);
    when(block.getX()).thenReturn(blockX);
    when(block.getY()).thenReturn(blockY);
    when(block.getZ()).thenReturn(blockZ);
    when(block.getWorld()).thenReturn(this.world);
    when(block.getLocation()).thenAnswer(_ -> this.createLocation(blockX, blockY, blockZ));
    when(block.getRelative(any(BlockFace.class))).thenAnswer(invocation -> this.findRelativeBlock(invocation, blockX, blockY, blockZ));
    when(block.getRelative(any(BlockFace.class), anyInt())).thenAnswer(invocation ->
      this.findRelativeBlockAtDistance(invocation, blockX, blockY, blockZ)
    );
    when(block.getType()).thenAnswer(_ -> this.materials.getOrDefault(block, Material.AIR));
    doAnswer(invocation -> this.setMaterial(invocation, block))
      .when(block)
      .setType(any(Material.class));

    return block;
  }

  private Block findRelativeBlock(final InvocationOnMock invocation, final int blockX, final int blockY, final int blockZ) {
    final BlockFace face = invocation.getArgument(0);
    return this.relative(blockX, blockY, blockZ, face, 1);
  }

  private Block findRelativeBlockAtDistance(final InvocationOnMock invocation, final int blockX, final int blockY, final int blockZ) {
    final BlockFace face = invocation.getArgument(0);
    final int distance = invocation.getArgument(1);
    return this.relative(blockX, blockY, blockZ, face, distance);
  }

  private @Nullable Object setMaterial(final InvocationOnMock invocation, final Block block) {
    final Material material = invocation.getArgument(0);
    this.materials.put(block, material);

    return null;
  }

  private Block relative(final int blockX, final int blockY, final int blockZ, final BlockFace face, final int distance) {
    final int offsetX = face.getModX();
    final int offsetY = face.getModY();
    final int offsetZ = face.getModZ();
    return this.block(blockX + offsetX * distance, blockY + offsetY * distance, blockZ + offsetZ * distance);
  }

  public Material getMaterial(final int blockX, final int blockY, final int blockZ) {
    final Block block = this.block(blockX, blockY, blockZ);
    return this.materials.getOrDefault(block, Material.AIR);
  }

  public ItemFrame createFrame(final Location location, final BlockFace facing) {
    final ItemFrame frame = mock(ItemFrame.class);
    final Map<NamespacedKey, Object> data = new HashMap<>();
    final PersistentDataContainer container = mock(PersistentDataContainer.class);
    doAnswer(invocation -> this.storeData(invocation, data))
      .when(container)
      .set(any(NamespacedKey.class), any(), any());
    when(container.has(any(NamespacedKey.class), eq(PersistentDataType.BOOLEAN))).thenAnswer(invocation -> this.hasData(invocation, data));
    when(container.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> this.readData(invocation, data));
    when(frame.getPersistentDataContainer()).thenReturn(container);
    when(frame.getLocation()).thenAnswer(_ -> location.clone());
    when(frame.getWorld()).thenReturn(this.world);
    when(frame.getFacing()).thenReturn(facing);
    final BlockFace attached = facing.getOppositeFace();
    when(frame.getAttachedFace()).thenReturn(attached);
    when(frame.getRotation()).thenReturn(Rotation.NONE);

    return frame;
  }

  private @Nullable Object storeData(final InvocationOnMock invocation, final Map<NamespacedKey, Object> data) {
    final NamespacedKey key = invocation.getArgument(0);
    final Object value = invocation.getArgument(2);
    data.put(key, value);

    return null;
  }

  private boolean hasData(final InvocationOnMock invocation, final Map<NamespacedKey, Object> data) {
    final NamespacedKey key = invocation.getArgument(0);
    return data.containsKey(key);
  }

  private @Nullable Object readData(final InvocationOnMock invocation, final Map<NamespacedKey, Object> data) {
    final NamespacedKey key = invocation.getArgument(0);
    return data.get(key);
  }

  public ItemFrame addFrame(final Location location, final BlockFace facing, final NamespacedKey... keys) {
    final ItemFrame frame = this.createFrame(location, facing);
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    for (final NamespacedKey key : keys) {
      container.set(key, PersistentDataType.BOOLEAN, true);
    }
    this.entities.add(frame);

    return frame;
  }

  // the entity's location must be mocked
  public void addEntity(final Entity entity) {
    this.entities.add(entity);
  }

  public boolean hasTag(final ItemFrame frame, final NamespacedKey key) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    return container.has(key, PersistentDataType.BOOLEAN);
  }

  private Collection<Entity> findNearbyEntities(final Location center, final double radiusX, final double radiusY, final double radiusZ) {
    return this.entities
      .stream()
      .filter(entity -> this.isWithin(entity.getLocation(), center, radiusX, radiusY, radiusZ))
      .toList();
  }

  private boolean isWithin(
    final Location location,
    final Location center,
    final double radiusX,
    final double radiusY,
    final double radiusZ
  ) {
    final double offsetX = location.getX() - center.getX();
    final double offsetY = location.getY() - center.getY();
    final double offsetZ = location.getZ() - center.getZ();
    final double distanceX = Math.abs(offsetX);
    final double distanceY = Math.abs(offsetY);
    final double distanceZ = Math.abs(offsetZ);
    return distanceX <= radiusX && distanceY <= radiusY && distanceZ <= radiusZ;
  }

  public World getWorld() {
    return this.world;
  }

  public int getChangedBlockCount() {
    return this.materials.size();
  }

  public List<ItemFrame> getSpawnedFrames() {
    return this.spawnedFrames;
  }
}

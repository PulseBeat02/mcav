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
package me.brandonli.mcav.bukkit.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.entity.TextDisplay;
import org.mockito.ArgumentMatchers;

/**
 * A mocked world for tests. Every block has its own block data mock, and spawning a text display creates a valid
 * {@link CraftTextDisplay} mock with a server entity mock, after running the configuration callback on it.
 */
public final class FakeWorld {

  private final World world;
  private final List<CraftTextDisplay> spawnedDisplays;
  private final List<Location> spawnLocations;
  private final Map<String, BlockData> blocks;

  private FakeWorld() {
    this.world = mock(World.class);
    this.spawnedDisplays = new CopyOnWriteArrayList<>();
    this.spawnLocations = new CopyOnWriteArrayList<>();
    this.blocks = new ConcurrentHashMap<>();
    when(this.world.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
      final int x = invocation.getArgument(0);
      final int y = invocation.getArgument(1);
      final int z = invocation.getArgument(2);
      return this.getOriginalBlock(x, y, z);
    });
    when(this.world.spawn(any(Location.class), eq(TextDisplay.class), ArgumentMatchers.<Consumer<? super TextDisplay>>any())).thenAnswer(
      invocation -> {
        final Location location = invocation.getArgument(0);
        final Consumer<? super TextDisplay> configurator = invocation.getArgument(2);
        final CraftTextDisplay display = mock(CraftTextDisplay.class);
        final net.minecraft.world.entity.Display.TextDisplay handle = mock(net.minecraft.world.entity.Display.TextDisplay.class);
        when(display.getHandle()).thenReturn(handle);
        when(display.isValid()).thenReturn(true);
        configurator.accept(display);
        this.spawnLocations.add(location);
        this.spawnedDisplays.add(display);
        return display;
      }
    );
  }

  /**
   * Creates a new world.
   *
   * @return the world
   */
  public static FakeWorld create() {
    return new FakeWorld();
  }

  /**
   * Gets the world mock.
   *
   * @return the world
   */
  public World getWorld() {
    return this.world;
  }

  /**
   * Gets the block data mock of a block, which is the same for every call with the same coordinates.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @return the block data
   */
  public BlockData getOriginalBlock(final int x, final int y, final int z) {
    final String key = x + "," + y + "," + z;
    return this.blocks.computeIfAbsent(key, name -> mock(BlockData.class, "block " + name));
  }

  /**
   * Gets the text displays spawned so far, in order.
   *
   * @return the spawned displays
   */
  public List<CraftTextDisplay> getSpawnedDisplays() {
    return List.copyOf(this.spawnedDisplays);
  }

  /**
   * Gets the locations text displays were spawned at, in order.
   *
   * @return the spawn locations
   */
  public List<Location> getSpawnLocations() {
    return List.copyOf(this.spawnLocations);
  }
}

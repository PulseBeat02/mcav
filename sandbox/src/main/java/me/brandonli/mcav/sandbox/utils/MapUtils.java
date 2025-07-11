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

/*

MIT License

Copyright (c) 2024 Brandon Li

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/

import static java.util.Objects.requireNonNull;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class MapUtils {

  private MapUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static ItemStack getMapFromID(final int id) {
    final ItemStack map = createMapItemStack(id);
    final ItemMeta itemMeta = requireNonNull(map.getItemMeta());
    final String name = "Map ID [%s]".formatted(id);
    itemMeta.lore(List.of(text(name, RED)));
    map.setItemMeta(itemMeta);
    return map;
  }

  @SuppressWarnings("deprecation")
  private static ItemStack createMapItemStack(final int id) {
    final MapView view = Bukkit.getMap(id);
    if (view == null) {
      final List<World> worlds = Bukkit.getWorlds();
      final World world = worlds.getFirst();
      final MapView mapView = Bukkit.createMap(world);
      int currentId = mapView.getId();
      while (currentId <= id) {
        final MapView temp = Bukkit.createMap(world);
        currentId = temp.getId();
      }
    }

    final ItemStack map = new ItemStack(Material.FILLED_MAP);
    final MapMeta mapMeta = requireNonNull((MapMeta) map.getItemMeta());
    mapMeta.setMapId(id);
    map.setItemMeta(mapMeta);
    return map;
  }

  public static void buildMapScreen(
    final CommandSender sender,
    final Location location,
    final Material mat,
    final int width,
    final int height,
    final int map
  ) {
    final World world = location.getWorld();
    final BlockFace face;
    if (sender instanceof final Player player) {
      face = player.getFacing();
    } else {
      face = BlockFace.NORTH;
    }
    final BlockFace opposite = face.getOppositeFace();
    final Block start = location.getBlock().getRelative(face);
    if (face == BlockFace.NORTH) {
      handleNorth(mat, width, height, start, face, world, opposite, map);
    } else if (face == BlockFace.SOUTH) {
      handleSouth(mat, width, height, start, face, world, opposite, map);
    } else if (face == BlockFace.EAST) {
      handleEast(mat, width, height, start, face, world, opposite, map);
    } else if (face == BlockFace.WEST) {
      handleWest(mat, width, height, start, face, world, opposite, map);
    }
  }

  private static void handleNorth(
    final Material mat,
    final int width,
    final int height,
    final Block start,
    final BlockFace face,
    final World world,
    final BlockFace opposite,
    int map
  ) {
    for (int h = height - 1; h >= 0; h--) {
      for (int w = 0; w < width; w++) {
        final Block current = getRelativeBlock(start, mat, w, h, face);
        final ItemFrame frame = getRelativeItemFrame(world, current, opposite, face, map);
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (h == height - 1 && w == 0) {
          data.set(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        } else if (h == 0 && w == width - 1) {
          data.set(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        }
        data.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
        map++;
      }
    }
  }

  private static void handleSouth(
    final Material mat,
    final int width,
    final int height,
    final Block start,
    final BlockFace face,
    final World world,
    final BlockFace opposite,
    int map
  ) {
    for (int h = height - 1; h >= 0; h--) {
      for (int w = width - 1; w >= 0; w--) {
        final Block current = getRelativeBlock(start, mat, w, h, face);
        final ItemFrame frame = getRelativeItemFrame(world, current, opposite, face, map);
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (h == height - 1 && w == width - 1) {
          data.set(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        } else if (h == 0 && w == 0) {
          data.set(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        }
        data.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
        map++;
      }
    }
  }

  private static void handleEast(
    final Material mat,
    final int width,
    final int height,
    final Block start,
    final BlockFace face,
    final World world,
    final BlockFace opposite,
    int map
  ) {
    for (int h = height - 1; h >= 0; h--) {
      for (int w = width - 1; w >= 0; w--) {
        final Block current = getRelativeBlock(start, mat, w, h, face);
        final ItemFrame frame = getRelativeItemFrame(world, current, opposite, face, map);
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (h == height - 1 && w == width - 1) {
          data.set(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        } else if (h == 0 && w == 0) {
          data.set(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        }
        data.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
        map++;
      }
    }
  }

  private static void handleWest(
    final Material mat,
    final int width,
    final int height,
    final Block start,
    final BlockFace face,
    final World world,
    final BlockFace opposite,
    int map
  ) {
    for (int h = height - 1; h >= 0; h--) {
      for (int w = 0; w < width; w++) {
        final Block current = getRelativeBlock(start, mat, w, h, face);
        final ItemFrame frame = getRelativeItemFrame(world, current, opposite, face, map);
        final PersistentDataContainer data = frame.getPersistentDataContainer();
        if (h == height - 1 && w == 0) {
          data.set(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        } else if (h == 0 && w == width - 1) {
          data.set(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN, true);
        }
        data.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
        map++;
      }
    }
  }

  private static ItemFrame getRelativeItemFrame(
    final World world,
    final Block current,
    final BlockFace opposite,
    final BlockFace face,
    final int map
  ) {
    final ItemFrame frame = world.spawn(current.getRelative(opposite).getLocation(), ItemFrame.class);
    frame.setFacingDirection(face);
    frame.setItem(getMapFromID(map));
    frame.setInvulnerable(true);
    frame.setGravity(false);
    return frame;
  }

  private static Block getRelativeBlock(final Block start, final Material mat, final int w, final int h, final BlockFace face) {
    final Block block;
    if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
      block = start.getRelative(BlockFace.UP, h).getRelative(BlockFace.EAST, w);
    } else {
      block = start.getRelative(BlockFace.UP, h).getRelative(BlockFace.NORTH, w);
    }
    block.setType(mat);
    return block;
  }
}

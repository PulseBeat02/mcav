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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds map screens: walls of item frames that hold consecutive maps, tagged with {@link Keys} so clicks on the
 * wall can be translated into pixels by {@link InteractUtils}.
 *
 * <p>This class is stateless; call it on the server main thread, since it creates maps and changes the world.
 *
 * @since 1.0.0
 */
public final class MapUtils {

  private static final String MAP_ID_NEGATIVE_MESSAGE = "Map id %s must not be negative!";

  /**
   * The largest number of maps one call creates to reach a map id, which is the number of maps of the largest wall.
   * Creating a map writes a file, so an id far past the maps of the world would otherwise tie up the main thread.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_NEW_MAPS = ArgumentUtils.MAXIMUM_SCREEN_SIDE * ArgumentUtils.MAXIMUM_SCREEN_SIDE;

  private static final double HALF_BLOCK = 0.5;

  private MapUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Creates a filled map item that shows the map with an id, creating maps in the first world until the id
   * exists.
   *
   * @param id the id of the map, zero or more
   * @return the map item, with the id in its lore
   * @throws IllegalArgumentException if the id is negative, or more than {@link #MAXIMUM_NEW_MAPS} ids past the next map
   *                                  id the server hands out; no map is created then
   * @throws IllegalStateException    if the requested historical map is missing and its id cannot be allocated again
   * @since 1.0.0
   */
  public static ItemStack createMapItem(final int id) {
    Preconditions.checkArgument(id >= 0, MAP_ID_NEGATIVE_MESSAGE, id);

    final ItemStack map = createMapItemStack(id);
    final ItemMeta nullableMetadata = map.getItemMeta();
    final ItemMeta itemMetadata = requireNonNull(nullableMetadata); // checker framework

    final String name = "Map ID [%s]".formatted(id);
    final Component lore = text(name, NamedTextColor.RED);
    final List<Component> lines = List.of(lore);
    itemMetadata.lore(lines);
    map.setItemMeta(itemMetadata);
    return map;
  }

  private static ItemStack createMapItemStack(final int id) {
    final MapView view = findOrCreateMap(id);
    final ItemStack map = new ItemStack(Material.FILLED_MAP);
    final MapMeta nullableMetadata = (MapMeta) map.getItemMeta();
    final MapMeta mapMetadata = requireNonNull(nullableMetadata); // checker framework
    mapMetadata.setMapView(view);
    map.setItemMeta(mapMetadata);
    return map;
  }

  private static MapView findOrCreateMap(final int id) {
    final MapView existing = Bukkit.getMap(id);
    if (existing != null) {
      return existing;
    }

    return createMapsUpTo(id);
  }

  // the server hands out map ids in increasing order
  private static MapView createMapsUpTo(final int id) {
    final int lowestNextId = id - MAXIMUM_NEW_MAPS;
    Preconditions.checkArgument(
      isWithinReach(lowestNextId),
      "Map id %s is more than %s ids past the next map id, because map %s does not exist!",
      id,
      MAXIMUM_NEW_MAPS,
      lowestNextId - 1
    );

    final List<World> worlds = Bukkit.getWorlds();
    final World world = worlds.getFirst();
    MapView created = Bukkit.createMap(world);
    int currentId = created.getId();
    while (currentId < id) {
      created = Bukkit.createMap(world);
      currentId = created.getId();
    }

    Preconditions.checkState(currentId == id, "Map id %s is unavailable, because the server allocated %s instead!", id, currentId);

    return created;
  }

  private static boolean isWithinReach(final int lowestNextId) {
    if (lowestNextId <= 0) {
      return true;
    }

    final MapView lastNeededMap = Bukkit.getMap(lowestNextId - 1);
    return lastNeededMap != null;
  }

  /**
   * Builds a map screen in front of the sender: a wall of blocks with an item frame on every block, holding the
   * maps from {@code map} on, row by row from the top left corner as seen by the sender. Players build the wall in
   * the cardinal direction they are facing, also when they look straight up or down; other senders build it to the
   * north.
   *
   * @param sender   who builds the screen
   * @param location where the screen is built
   * @param material the block of the wall
   * @param width    the width of the screen in blocks, at least 1
   * @param height   the height of the screen in blocks, at least 1
   * @param map      the id of the map in the top left corner, zero or more
   * @throws NullPointerException     if the sender, the location or the material is {@code null}
   * @throws IllegalArgumentException if the width or the height is not positive, the map id is negative, the last map
   *                                  id of the screen is more than {@link Integer#MAX_VALUE}, or it is more than
   *                                  {@link #MAXIMUM_NEW_MAPS} ids past the next map id; nothing is built and no map
   *                                  is created then
   * @throws IllegalStateException    if a map id of the screen belonged to a map that no longer exists
   * @since 1.0.0
   */
  public static void buildMapScreen(
    final CommandSender sender,
    final Location location,
    final Material material,
    final int width,
    final int height,
    final int map
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null!");
    Preconditions.checkNotNull(location, "Location must not be null!");
    Preconditions.checkNotNull(material, "Material must not be null!");
    Preconditions.checkArgument(width > 0, "Width %s must be positive!", width);
    Preconditions.checkArgument(height > 0, "Height %s must be positive!", height);
    Preconditions.checkArgument(map >= 0, MAP_ID_NEGATIVE_MESSAGE, map);

    final long count = (long) width * height;
    final long lastMap = map + count - 1;
    Preconditions.checkArgument(lastMap <= Integer.MAX_VALUE, "Last screen map id %s exceeds the integer range!", lastMap);

    final BlockFace face = sender instanceof final Player player ? player.getFacing() : BlockFace.NORTH;
    final boolean alongX = face == BlockFace.NORTH || face == BlockFace.SOUTH;
    final boolean alongZ = face == BlockFace.EAST || face == BlockFace.WEST;
    if (!alongX && !alongZ) {
      return;
    }

    final int lastMapId = (int) lastMap;
    findOrCreateMap(lastMapId);

    final World world = location.getWorld();
    final Block origin = location.getBlock();
    final Block start = origin.getRelative(face);
    final Wall wall = new Wall(world, start, face, material, alongX);
    fillWall(wall, width, height, map);
  }

  private static void fillWall(final Wall wall, final int width, final int height, final int firstMap) {
    final UUID identifier = UUID.randomUUID();
    final String screen = identifier.toString();
    final List<ItemFrame> frames = new ArrayList<>();
    int mapId = firstMap;
    for (int row = height - 1; row >= 0; row--) {
      for (int column = 0; column < width; column++) {
        final ItemFrame frame = wall.place(column, row, width, mapId);
        tagFrame(frame, screen);
        frames.add(frame);
        mapId++;
      }
    }

    final ItemFrame topLeft = frames.getFirst();
    final ItemFrame bottomRight = frames.getLast();
    tagCorner(topLeft, Keys.FIRST_MAP_KEY);
    tagCorner(bottomRight, Keys.LAST_MAP_KEY);
  }

  private static void tagFrame(final ItemFrame frame, final String screen) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    container.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
    container.set(Keys.SCREEN_KEY, PersistentDataType.STRING, screen);
  }

  private static void tagCorner(final ItemFrame frame, final NamespacedKey key) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    container.set(key, PersistentDataType.BOOLEAN, true);
  }

  private static final class Wall {

    private final World world;
    private final Block start;
    private final BlockFace outward;
    private final Material material;
    private final boolean alongX;
    private final boolean reversed;

    Wall(final World world, final Block start, final BlockFace face, final Material material, final boolean alongX) {
      this.world = world;
      this.start = start;
      this.outward = face.getOppositeFace();
      this.material = material;
      this.alongX = alongX;
      // the columns are counted along the positive axis, which runs from right to left when facing south or east
      this.reversed = face == BlockFace.SOUTH || face == BlockFace.EAST;
    }

    // column counts from the builder's left, row from the bottom
    ItemFrame place(final int column, final int row, final int width, final int map) {
      final int offset = this.reversed ? width - 1 - column : column;
      final Block raised = this.start.getRelative(BlockFace.UP, row);
      final BlockFace direction = this.alongX ? BlockFace.EAST : BlockFace.NORTH;
      final Block block = raised.getRelative(direction, offset);
      block.setType(this.material);
      return this.spawnFrame(block, map);
    }

    private ItemFrame spawnFrame(final Block block, final int map) {
      final Block frameBlock = block.getRelative(this.outward);
      this.clearFrames(frameBlock);
      final Location frameLocation = frameBlock.getLocation();
      final ItemFrame frame = this.world.spawn(frameLocation, ItemFrame.class);
      frame.setFacingDirection(this.outward);

      final ItemStack item = createMapItem(map);
      frame.setItem(item);
      frame.setInvulnerable(true);
      frame.setGravity(false);
      return frame;
    }

    // a block holds one frame per side, so an old frame would push the new one behind the map
    private void clearFrames(final Block frameBlock) {
      final Location blockLocation = frameBlock.getLocation();
      final Location center = blockLocation.add(HALF_BLOCK, HALF_BLOCK, HALF_BLOCK);
      final Collection<Entity> occupants = this.world.getNearbyEntities(center, HALF_BLOCK, HALF_BLOCK, HALF_BLOCK);
      for (final Entity occupant : occupants) {
        if (occupant instanceof ItemFrame) {
          occupant.remove();
        }
      }
    }
  }
}

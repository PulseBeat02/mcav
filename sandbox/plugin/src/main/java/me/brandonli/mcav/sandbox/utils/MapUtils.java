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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
 */
public final class MapUtils {

  private MapUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a filled map item that shows the map with an id, creating maps in the first world until the id
   * exists.
   *
   * @param id the id of the map, zero or more
   * @return the map item, with the id in its lore
   * @throws IllegalArgumentException if the id is negative
   * @throws IllegalStateException if the requested historical map is missing and its id cannot be allocated again
   */
  public static ItemStack getMapFromID(final int id) {
    Preconditions.checkArgument(id >= 0, "Map id must not be negative: %s", id);
    final ItemStack map = createMapItemStack(id);
    final ItemMeta nullableMeta = map.getItemMeta();
    final ItemMeta itemMeta = requireNonNull(nullableMeta);

    final String name = "Map ID [%s]".formatted(id);
    final Component lore = text(name, RED);
    final List<Component> lines = List.of(lore);
    itemMeta.lore(lines);
    map.setItemMeta(itemMeta);
    return map;
  }

  private static ItemStack createMapItemStack(final int id) {
    final MapView view = findOrCreateMap(id);
    final ItemStack map = new ItemStack(Material.FILLED_MAP);
    final MapMeta nullableMeta = (MapMeta) map.getItemMeta();
    final MapMeta mapMeta = requireNonNull(nullableMeta);
    mapMeta.setMapView(view);
    map.setItemMeta(mapMeta);
    return map;
  }

  /**
   * Gets the map with an id, creating maps in the first world until it exists.
   */
  private static MapView findOrCreateMap(final int id) {
    final MapView existing = Bukkit.getMap(id);
    if (existing != null) {
      return existing;
    }
    return createMapsUpTo(id);
  }

  /**
   * Creates maps until the map with the id exists. The server hands out map ids in increasing order.
   *
   * @return the created map with exactly the requested id
   * @throws IllegalStateException if the server has already advanced past a missing map id
   */
  private static MapView createMapsUpTo(final int id) {
    final List<World> worlds = Bukkit.getWorlds();
    final World world = worlds.getFirst();
    MapView created = Bukkit.createMap(world);
    int currentId = created.getId();
    while (currentId < id) {
      created = Bukkit.createMap(world);
      currentId = created.getId();
    }
    Preconditions.checkState(currentId == id, "Map id %s is unavailable; the server allocated %s", id, currentId);
    return created;
  }

  /**
   * Builds a map screen in front of the sender: a wall of blocks with an item frame on every block, holding the
   * maps from {@code map} on, row by row from the top left corner as seen by the sender. Players build the wall in
   * the direction they are facing, other senders build it to the north. Players looking straight up or down build
   * nothing.
   *
   * @param sender   who builds the screen
   * @param location where the screen is built
   * @param material the block of the wall
   * @param width    the width of the screen in blocks, at least 1
   * @param height   the height of the screen in blocks, at least 1
   * @param map      the id of the map in the top left corner, zero or more
   * @throws NullPointerException     if the sender, the location or the material is {@code null}
   * @throws IllegalArgumentException if the width or the height is not positive, or the map id is negative
   */
  public static void buildMapScreen(
    final CommandSender sender,
    final Location location,
    final Material material,
    final int width,
    final int height,
    final int map
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");
    Preconditions.checkNotNull(material, "Material must not be null");
    Preconditions.checkArgument(width > 0, "Width must be positive: %s", width);
    Preconditions.checkArgument(height > 0, "Height must be positive: %s", height);
    Preconditions.checkArgument(map >= 0, "Map id must not be negative: %s", map);
    final long count = (long) width * height;
    final long lastMap = map + count - 1;
    Preconditions.checkArgument(lastMap <= Integer.MAX_VALUE, "Screen map ids exceed the integer range: %s", lastMap);

    final BlockFace face = sender instanceof final Player player ? player.getFacing() : BlockFace.NORTH;
    final boolean alongX = face == BlockFace.NORTH || face == BlockFace.SOUTH;
    final boolean alongZ = face == BlockFace.EAST || face == BlockFace.WEST;
    if (!alongX && !alongZ) {
      return;
    }

    final World world = location.getWorld();
    final Block origin = location.getBlock();
    final Block start = origin.getRelative(face);
    final Wall wall = new Wall(world, start, face, material, alongX);
    fillWall(wall, width, height, map);
  }

  private static void fillWall(final Wall wall, final int width, final int height, final int firstMap) {
    final UUID identifier = UUID.randomUUID();
    final String screen = identifier.toString();
    int mapId = firstMap;
    for (int row = height - 1; row >= 0; row--) {
      for (int column = 0; column < width; column++) {
        final ItemFrame frame = wall.place(column, row, width, mapId);
        final boolean first = row == height - 1 && column == 0;
        final boolean last = row == 0 && column == width - 1;
        tagFrame(frame, first, last, screen);
        mapId++;
      }
    }
  }

  private static void tagFrame(final ItemFrame frame, final boolean first, final boolean last, final String screen) {
    final PersistentDataContainer container = frame.getPersistentDataContainer();
    if (first) {
      container.set(Keys.FIRST_MAP_KEY, PersistentDataType.BOOLEAN, true);
    }
    if (last) {
      container.set(Keys.LAST_MAP_KEY, PersistentDataType.BOOLEAN, true);
    }
    container.set(Keys.MAP_KEY, PersistentDataType.BOOLEAN, true);
    container.set(Keys.SCREEN_KEY, PersistentDataType.STRING, screen);
  }

  /**
   * The wall of a map screen being built: where it starts, which way it faces, and what it is made of.
   */
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

    /**
     * Places the block of a cell of the wall and hangs a frame with a map on it.
     *
     * @param column the column as seen by the builder, counted from the left
     * @param row    the row, counted from the bottom
     * @param width  the number of columns
     * @param map    the id of the map in the frame
     * @return the frame
     */
    ItemFrame place(final int column, final int row, final int width, final int map) {
      final int offset = this.reversed ? width - 1 - column : column;
      final Block raised = this.start.getRelative(BlockFace.UP, row);
      final BlockFace direction = this.alongX ? BlockFace.EAST : BlockFace.NORTH;
      final Block block = raised.getRelative(direction, offset);
      block.setType(this.material);
      return this.spawnFrame(block, map);
    }

    /**
     * Removes the item frames already hanging in the block the new frame goes into.
     *
     * <p>Without this, building a screen where one already stands leaves the old frame in place. A block holds
     * only one frame per side, so the new frame cannot take the side it is asked for and keeps the one it was
     * spawned with, hanging on the opposite side of the same block with its back to the viewer, directly in front
     * of the map. The screen then shows the backs of item frames and stays blank however much media is sent to
     * it, with nothing wrong in the packets.
     */
    private void clearFrames(final Block frameBlock) {
      final Location centre = frameBlock.getLocation().add(0.5, 0.5, 0.5);
      final Collection<Entity> occupants = this.world.getNearbyEntities(centre, 0.5, 0.5, 0.5);
      for (final Entity occupant : occupants) {
        if (occupant instanceof ItemFrame) {
          occupant.remove();
        }
      }
    }

    private ItemFrame spawnFrame(final Block block, final int map) {
      final Block frameBlock = block.getRelative(this.outward);
      this.clearFrames(frameBlock);
      final Location frameLocation = frameBlock.getLocation();
      final ItemFrame frame = this.world.spawn(frameLocation, ItemFrame.class);
      // The supporting block lies ahead of the builder; the frame faces back toward the builder.
      frame.setFacingDirection(this.outward);

      final ItemStack item = getMapFromID(map);
      frame.setItem(item);
      frame.setInvulnerable(true);
      frame.setGravity(false);
      return frame;
    }
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.brandonli.mcav.sandbox.testing.FakeWorld;
import me.brandonli.mcav.sandbox.testing.TestServer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.mockito.MockedConstruction;
import org.mockito.MockingDetails;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * A property of building a wall of maps where one already hangs. The live pass found the most serious runtime defect of
 * this branch here: running {@code /mcav screen} twice on one wall left a second item frame in every block, back
 * turned and right in front of the map, so the wall showed nothing. The fix clears the frames of a block before it
 * hangs a new one; the examples check one block. This checks every wall size and facing, built up to three times, over
 * old frames facing either way, other entities in the wall, and frames next to it: afterwards every block of the wall
 * holds exactly one frame, the one of the last build, and nothing that is not a frame of the wall was removed.
 */
final class MapScreenRebuildPropertyTest {

  private static final String SEED = "20260925";
  private static final List<BlockFace> FACINGS = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

  private MockedConstruction<ItemStack> items;

  @BeforeTry
  void setUp() {
    final Server server = TestServer.reset();
    when(server.getMap(anyInt())).thenAnswer(_ -> mock(MapView.class));
    this.items = Mockito.mockConstruction(ItemStack.class, (item, _) -> {
      final MapMeta meta = mock(MapMeta.class);
      when(item.getItemMeta()).thenReturn(meta);
    });
  }

  @AfterTry
  void closeConstructionMock() {
    this.items.close();
  }

  @Provide
  Arbitrary<Rebuild> rebuilds() {
    final Arbitrary<Integer> sides = between(1, 4);
    final Arbitrary<BlockFace> facings = Arbitraries.of(FACINGS);
    final Arbitrary<Integer> builds = between(1, 3);
    final Arbitrary<Obstacle> obstacle = obstacles();
    final ListArbitrary<Obstacle> obstacleList = obstacle.list();
    final ListArbitrary<Obstacle> someObstacles = obstacleList.ofMaxSize(6);
    final Combinators.Combinator5<Integer, Integer, BlockFace, Integer, List<Obstacle>> rebuilds = Combinators.combine(
      sides,
      sides,
      facings,
      builds,
      someObstacles
    );
    return rebuilds.as(Rebuild::new);
  }

  private static Arbitrary<Obstacle> obstacles() {
    final Arbitrary<Integer> cells = between(0, 15);
    final Arbitrary<ObstacleKind> kinds = Arbitraries.of(ObstacleKind.class);
    final Arbitrary<BlockFace> facings = Arbitraries.of(FACINGS);
    final Combinators.Combinator3<Integer, ObstacleKind, BlockFace> obstacles = Combinators.combine(cells, kinds, facings);
    return obstacles.as(Obstacle::new);
  }

  /**
   * Creates integers in a range from a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones
   * configured from it, so one base configured twice would hand every user the last range.
   */
  private static Arbitrary<Integer> between(final int min, final int max) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(min, max);
  }

  @Property(seed = SEED, tries = 150)
  void everyBlockOfARebuiltWallHoldsExactlyTheFrameOfTheLastBuild(@ForAll("rebuilds") final Rebuild rebuild) {
    final List<List<Integer>> cells = rebuild.findCells();
    final FakeWorld world = new FakeWorld();
    final List<Entity> keep = new ArrayList<>();
    final List<ItemFrame> doomed = new ArrayList<>();
    rebuild.placeObstacles(world, cells, keep, doomed);

    final Player player = rebuild.createPlayer();
    final Location location = world.location(0.5, 64.0, 0.5);
    List<ItemFrame> lastBuild = List.of();
    for (int build = 0; build < rebuild.builds; build++) {
      final List<ItemFrame> spawned = world.spawnedFrames();
      final int before = spawned.size();
      MapUtils.buildMapScreen(player, location, Material.STONE, rebuild.width, rebuild.height, 0);
      final List<ItemFrame> after = world.spawnedFrames();
      final int total = after.size();
      final List<ItemFrame> newFrames = after.subList(before, total);
      final List<ItemFrame> built = new ArrayList<>(newFrames);
      doomed.addAll(lastBuild);
      for (final ItemFrame frame : built) {
        hangInsideItsBlock(frame);
        world.addEntity(frame);
      }
      lastBuild = built;
    }

    final int cellCount = rebuild.width * rebuild.height;
    final int frameCount = lastBuild.size();
    assertEquals(cellCount, frameCount, "one frame per block of the wall");
    final Set<List<Integer>> occupied = new HashSet<>();
    for (final ItemFrame frame : lastBuild) {
      final List<Integer> block = blockOf(frame);
      final boolean alone = occupied.add(block);
      assertTrue(alone, () -> "two frames of the wall hang in the block " + block);
      assertFalse(wasRemoved(frame), () -> "the frame of the last build in " + block + " was removed");
    }
    for (final ItemFrame frame : doomed) {
      final List<Integer> block = blockOf(frame);
      assertTrue(wasRemoved(frame), () -> "an old frame still hangs in the block " + block + " of the wall");
    }
    for (final Entity entity : keep) {
      assertFalse(wasRemoved(entity), () -> "an entity that is not a frame of the wall was removed: " + entity);
    }
  }

  /**
   * The world spawns frames at the corner of their block; a real frame hangs inside it, and a frame on the corner
   * would also be found by the search of the neighbouring block.
   */
  private static void hangInsideItsBlock(final ItemFrame frame) {
    final Location corner = frame.getLocation();
    final Location inside = corner.clone();
    inside.add(0.5, 0.5, 0.5);
    when(frame.getLocation()).thenAnswer(_ -> inside.clone());
  }

  private static List<Integer> blockOf(final Entity entity) {
    final Location location = entity.getLocation();
    return List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private static boolean wasRemoved(final Entity entity) {
    final MockingDetails details = Mockito.mockingDetails(entity);
    final Collection<Invocation> invocations = details.getInvocations();
    for (final Invocation invocation : invocations) {
      final Method method = invocation.getMethod();
      final String name = method.getName();
      if (name.equals("remove")) {
        return true;
      }
    }
    return false;
  }

  /**
   * What stands in or next to the wall before it is built.
   */
  enum ObstacleKind {
    OLD_FRAME,
    OTHER_ENTITY,
    NEIGHBOUR_FRAME,
  }

  /**
   * One thing in or next to a block of the wall.
   */
  static final class Obstacle {

    private final int cell;
    private final ObstacleKind kind;
    private final BlockFace facing;

    Obstacle(final int cell, final ObstacleKind kind, final BlockFace facing) {
      this.cell = cell;
      this.kind = kind;
      this.facing = facing;
    }

    @Override
    public String toString() {
      return this.kind + " at cell " + this.cell + " facing " + this.facing;
    }
  }

  /**
   * A wall built a number of times by a player facing one way, with things already standing where it goes.
   */
  static final class Rebuild {

    private final int width;
    private final int height;
    private final BlockFace facing;
    private final int builds;
    private final List<Obstacle> obstacles;

    Rebuild(final int width, final int height, final BlockFace facing, final int builds, final List<Obstacle> obstacles) {
      this.width = width;
      this.height = height;
      this.facing = facing;
      this.builds = builds;
      this.obstacles = obstacles;
    }

    Player createPlayer() {
      final Player player = mock(Player.class);
      when(player.getFacing()).thenReturn(this.facing);
      return player;
    }

    /**
     * Builds the wall once in an empty world to learn which blocks its frames hang in.
     */
    List<List<Integer>> findCells() {
      final FakeWorld empty = new FakeWorld();
      final Player player = this.createPlayer();
      final Location location = empty.location(0.5, 64.0, 0.5);
      MapUtils.buildMapScreen(player, location, Material.STONE, this.width, this.height, 0);
      final List<List<Integer>> cells = new ArrayList<>();
      for (final ItemFrame frame : empty.spawnedFrames()) {
        cells.add(blockOf(frame));
      }
      return cells;
    }

    void placeObstacles(final FakeWorld world, final List<List<Integer>> cells, final List<Entity> keep, final List<ItemFrame> doomed) {
      for (final Obstacle obstacle : this.obstacles) {
        final List<Integer> cell = cells.get(obstacle.cell % cells.size());
        final int x = cell.get(0);
        final int y = cell.get(1);
        final int z = cell.get(2);
        switch (obstacle.kind) {
          case OLD_FRAME -> {
            final Location inside = world.location(x + 0.5, y + 0.5, z + 0.5);
            final ItemFrame frame = world.addFrame(inside, obstacle.facing);
            doomed.add(frame);
          }
          case OTHER_ENTITY -> {
            final Location inside = world.location(x + 0.5, y + 0.5, z + 0.5);
            final Entity entity = mock(Entity.class);
            when(entity.getLocation()).thenAnswer(_ -> inside.clone());
            world.addEntity(entity);
            keep.add(entity);
          }
          case NEIGHBOUR_FRAME -> {
            // the block above the top of the wall is never part of it
            final Location above = world.location(x + 0.5, 64 + this.height + 0.5, z + 0.5);
            final ItemFrame frame = world.addFrame(above, obstacle.facing);
            keep.add(frame);
          }
        }
      }
    }

    @Override
    public String toString() {
      return this.width + "x" + this.height + " facing " + this.facing + ", built " + this.builds + " times, over " + this.obstacles;
    }
  }
}

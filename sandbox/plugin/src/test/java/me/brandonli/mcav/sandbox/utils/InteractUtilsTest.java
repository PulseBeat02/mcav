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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.brandonli.mcav.sandbox.testing.FakeWorld;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.bukkit.Location;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link InteractUtils}.
 *
 * <p>The walls are built from mocked item frames in a {@link FakeWorld}. The player looks at a point whose position
 * inside its frame is 0.3 of the frame from the left and 0.4 from the top, which is pixel 38, 51 of the map.
 */
final class InteractUtilsTest {

  private static final float LOOK_NORTH = 180.0f;
  private static final float LOOK_SOUTH = 0.0f;
  private static final float LOOK_EAST = -90.0f;
  private static final float LOOK_WEST = 90.0f;

  private FakeWorld fakeWorld;
  private Player player;

  @BeforeEach
  void createWorld() {
    this.fakeWorld = new FakeWorld();
    this.player = mock(Player.class);
    final World world = this.fakeWorld.world();
    when(this.player.getWorld()).thenReturn(world);
  }

  private void aimAt(final Entity target, final BlockFace face, final Block block, final Location eye) {
    when(this.player.getTargetEntity(anyInt())).thenReturn(target);
    when(this.player.getTargetBlockFace(anyInt())).thenReturn(face);
    when(this.player.getTargetBlock(any(), anyInt())).thenReturn(block);
    when(this.player.getEyeLocation()).thenAnswer(_ -> eye.clone());
  }

  private Location eye(final double x, final double y, final double z, final float yaw) {
    final World world = this.fakeWorld.world();
    return new Location(world, x, y, z, yaw, 0.0f);
  }

  private Location eye(final double x, final double y, final double z, final float yaw, final float pitch) {
    final World world = this.fakeWorld.world();
    return new Location(world, x, y, z, yaw, pitch);
  }

  /**
   * Builds the wall of {@link #buildSouthWall()} four blocks further east, so that the column of a frame differs from
   * its own coordinate and only the distance to the first corner gives the right column.
   *
   * @return the frame the player looks at, in the last column of the lower row
   */
  private ItemFrame buildSouthWallAwayFromTheOrigin() {
    ItemFrame target = null;
    for (int row = 0; row < 2; row++) {
      for (int column = 0; column < 4; column++) {
        final int x = 4 + column;
        final int y = 65 - row;
        final Location location = this.fakeWorld.location(x + 0.5, y + 0.5, 0.0);
        final boolean first = row == 0 && column == 0;
        final boolean last = row == 1 && column == 3;
        if (first) {
          this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
        } else if (last) {
          target = this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
        } else {
          this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY);
        }
      }
    }
    final Block wallBlock = this.fakeWorld.block(7, 64, -1);
    final Location eye = this.eye(7.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    return target;
  }

  /**
   * Builds a wall of four columns and two rows on the plane z = 0, facing south. The top left frame as seen from the
   * south is at x = 0 and y = 65, and the frame the player looks at is in the last column of the lower row.
   */
  private ItemFrame buildSouthWall() {
    ItemFrame target = null;
    for (int row = 0; row < 2; row++) {
      for (int column = 0; column < 4; column++) {
        final int y = 65 - row;
        final Location location = this.fakeWorld.location(column + 0.5, y + 0.5, 0.0);
        final boolean first = row == 0 && column == 0;
        final boolean last = row == 1 && column == 3;
        if (first) {
          this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
        } else if (last) {
          target = this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
        } else {
          this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY);
        }
      }
    }
    final Block wallBlock = this.fakeWorld.block(3, 64, -1);
    final Location eye = this.eye(3.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    return target;
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void keepsAdjacentScreenCornersSeparateOrRejectsAmbiguousLegacyWalls(final boolean identified) {
    final Location firstLocation = this.fakeWorld.location(0.5, 64.5, 0.0);
    final Location secondLocation = this.fakeWorld.location(1.5, 64.5, 0.0);
    final ItemFrame first = this.fakeWorld.addFrame(firstLocation, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    final ItemFrame second = this.fakeWorld.addFrame(secondLocation, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    if (identified) {
      final PersistentDataContainer firstData = first.getPersistentDataContainer();
      final PersistentDataContainer secondData = second.getPersistentDataContainer();
      firstData.set(Keys.SCREEN_KEY, PersistentDataType.STRING, "first");
      secondData.set(Keys.SCREEN_KEY, PersistentDataType.STRING, "second");
    }
    final Block wall = this.fakeWorld.block(1, 64, -1);
    final Location eye = this.eye(1.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(second, BlockFace.SOUTH, wall, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    if (identified) {
      assertArrayEquals(new int[] { 38, 51 }, coordinates);
    } else {
      assertNull(coordinates, "legacy walls with several possible corners cannot be resolved safely");
    }
  }

  @Test
  void findsThePixelOnAWallFacingSouth() {
    this.buildSouthWall();
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + 38, 128 + 51 }, coordinates);
  }

  @Test
  void findsThePixelOfTheFrameItWasGiven() {
    final ItemFrame target = this.buildSouthWall();
    when(this.player.getTargetEntity(anyInt())).thenReturn(null);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player, target);
    assertArrayEquals(new int[] { 3 * 128 + 38, 128 + 51 }, coordinates);
  }

  @Test
  void countsTheColumnsFromTheFirstCornerOfTheWall() {
    this.buildSouthWallAwayFromTheOrigin();
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    final int[] expected = { 3 * 128 + 38, 128 + 51 };
    assertArrayEquals(expected, coordinates, "the column is the distance to the first corner, not the coordinate");
  }

  @Test
  void countsTheColumnsOfAWallFacingWestFromItsFirstCorner() {
    final Location left = this.fakeWorld.location(0.0, 64.5, 2.5);
    final Location right = this.fakeWorld.location(0.0, 64.5, 3.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, 64, 3);
    final Location eye = this.eye(-5.0, 64.6, 3.3, LOOK_EAST);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    final int[] expected = { 128 + 38, 51 };
    assertArrayEquals(expected, coordinates, "the column is the distance to the first corner, not the coordinate");
  }

  @Test
  void followsTheLineOfSightOfAPlayerLookingDown() {
    this.buildSouthWall();
    // looking down at 45 degrees, so the ray falls by as much as it travels north: 5.3 blocks over the 5.3 blocks to
    // the wall, which lands 0.3 of the frame below its top edge
    final Location eye = this.eye(3.3, 69.6, 5.3, LOOK_NORTH, 45.0f);
    when(this.player.getEyeLocation()).thenAnswer(_ -> eye.clone());
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    final int[] expected = { 3 * 128 + 38, 128 + 89 };
    assertArrayEquals(expected, coordinates);
  }

  @ParameterizedTest
  @CsvSource(
    {
      "NONE, 38, 51",
      "FLIPPED, 38, 51",
      "CLOCKWISE_45, 51, 89",
      "FLIPPED_45, 51, 89",
      "CLOCKWISE, 89, 76",
      "COUNTER_CLOCKWISE, 89, 76",
      "CLOCKWISE_135, 76, 38",
      "COUNTER_CLOCKWISE_45, 76, 38",
    }
  )
  void turnsThePixelWithTheMapInsideTheFrame(final Rotation rotation, final int pixelX, final int pixelY) {
    final ItemFrame target = this.buildSouthWall();
    when(target.getRotation()).thenReturn(rotation);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + pixelX, 128 + pixelY }, coordinates);
  }

  @Test
  void ignoresEntitiesAndFramesThatAreNotPartOfTheWall() {
    this.buildSouthWall();
    final Zombie zombie = mock(Zombie.class);
    final Location zombieLocation = this.fakeWorld.location(2.5, 64.5, 0.5);
    when(zombie.getLocation()).thenReturn(zombieLocation);
    this.fakeWorld.addEntity(zombie);
    final Location sideways = this.fakeWorld.location(2.5, 65.5, 0.0);
    this.fakeWorld.addFrame(sideways, BlockFace.EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final Location untagged = this.fakeWorld.location(2.5, 66.5, 0.0);
    this.fakeWorld.addFrame(untagged, BlockFace.SOUTH, Keys.FIRST_MAP_KEY);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + 38, 128 + 51 }, coordinates);
  }

  @Test
  void mirrorsThePixelOnAWallFacingNorth() {
    final Location left = this.fakeWorld.location(1.5, 64.5, 0.0);
    final Location right = this.fakeWorld.location(0.5, 64.5, 0.0);
    this.fakeWorld.addFrame(left, BlockFace.NORTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.NORTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(0, 64, 1);
    final Location eye = this.eye(0.3, 64.6, -5.0, LOOK_SOUTH);
    this.aimAt(target, BlockFace.NORTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 128 + 89, 51 }, coordinates);
  }

  @Test
  void findsThePixelOnAWallFacingWest() {
    final Location left = this.fakeWorld.location(0.0, 64.5, 0.5);
    final Location right = this.fakeWorld.location(0.0, 64.5, 1.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, 64, 1);
    final Location eye = this.eye(-5.0, 64.6, 1.3, LOOK_EAST);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 128 + 38, 51 }, coordinates);
  }

  @Test
  void mirrorsThePixelOnAWallFacingEast() {
    final Location left = this.fakeWorld.location(0.0, 64.5, 1.5);
    final Location right = this.fakeWorld.location(0.0, 64.5, 0.5);
    this.fakeWorld.addFrame(left, BlockFace.EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.EAST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(-1, 64, 0);
    final Location eye = this.eye(5.0, 64.6, 0.3, LOOK_WEST);
    this.aimAt(target, BlockFace.EAST, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 128 + 89, 51 }, coordinates);
  }

  @Test
  void followsTheLineOfSightToAWallThatIsNotOnTheZeroPlane() {
    final Location left = this.fakeWorld.location(0.5, 64.5, 8.0);
    final Location right = this.fakeWorld.location(1.5, 64.5, 8.0);
    this.fakeWorld.addFrame(left, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, 64, 7);
    // yaw 150 looks north-north-west, so the ray travels 0.577 blocks west for every block north: over the 5.3 blocks
    // to the wall it moves 3.06 west, from x = 4.3 to x = 1.24, which is 0.24 of the frame from its left edge. A frame
    // that faces south is attached to the north side of its block, so the pixel is not mirrored: 0.24 * 128 = 30
    final Location eye = this.eye(4.3, 64.6, 13.3, 150.0f);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    final int[] expected = { 128 + 30, 51 };
    assertArrayEquals(expected, coordinates, "the ray is followed to the wall, not to the mirror image of the eye");
  }

  @Test
  void followsTheLineOfSightToAWallFacingWestThatIsNotOnTheZeroPlane() {
    final Location left = this.fakeWorld.location(8.0, 64.5, 0.5);
    final Location right = this.fakeWorld.location(8.0, 64.5, 1.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(9, 64, 1);
    // yaw -60 looks east-south-east, so the ray travels 0.577 blocks south for every block east: over the 5.3 blocks
    // to the wall it moves 3.06 south, from z = -1.5 to z = 1.56, which is 0.56 of the frame from its first edge. A
    // frame that faces west is attached to the east side of its block, so the pixel is not mirrored: 0.56 * 128 = 71
    final Location eye = this.eye(2.7, 64.6, -1.5, -60.0f);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    final int[] expected = { 128 + 71, 51 };
    assertArrayEquals(expected, coordinates, "the ray is followed to the wall, not to the mirror image of the eye");
  }

  @Test
  void rejectsUnsupportedFramesOnTheFloor() {
    ItemFrame target = null;
    for (int x = 0; x < 2; x++) {
      for (int z = 0; z < 2; z++) {
        final Location location = this.fakeWorld.location(x + 0.5, 65.0, z + 0.5);
        final boolean first = x == 0 && z == 0;
        final boolean last = x == 1 && z == 1;
        if (first) {
          this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
        } else if (last) {
          target = this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
        } else {
          this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY);
        }
      }
    }
    final Block floorBlock = this.fakeWorld.block(1, 64, 1);
    final Location eye = this.eye(1.3, 67.0, 1.6, LOOK_NORTH);
    this.aimAt(target, BlockFace.UP, floorBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @ParameterizedTest
  @CsvSource({ "2.9,64.5", "4.1,64.5", "3.5,63.9", "3.5,65.1" })
  void rejectsRaysOutsideTheSelectedFrame(final double x, final double y) {
    this.buildSouthWall();
    final Location eye = this.eye(x, y, 5.0, LOOK_NORTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void acceptsAnEyeOnTheMapPlaneLookingAcrossIt() {
    this.buildSouthWall();
    final Location eye = this.eye(3.5, 64.5, 0.0, LOOK_NORTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + 64, 128 + 64 }, coordinates);
  }

  @Test
  void rejectsAnIntersectionBehindThePlayer() {
    this.buildSouthWall();
    final Location eye = this.eye(3.3, 64.6, 5.0, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @ParameterizedTest
  @CsvSource({ "5.0", "0.0" })
  void rejectsParallelAndCoplanarRays(final double z) {
    this.buildSouthWall();
    final Location eye = mock(Location.class);
    when(eye.getZ()).thenReturn(z);
    final Vector direction = new Vector(1, 0, 0);
    when(eye.getDirection()).thenReturn(direction);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @ParameterizedTest
  @CsvSource({ "3.0,64.0,0,127", "4.0,65.0,127,0" })
  void keepsFrameEdgesInsideTheSelectedMap(final double x, final double y, final int pixelX, final int pixelY) {
    this.buildSouthWall();
    final Location eye = this.eye(x, y, -5.0, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + pixelX, 128 + pixelY }, coordinates);
  }

  @Test
  void keepsRotatedFrameEdgesInsideTheSelectedMap() {
    final ItemFrame target = this.buildSouthWall();
    when(target.getRotation()).thenReturn(Rotation.CLOCKWISE);
    final Location eye = this.eye(3.0, 65.0, -5.0, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertArrayEquals(new int[] { 3 * 128 + 127, 128 + 127 }, coordinates);
  }

  @Test
  void returnsNullForWallsThatDoNotFaceAlongAnAxis() {
    final Location location = this.fakeWorld.location(0.5, 64.5, 0.0);
    final ItemFrame target = this.fakeWorld.addFrame(location, BlockFace.NORTH_EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(0, 64, -1);
    final Location eye = this.eye(0.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(target, BlockFace.NORTH_EAST, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void returnsNullWhenThePlayerLooksAtNoEntity() {
    when(this.player.getTargetEntity(anyInt())).thenReturn(null);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void returnsNullWhenThePlayerLooksAtNoBlockFace() {
    this.buildSouthWall();
    when(this.player.getTargetBlockFace(anyInt())).thenReturn(null);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void returnsNullForAWallWithoutItsLastCorner() {
    final Location first = this.fakeWorld.location(0.5, 64.5, 0.0);
    final Location second = this.fakeWorld.location(1.5, 64.5, 0.0);
    this.fakeWorld.addFrame(first, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(second, BlockFace.SOUTH, Keys.MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, 64, -1);
    final Location eye = this.eye(1.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void returnsNullForAWallWithoutItsFirstCorner() {
    final Location first = this.fakeWorld.location(0.5, 64.5, 0.0);
    final Location second = this.fakeWorld.location(1.5, 64.5, 0.0);
    this.fakeWorld.addFrame(first, BlockFace.SOUTH, Keys.MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(second, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, 64, -1);
    final Location eye = this.eye(1.3, 64.6, 5.0, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.getBoardCoordinates(this.player);
    assertNull(coordinates);
  }

  @Test
  void rejectsEntitiesThatAreNotItemFrames() {
    final Zombie zombie = mock(Zombie.class);
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
      InteractUtils.getBoardCoordinates(this.player, zombie)
    );
    final String message = exception.getMessage();
    assertEquals("Target entity is not an ItemFrame", message);
  }

  @Test
  void rejectsNullArguments() {
    final ItemFrame frame = mock(ItemFrame.class);
    assertThrows(NullPointerException.class, () -> InteractUtils.getBoardCoordinates(null));
    assertThrows(NullPointerException.class, () -> InteractUtils.getBoardCoordinates(null, frame));
    assertThrows(NullPointerException.class, () -> InteractUtils.getBoardCoordinates(this.player, null));
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(InteractUtils.class);
  }
}

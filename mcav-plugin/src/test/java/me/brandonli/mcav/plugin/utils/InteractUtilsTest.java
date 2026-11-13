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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import me.brandonli.mcav.plugin.testing.FakeWorld;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// the player looks 0.3 across and 0.4 down its frame: pixel 38, 51 of the map
final class InteractUtilsTest {

  private static final float LOOK_NORTH = 180.0f;
  private static final float LOOK_SOUTH = 0.0f;
  private static final float LOOK_EAST = -90.0f;
  private static final float LOOK_WEST = 90.0f;

  private static final int BLOCK_Y = 64;
  private static final int TOP_ROW_Y = 65;
  private static final double FRAME_CENTER_Y = 64.5;
  private static final double EYE_Y = 64.6;

  private static final double EYE_DISTANCE = 5.0;

  private static final double DISTANT_WALL_PLANE = 8.0;

  private static final double HALF_BLOCK = 0.5;

  private static final int WALL_COLUMNS = 4;
  private static final int WALL_ROWS = 2;

  private static final int MAP_SIZE = 128;
  private static final int AIMED_PIXEL_X = 38;
  private static final int AIMED_PIXEL_Y = 51;
  private static final int MIRRORED_PIXEL_X = 89;

  private static final String COLUMN_DISTANCE_MESSAGE = "the column is the distance to the first corner, not the coordinate";

  private static final String WALL_RAY_DIRECTION_MESSAGE = "the ray is followed to the wall, not to the mirror image of the eye";

  private FakeWorld fakeWorld;
  private Player player;

  @BeforeEach
  void createWorld() {
    this.fakeWorld = new FakeWorld();
    this.player = mock(Player.class);
    final World world = this.fakeWorld.getWorld();
    when(this.player.getWorld()).thenReturn(world);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void shouldKeepAdjacentScreenCornersSeparateOrRejectAmbiguousLegacyWalls(final boolean identified) {
    final Location firstLocation = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, 0.0);
    final Location secondLocation = this.fakeWorld.createLocation(1.5, FRAME_CENTER_Y, 0.0);
    final ItemFrame first = this.fakeWorld.addFrame(firstLocation, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    final ItemFrame second = this.fakeWorld.addFrame(secondLocation, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    if (identified) {
      final PersistentDataContainer firstData = first.getPersistentDataContainer();
      final PersistentDataContainer secondData = second.getPersistentDataContainer();
      firstData.set(Keys.SCREEN_KEY, PersistentDataType.STRING, "first");
      secondData.set(Keys.SCREEN_KEY, PersistentDataType.STRING, "second");
    }
    final Block wall = this.fakeWorld.block(1, BLOCK_Y, -1);
    final Location eye = this.eye(1.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(second, BlockFace.SOUTH, wall, eye);
    final Optional<int[]> foundCoordinates = InteractUtils.findBoardCoordinates(this.player);
    if (identified) {
      final int[] coordinates = foundCoordinates.orElseThrow();
      final int[] expectedCoordinates = { AIMED_PIXEL_X, AIMED_PIXEL_Y };
      assertArrayEquals(expectedCoordinates, coordinates);
    } else {
      assertEquals(Optional.empty(), foundCoordinates, "legacy walls with several possible corners cannot be resolved safely");
    }
  }

  private void aimAt(final Entity target, final BlockFace face, final Block block, final Location eye) {
    when(this.player.getTargetEntity(anyInt())).thenReturn(target);
    when(this.player.getTargetBlockFace(anyInt())).thenReturn(face);
    when(this.player.getTargetBlock(any(), anyInt())).thenReturn(block);
    when(this.player.getEyeLocation()).thenAnswer(_ -> eye.clone());
  }

  private Location eye(final double eyeX, final double eyeY, final double eyeZ, final float yaw) {
    final World world = this.fakeWorld.getWorld();
    return new Location(world, eyeX, eyeY, eyeZ, yaw, 0.0f);
  }

  private Location eye(final double eyeX, final double eyeY, final double eyeZ, final float yaw, final float pitch) {
    final World world = this.fakeWorld.getWorld();
    return new Location(world, eyeX, eyeY, eyeZ, yaw, pitch);
  }

  @Test
  void shouldFindThePixelOnAWallFacingSouth() {
    this.buildSouthWall();
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + AIMED_PIXEL_X, MAP_SIZE + AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  // the player looks at the frame in the last column of the lower row
  private ItemFrame buildSouthWall() {
    ItemFrame target = null;
    for (int row = 0; row < WALL_ROWS; row++) {
      target = this.addSouthWallRow(row, target);
    }
    final Block wallBlock = this.fakeWorld.block(3, BLOCK_Y, -1);
    final Location eye = this.eye(3.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    return target;
  }

  private @Nullable ItemFrame addSouthWallRow(final int row, final @Nullable ItemFrame previousTarget) {
    ItemFrame target = previousTarget;
    for (int column = 0; column < WALL_COLUMNS; column++) {
      final int blockY = TOP_ROW_Y - row;
      final Location location = this.fakeWorld.createLocation(column + HALF_BLOCK, blockY + HALF_BLOCK, 0.0);
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
    return target;
  }

  @Test
  void shouldFindThePixelOfTheFrameItWasGiven() {
    final ItemFrame target = this.buildSouthWall();
    when(this.player.getTargetEntity(anyInt())).thenReturn(null);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player, target).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + AIMED_PIXEL_X, MAP_SIZE + AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldCountTheColumnsFromTheFirstCornerOfTheWall() {
    this.buildSouthWallAwayFromTheOrigin();
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expected = { 3 * MAP_SIZE + AIMED_PIXEL_X, MAP_SIZE + AIMED_PIXEL_Y };
    assertArrayEquals(expected, coordinates, COLUMN_DISTANCE_MESSAGE);
  }

  // offset so a frame's column differs from its own coordinate
  private ItemFrame buildSouthWallAwayFromTheOrigin() {
    ItemFrame target = null;
    for (int row = 0; row < WALL_ROWS; row++) {
      target = this.addSouthWallRowAwayFromTheOrigin(row, target);
    }
    final Block wallBlock = this.fakeWorld.block(7, BLOCK_Y, -1);
    final Location eye = this.eye(7.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    return target;
  }

  private @Nullable ItemFrame addSouthWallRowAwayFromTheOrigin(final int row, final @Nullable ItemFrame previousTarget) {
    ItemFrame target = previousTarget;
    for (int column = 0; column < WALL_COLUMNS; column++) {
      final int blockX = 4 + column;
      final int blockY = TOP_ROW_Y - row;
      final Location location = this.fakeWorld.createLocation(blockX + HALF_BLOCK, blockY + HALF_BLOCK, 0.0);
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
    return target;
  }

  @Test
  void shouldCountTheColumnsOfAWallFacingWestFromItsFirstCorner() {
    final Location left = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 2.5);
    final Location right = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 3.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, BLOCK_Y, 3);
    final Location eye = this.eye(-EYE_DISTANCE, EYE_Y, 3.3, LOOK_EAST);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expected = { MAP_SIZE + AIMED_PIXEL_X, AIMED_PIXEL_Y };
    assertArrayEquals(expected, coordinates, COLUMN_DISTANCE_MESSAGE);
  }

  @Test
  void shouldFollowTheLineOfSightOfAPlayerLookingDown() {
    this.buildSouthWall();
    final Location eye = this.eye(3.3, 69.6, 5.3, LOOK_NORTH, 45.0f);
    when(this.player.getEyeLocation()).thenAnswer(_ -> eye.clone());
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expected = { 3 * MAP_SIZE + AIMED_PIXEL_X, MAP_SIZE + MIRRORED_PIXEL_X };
    assertArrayEquals(expected, coordinates);
  }

  @ParameterizedTest
  @CsvSource({
    "NONE, 38, 51",
    "FLIPPED, 38, 51",
    "CLOCKWISE_45, 51, 89",
    "FLIPPED_45, 51, 89",
    "CLOCKWISE, 89, 76",
    "COUNTER_CLOCKWISE, 89, 76",
    "CLOCKWISE_135, 76, 38",
    "COUNTER_CLOCKWISE_45, 76, 38",
  })
  void shouldTurnThePixelWithTheMapInsideTheFrame(final Rotation rotation, final int pixelX, final int pixelY) {
    final ItemFrame target = this.buildSouthWall();
    when(target.getRotation()).thenReturn(rotation);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + pixelX, MAP_SIZE + pixelY };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldIgnoreEntitiesAndFramesThatAreNotPartOfTheWall() {
    this.buildSouthWall();
    final Zombie zombie = mock(Zombie.class);
    final Location zombieLocation = this.fakeWorld.createLocation(2.5, FRAME_CENTER_Y, 0.5);
    when(zombie.getLocation()).thenReturn(zombieLocation);
    this.fakeWorld.addEntity(zombie);
    final Location sideways = this.fakeWorld.createLocation(2.5, 65.5, 0.0);
    this.fakeWorld.addFrame(sideways, BlockFace.EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final Location untagged = this.fakeWorld.createLocation(2.5, 66.5, 0.0);
    this.fakeWorld.addFrame(untagged, BlockFace.SOUTH, Keys.FIRST_MAP_KEY);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + AIMED_PIXEL_X, MAP_SIZE + AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldFindNoPixelOnAPlainFrameBesideALegacyWall() {
    this.buildSouthWall();
    final Location beside = this.fakeWorld.createLocation(4.5, FRAME_CENTER_Y, 0.0);
    final ItemFrame plain = this.fakeWorld.addFrame(beside, BlockFace.SOUTH);
    final Block wallBlock = this.fakeWorld.block(4, BLOCK_Y, -1);
    final Location eye = this.eye(4.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(plain, BlockFace.SOUTH, wallBlock, eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates, "a frame that is not a map of the wall is not part of it");
  }

  @Test
  void shouldMirrorThePixelOnAWallFacingNorth() {
    final Location left = this.fakeWorld.createLocation(1.5, FRAME_CENTER_Y, 0.0);
    final Location right = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, 0.0);
    this.fakeWorld.addFrame(left, BlockFace.NORTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.NORTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(0, BLOCK_Y, 1);
    final Location eye = this.eye(0.3, EYE_Y, -EYE_DISTANCE, LOOK_SOUTH);
    this.aimAt(target, BlockFace.NORTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { MAP_SIZE + MIRRORED_PIXEL_X, AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldFindThePixelOnAWallFacingWest() {
    final Location left = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 0.5);
    final Location right = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 1.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, BLOCK_Y, 1);
    final Location eye = this.eye(-EYE_DISTANCE, EYE_Y, 1.3, LOOK_EAST);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { MAP_SIZE + AIMED_PIXEL_X, AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldMirrorThePixelOnAWallFacingEast() {
    final Location left = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 1.5);
    final Location right = this.fakeWorld.createLocation(0.0, FRAME_CENTER_Y, 0.5);
    this.fakeWorld.addFrame(left, BlockFace.EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.EAST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(-1, BLOCK_Y, 0);
    final Location eye = this.eye(EYE_DISTANCE, EYE_Y, 0.3, LOOK_WEST);
    this.aimAt(target, BlockFace.EAST, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { MAP_SIZE + MIRRORED_PIXEL_X, AIMED_PIXEL_Y };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldFollowTheLineOfSightToAWallThatIsNotOnTheZeroPlane() {
    final Location left = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, DISTANT_WALL_PLANE);
    final Location right = this.fakeWorld.createLocation(1.5, FRAME_CENTER_Y, DISTANT_WALL_PLANE);
    this.fakeWorld.addFrame(left, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, BLOCK_Y, 7);
    final Location eye = this.eye(4.3, EYE_Y, 13.3, 150.0f);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expected = { MAP_SIZE + 30, AIMED_PIXEL_Y };
    assertArrayEquals(expected, coordinates, WALL_RAY_DIRECTION_MESSAGE);
  }

  @Test
  void shouldFollowTheLineOfSightToAWallFacingWestThatIsNotOnTheZeroPlane() {
    final Location left = this.fakeWorld.createLocation(DISTANT_WALL_PLANE, FRAME_CENTER_Y, 0.5);
    final Location right = this.fakeWorld.createLocation(DISTANT_WALL_PLANE, FRAME_CENTER_Y, 1.5);
    this.fakeWorld.addFrame(left, BlockFace.WEST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(right, BlockFace.WEST, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(9, BLOCK_Y, 1);
    final Location eye = this.eye(2.7, EYE_Y, -1.5, -60.0f);
    this.aimAt(target, BlockFace.WEST, wallBlock, eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expected = { MAP_SIZE + 71, AIMED_PIXEL_Y };
    assertArrayEquals(expected, coordinates, WALL_RAY_DIRECTION_MESSAGE);
  }

  @Test
  void shouldRejectUnsupportedFramesOnTheFloor() {
    ItemFrame target = null;
    for (int blockX = 0; blockX < 2; blockX++) {
      target = this.addFloorRow(blockX, target);
    }
    final Block floorBlock = this.fakeWorld.block(1, BLOCK_Y, 1);
    final Location eye = this.eye(1.3, 67.0, 1.6, LOOK_NORTH);
    this.aimAt(target, BlockFace.UP, floorBlock, eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  private @Nullable ItemFrame addFloorRow(final int blockX, final @Nullable ItemFrame previousTarget) {
    ItemFrame target = previousTarget;
    for (int blockZ = 0; blockZ < 2; blockZ++) {
      final Location location = this.fakeWorld.createLocation(blockX + HALF_BLOCK, 65.0, blockZ + HALF_BLOCK);
      final boolean first = blockX == 0 && blockZ == 0;
      final boolean last = blockX == 1 && blockZ == 1;
      if (first) {
        this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
      } else if (last) {
        target = this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
      } else {
        this.fakeWorld.addFrame(location, BlockFace.UP, Keys.MAP_KEY);
      }
    }
    return target;
  }

  @ParameterizedTest
  @CsvSource({ "2.9,64.5", "4.1,64.5", "3.5,63.9", "3.5,65.1" })
  void shouldRejectRaysOutsideTheSelectedFrame(final double eyeX, final double eyeY) {
    this.buildSouthWall();
    final Location eye = this.eye(eyeX, eyeY, EYE_DISTANCE, LOOK_NORTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @Test
  void shouldAcceptAnEyeOnTheMapPlaneLookingAcrossIt() {
    this.buildSouthWall();
    final Location eye = this.eye(3.5, FRAME_CENTER_Y, 0.0, LOOK_NORTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + 64, MAP_SIZE + 64 };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldRejectAnIntersectionBehindThePlayer() {
    this.buildSouthWall();
    final Location eye = this.eye(3.3, EYE_Y, EYE_DISTANCE, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @ParameterizedTest
  @CsvSource({ "5.0", "0.0" })
  void shouldRejectParallelAndCoplanarRays(final double eyeZ) {
    this.buildSouthWall();
    final Location eye = mock(Location.class);
    when(eye.getZ()).thenReturn(eyeZ);
    final Vector direction = new Vector(1, 0, 0);
    when(eye.getDirection()).thenReturn(direction);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @ParameterizedTest
  @CsvSource({ "3.0,64.0,0,127", "4.0,65.0,127,0" })
  void shouldKeepFrameEdgesInsideTheSelectedMap(final double eyeX, final double eyeY, final int pixelX, final int pixelY) {
    this.buildSouthWall();
    final Location eye = this.eye(eyeX, eyeY, -EYE_DISTANCE, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + pixelX, MAP_SIZE + pixelY };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldKeepRotatedFrameEdgesInsideTheSelectedMap() {
    final ItemFrame target = this.buildSouthWall();
    when(target.getRotation()).thenReturn(Rotation.CLOCKWISE);
    final Location eye = this.eye(3.0, 65.0, -EYE_DISTANCE, LOOK_SOUTH);
    when(this.player.getEyeLocation()).thenReturn(eye);
    final int[] coordinates = InteractUtils.findBoardCoordinates(this.player).orElseThrow();
    final int[] expectedCoordinates = { 3 * MAP_SIZE + 127, MAP_SIZE + 127 };
    assertArrayEquals(expectedCoordinates, coordinates);
  }

  @Test
  void shouldFailForWallsThatDoNotFaceAlongAnAxis() {
    final Location location = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, 0.0);
    final ItemFrame target = this.fakeWorld.addFrame(location, BlockFace.NORTH_EAST, Keys.MAP_KEY, Keys.FIRST_MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(0, BLOCK_Y, -1);
    final Location eye = this.eye(0.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(target, BlockFace.NORTH_EAST, wallBlock, eye);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      InteractUtils.findBoardCoordinates(this.player)
    );

    final String message = exception.getMessage();
    assertEquals("Block face NORTH_EAST is not a side of a block!", message);
  }

  @Test
  void shouldFindNoPixelWhenThePlayerLooksAtNoEntity() {
    when(this.player.getTargetEntity(anyInt())).thenReturn(null);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @Test
  void shouldFindNoPixelWhenThePlayerLooksAtNoBlockFace() {
    this.buildSouthWall();
    when(this.player.getTargetBlockFace(anyInt())).thenReturn(null);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @Test
  void shouldFindNoPixelOnAWallWithoutItsLastCorner() {
    final Location first = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, 0.0);
    final Location second = this.fakeWorld.createLocation(1.5, FRAME_CENTER_Y, 0.0);
    this.fakeWorld.addFrame(first, BlockFace.SOUTH, Keys.MAP_KEY, Keys.FIRST_MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(second, BlockFace.SOUTH, Keys.MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, BLOCK_Y, -1);
    final Location eye = this.eye(1.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @Test
  void shouldFindNoPixelOnAWallWithoutItsFirstCorner() {
    final Location first = this.fakeWorld.createLocation(0.5, FRAME_CENTER_Y, 0.0);
    final Location second = this.fakeWorld.createLocation(1.5, FRAME_CENTER_Y, 0.0);
    this.fakeWorld.addFrame(first, BlockFace.SOUTH, Keys.MAP_KEY);
    final ItemFrame target = this.fakeWorld.addFrame(second, BlockFace.SOUTH, Keys.MAP_KEY, Keys.LAST_MAP_KEY);
    final Block wallBlock = this.fakeWorld.block(1, BLOCK_Y, -1);
    final Location eye = this.eye(1.3, EYE_Y, EYE_DISTANCE, LOOK_NORTH);
    this.aimAt(target, BlockFace.SOUTH, wallBlock, eye);
    final Optional<int[]> coordinates = InteractUtils.findBoardCoordinates(this.player);
    assertEquals(Optional.empty(), coordinates);
  }

  @Test
  void shouldRejectEntitiesThatAreNotItemFrames() {
    final Zombie zombie = mock(Zombie.class);
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
      InteractUtils.findBoardCoordinates(this.player, zombie)
    );

    final String message = exception.getMessage();
    assertEquals("Target entity is not an ItemFrame!", message);
  }

  @Test
  void shouldRejectNullArguments() {
    final ItemFrame frame = mock(ItemFrame.class);
    assertThrows(NullPointerException.class, () -> InteractUtils.findBoardCoordinates(null));
    assertThrows(NullPointerException.class, () -> InteractUtils.findBoardCoordinates(null, frame));
    assertThrows(NullPointerException.class, () -> InteractUtils.findBoardCoordinates(this.player, null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(InteractUtils.class);
  }
}

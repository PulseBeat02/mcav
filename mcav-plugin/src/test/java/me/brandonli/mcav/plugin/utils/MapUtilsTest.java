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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.plugin.testing.FakeWorld;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// item stacks need a server, so their construction is mocked
final class MapUtilsTest {

  private static final int SCREEN_Y = 64;
  private static final double BLOCK_CENTER_Y = 64.5;

  private static final int NEXT_MAP_ID = 10;
  private static final int MISSING_MAP_ID = 5;

  private final List<MapMeta> mapMetadata = new ArrayList<>();
  private final List<List<?>> constructorArguments = new ArrayList<>();
  private final Map<Integer, MapView> existingMaps = new HashMap<>();

  private Server server;
  private FakeWorld fakeWorld;
  private MockedConstruction<ItemStack> items;

  @BeforeEach
  void setUp() {
    this.server = TestServer.reset();
    this.fakeWorld = new FakeWorld();
    when(this.server.getMap(anyInt())).thenAnswer(this::findExistingMap);
    this.items = Mockito.mockConstruction(ItemStack.class, this::recordMapItem);
  }

  private MapView findExistingMap(final InvocationOnMock invocation) {
    final int id = invocation.getArgument(0);
    return this.existingMap(id);
  }

  private void recordMapItem(final ItemStack item, final MockedConstruction.Context context) {
    final MapMeta metadata = mock(MapMeta.class);
    this.mapMetadata.add(metadata);
    final List<?> arguments = context.arguments();
    this.constructorArguments.add(arguments);
    when(item.getItemMeta()).thenReturn(metadata);
  }

  @AfterEach
  void closeConstructionMock() {
    this.items.close();
  }

  private MapView existingMap(final int id) {
    final MapView known = this.existingMaps.get(id);
    if (known != null) {
      return known;
    }

    final MapView created = mock(MapView.class);
    this.existingMaps.put(id, created);

    return created;
  }

  @Test
  void shouldCreateAMapItemForAnExistingMap() {
    final ItemStack item = MapUtils.createMapItem(7);
    final List<ItemStack> constructed = this.items.constructed();
    final int constructedCount = constructed.size();
    assertEquals(1, constructedCount);

    final ItemStack created = constructed.getFirst();
    assertSame(created, item);

    final List<?> arguments = this.constructorArguments.getFirst();
    final List<Material> expectedArguments = List.of(Material.FILLED_MAP);
    assertEquals(expectedArguments, arguments);

    final MapMeta metadata = this.mapMetadata.getFirst();
    final MapView existing = this.existingMap(7);
    verify(metadata).setMapView(existing);

    final Component lore = Component.text("Map ID [7]", NamedTextColor.RED);
    verify(metadata).lore(List.of(lore));
    verify(item, times(2)).setItemMeta(metadata);
    verify(this.server, never()).createMap(any(World.class));
  }

  @Test
  void shouldCreateMapsUntilTheIdExists() {
    final World world = this.fakeWorld.getWorld();
    when(this.server.getMap(MISSING_MAP_ID)).thenReturn(null);
    when(this.server.getWorlds()).thenReturn(List.of(world));
    final MapView third = mock(MapView.class);
    final MapView fourth = mock(MapView.class);
    final MapView fifth = mock(MapView.class);
    when(third.getId()).thenReturn(3);
    when(fourth.getId()).thenReturn(4);
    when(fifth.getId()).thenReturn(MISSING_MAP_ID);
    when(this.server.createMap(world)).thenReturn(third, fourth, fifth);
    MapUtils.createMapItem(MISSING_MAP_ID);
    verify(this.server, times(3)).createMap(world);

    final MapMeta metadata = this.mapMetadata.getFirst();
    verify(metadata).setMapView(fifth);
  }

  @Test
  void shouldCreateOneMapWhenTheNextIdIsTheWantedOne() {
    final World world = this.fakeWorld.getWorld();
    when(this.server.getMap(8)).thenReturn(null);
    when(this.server.getWorlds()).thenReturn(List.of(world));
    final MapView created = mock(MapView.class);
    when(created.getId()).thenReturn(8);
    when(this.server.createMap(world)).thenReturn(created);
    MapUtils.createMapItem(8);
    verify(this.server, times(1)).createMap(world);

    final MapMeta metadata = this.mapMetadata.getFirst();
    verify(metadata).setMapView(created);
  }

  @Test
  void shouldCreateAsManyMapsAsTheLargestWallNeeds() {
    final World world = this.fakeWorld.getWorld();
    final int wanted = NEXT_MAP_ID + MapUtils.MAXIMUM_NEW_MAPS;
    when(this.server.getMap(wanted)).thenReturn(null);
    this.handOutMapIdsFrom(world, NEXT_MAP_ID);
    MapUtils.createMapItem(wanted);
    verify(this.server, times(MapUtils.MAXIMUM_NEW_MAPS + 1)).createMap(world);

    final int size = this.mapMetadata.size();
    assertEquals(1, size);
  }

  private void handOutMapIdsFrom(final World world, final int firstId) {
    final AtomicInteger nextId = new AtomicInteger(firstId);
    final long lastAllowedId = firstId + 10_000L;
    when(this.server.getWorlds()).thenReturn(List.of(world));
    when(this.server.getMap(anyInt())).thenAnswer(invocation -> this.findHandedOutMap(nextId, invocation));
    when(this.server.createMap(world)).thenAnswer(invocation -> this.handOutNextMap(nextId, lastAllowedId));
  }

  private @Nullable MapView findHandedOutMap(final AtomicInteger nextId, final InvocationOnMock invocation) {
    final int id = invocation.getArgument(0);
    final int nextFreeId = nextId.get();
    if (id < 0 || id >= nextFreeId) {
      return null;
    }

    return this.existingMap(id);
  }

  private MapView handOutNextMap(final AtomicInteger nextId, final long lastAllowedId) {
    final int id = nextId.getAndIncrement();
    if (id > lastAllowedId) {
      final String message = "Map %s was created, far more maps than any wall needs!".formatted(id);
      throw new AssertionError(message);
    }

    final MapView created = mock(MapView.class);
    when(created.getId()).thenReturn(id);

    return created;
  }

  @Test
  void shouldCreateAsManyMapsAsTheLargestWallNeedsInAWorldWithoutMaps() {
    final World world = this.fakeWorld.getWorld();
    this.handOutMapIdsFrom(world, 0);
    MapUtils.createMapItem(MapUtils.MAXIMUM_NEW_MAPS);
    verify(this.server, times(MapUtils.MAXIMUM_NEW_MAPS + 1)).createMap(world);

    final int size = this.mapMetadata.size();
    assertEquals(1, size);
  }

  @Test
  void shouldRefuseToCreateMoreMapsThanTheLargestWallNeeds() {
    final World world = this.fakeWorld.getWorld();
    final int wanted = 11 + MapUtils.MAXIMUM_NEW_MAPS;
    when(this.server.getMap(wanted)).thenReturn(null);
    this.handOutMapIdsFrom(world, NEXT_MAP_ID);
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> MapUtils.createMapItem(wanted));

    final String message = failure.getMessage();
    assertEquals("Map id 4107 is more than 4096 ids past the next map id, because map 10 does not exist!", message);
    verify(this.server, never()).createMap(world);

    final boolean empty = this.mapMetadata.isEmpty();
    assertTrue(empty, "no map item is made for a refused id");
  }

  @Test
  void shouldRefuseAFarAwayMapIdBeforeChangingTheWorld() {
    final World world = this.fakeWorld.getWorld();
    final Player player = this.playerFacing(BlockFace.NORTH);
    final Location location = this.fakeWorld.createLocation(0, SCREEN_Y, 0);
    final int farId = Integer.MAX_VALUE - 1;
    when(this.server.getMap(Integer.MAX_VALUE)).thenReturn(null);
    this.handOutMapIdsFrom(world, 0);
    assertThrows(IllegalArgumentException.class, () -> MapUtils.buildMapScreen(player, location, Material.STONE, 2, 1, farId));
    verify(this.server, never()).createMap(world);

    final int changedBlocks = this.fakeWorld.getChangedBlockCount();
    assertEquals(0, changedBlocks);

    final boolean empty = this.fakeWorld.getSpawnedFrames().isEmpty();
    assertTrue(empty);

    final boolean mapMetadataEmpty = this.mapMetadata.isEmpty();
    assertTrue(mapMetadataEmpty);
  }

  private Player playerFacing(final BlockFace face) {
    final Player player = mock(Player.class);
    when(player.getFacing()).thenReturn(face);

    return player;
  }

  @Test
  void shouldRejectAMissingHistoricalMapInsteadOfDisplayingAnotherId() {
    final World world = this.fakeWorld.getWorld();
    when(this.server.getMap(MISSING_MAP_ID)).thenReturn(null);
    when(this.server.getWorlds()).thenReturn(List.of(world));
    final MapView later = mock(MapView.class);
    when(later.getId()).thenReturn(20);
    when(this.server.createMap(world)).thenReturn(later);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> MapUtils.createMapItem(MISSING_MAP_ID));

    final String message = failure.getMessage();
    assertEquals("Map id 5 is unavailable, because the server allocated 20 instead!", message);

    verify(this.server).createMap(world);

    final boolean empty = this.mapMetadata.isEmpty();
    assertTrue(empty, "no item may silently target a map outside the requested layout");
  }

  @Test
  void shouldRejectOverflowingScreenIdsBeforeChangingTheWorld() {
    final Player player = this.playerFacing(BlockFace.NORTH);
    final Location location = this.fakeWorld.createLocation(0, SCREEN_Y, 0);
    assertThrows(IllegalArgumentException.class, () -> MapUtils.buildMapScreen(player, location, Material.STONE, 2, 1, Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () ->
      MapUtils.buildMapScreen(player, location, Material.STONE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0)
    );

    final int changedBlocks = this.fakeWorld.getChangedBlockCount();
    assertEquals(0, changedBlocks);

    final boolean empty = this.fakeWorld.getSpawnedFrames().isEmpty();
    assertTrue(empty);

    final boolean mapMetadataEmpty = this.mapMetadata.isEmpty();
    assertTrue(mapMetadataEmpty);
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "NORTH | SOUTH | 0,65,0;1,65,0;0,64,0;1,64,0",
      "SOUTH | NORTH | 1,65,0;0,65,0;1,64,0;0,64,0",
      "EAST | WEST | 0,65,-1;0,65,0;0,64,-1;0,64,0",
      "WEST | EAST | 0,65,0;0,65,-1;0,64,0;0,64,-1",
    }
  )
  void shouldBuildTheScreenRowByRowFromTheTopLeftCornerAsSeenByThePlayer(
    final BlockFace face,
    final BlockFace expectedFrameFacing,
    final String expectedFrames
  ) {
    final Player player = this.playerFacing(face);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(player, location, Material.STONE, 2, 2, 10);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final String positions = this.describe(frames);
    assertEquals(expectedFrames, positions);

    final int changed = this.fakeWorld.getChangedBlockCount();
    assertEquals(4, changed);

    final int offsetX = face.getModX();
    final int offsetZ = face.getModZ();
    for (int index = 0; index < frames.size(); index++) {
      final ItemFrame frame = frames.get(index);
      final int[] position = this.coordinates(frame);
      final Material wall = this.fakeWorld.getMaterial(position[0] + offsetX, position[1], position[2] + offsetZ);
      assertEquals(Material.STONE, wall);
      verify(frame).setFacingDirection(expectedFrameFacing);
      verify(frame).setInvulnerable(true);
      verify(frame).setGravity(false);

      final List<ItemStack> constructed = this.items.constructed();
      final ItemStack item = constructed.get(index);
      verify(frame).setItem(item);

      final MapMeta metadata = this.mapMetadata.get(index);
      final MapView shown = this.existingMap(10 + index);
      verify(metadata).setMapView(shown);

      final boolean hasMapTag = this.fakeWorld.hasTag(frame, Keys.MAP_KEY);
      final boolean first = this.fakeWorld.hasTag(frame, Keys.FIRST_MAP_KEY);
      final boolean last = this.fakeWorld.hasTag(frame, Keys.LAST_MAP_KEY);
      assertTrue(hasMapTag);
      assertEquals(index == 0, first);
      assertEquals(index == 3, last);
    }
  }

  private int[] coordinates(final ItemFrame frame) {
    final Location location = frame.getLocation();
    final int blockX = location.getBlockX();
    final int blockY = location.getBlockY();
    final int blockZ = location.getBlockZ();
    return new int[] { blockX, blockY, blockZ };
  }

  private String describe(final List<ItemFrame> frames) {
    final List<String> positions = new ArrayList<>();
    for (final ItemFrame frame : frames) {
      final int[] position = this.coordinates(frame);
      positions.add("%s,%s,%s".formatted(position[0], position[1], position[2]));
    }
    return String.join(";", positions);
  }

  @Test
  void shouldGiveAllFramesOfOneScreenTheSameIdentityAndDifferentScreensDifferentIdentities() {
    final CommandSender console = mock(CommandSender.class);
    final Location firstLocation = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    final Location secondLocation = this.fakeWorld.createLocation(2.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(console, firstLocation, Material.STONE, 2, 1, 0);
    MapUtils.buildMapScreen(console, secondLocation, Material.STONE, 1, 1, 2);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final PersistentDataContainer first = frames.get(0).getPersistentDataContainer();
    final PersistentDataContainer same = frames.get(1).getPersistentDataContainer();
    final PersistentDataContainer other = frames.get(2).getPersistentDataContainer();
    final String firstId = first.get(Keys.SCREEN_KEY, PersistentDataType.STRING);
    final String sameId = same.get(Keys.SCREEN_KEY, PersistentDataType.STRING);
    final String otherId = other.get(Keys.SCREEN_KEY, PersistentDataType.STRING);
    Assertions.assertNotNull(firstId);
    Assertions.assertNotNull(otherId);
    assertEquals(firstId, sameId);
    Assertions.assertNotEquals(firstId, otherId);
  }

  @Test
  void shouldBuildTheScreenToTheNorthForSendersThatAreNotPlayers() {
    final CommandSender console = mock(CommandSender.class);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(console, location, Material.OBSIDIAN, 2, 1, 0);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final String positions = this.describe(frames);
    assertEquals("0,64,0;1,64,0", positions);

    final Material left = this.fakeWorld.getMaterial(0, SCREEN_Y, -1);
    final Material right = this.fakeWorld.getMaterial(1, SCREEN_Y, -1);
    assertEquals(Material.OBSIDIAN, left);
    assertEquals(Material.OBSIDIAN, right);

    final ItemFrame first = frames.getFirst();
    verify(first).setFacingDirection(BlockFace.SOUTH);
  }

  @Test
  void shouldKeepNonFrameOccupantsAndAdjacentFramesWhenRebuilding() {
    final CommandSender console = mock(CommandSender.class);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    final Location occupied = this.fakeWorld.createLocation(0.5, BLOCK_CENTER_Y, 0.5);
    final ItemFrame existing = this.fakeWorld.addFrame(occupied, BlockFace.SOUTH);
    final Entity other = mock(Entity.class);
    when(other.getLocation()).thenReturn(occupied);
    this.fakeWorld.addEntity(other);
    final Location adjacentLocation = this.fakeWorld.createLocation(1.5, BLOCK_CENTER_Y, 0.5);
    final ItemFrame adjacent = this.fakeWorld.addFrame(adjacentLocation, BlockFace.SOUTH);

    MapUtils.buildMapScreen(console, location, Material.OBSIDIAN, 1, 1, 0);

    verify(existing).remove();
    verify(other, never()).remove();
    verify(adjacent, never()).remove();
  }

  @Test
  void shouldRemoveTheFramesAlreadyHangingWhereTheScreenIsBuilt() {
    final CommandSender console = mock(CommandSender.class);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    final Location occupied = this.fakeWorld.createLocation(0.5, BLOCK_CENTER_Y, 0.5);
    final ItemFrame existing = this.fakeWorld.addFrame(occupied, BlockFace.SOUTH);
    MapUtils.buildMapScreen(console, location, Material.OBSIDIAN, 1, 1, 0);
    // a block holds one frame per side, so a frame left in place would push the new one onto the opposite side
    verify(existing).remove();

    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final int frameCount = frames.size();
    assertEquals(1, frameCount);
  }

  @Test
  void shouldMarkTheOnlyFrameOfASingleMapScreenAsBothCorners() {
    final Player player = this.playerFacing(BlockFace.NORTH);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(player, location, Material.STONE, 1, 1, 0);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final int frameCount = frames.size();
    assertEquals(1, frameCount);

    final ItemFrame frame = frames.getFirst();
    final boolean hasMapTag = this.fakeWorld.hasTag(frame, Keys.MAP_KEY);
    final boolean first = this.fakeWorld.hasTag(frame, Keys.FIRST_MAP_KEY);
    final boolean last = this.fakeWorld.hasTag(frame, Keys.LAST_MAP_KEY);
    assertTrue(hasMapTag);
    assertTrue(first);
    assertTrue(last);
  }

  @ParameterizedTest
  @EnumSource(value = BlockFace.class, names = { "UP", "DOWN" })
  void shouldBuildNothingForAPlayerFacingThatIsNotCardinal(final BlockFace facing) {
    final Player player = this.playerFacing(facing);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(player, location, Material.STONE, 2, 2, 0);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final boolean nothingSpawned = frames.isEmpty();
    final int changed = this.fakeWorld.getChangedBlockCount();
    assertTrue(nothingSpawned);
    assertEquals(0, changed);

    final List<ItemStack> constructed = this.items.constructed();
    final boolean noItems = constructed.isEmpty();
    assertTrue(noItems);
  }

  @Test
  void shouldPlaceTheWallInFrontOfTheLocation() {
    final Player player = this.playerFacing(BlockFace.NORTH);
    final Location location = this.fakeWorld.createLocation(10.5, 70.0, -3.5);
    MapUtils.buildMapScreen(player, location, Material.STONE, 1, 2, 0);
    final Material bottom = this.fakeWorld.getMaterial(10, 70, -5);
    final Material top = this.fakeWorld.getMaterial(10, 71, -5);
    final Material beside = this.fakeWorld.getMaterial(11, 70, -5);
    assertEquals(Material.STONE, bottom);
    assertEquals(Material.STONE, top);
    assertEquals(Material.AIR, beside);

    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final String positions = this.describe(frames);
    assertEquals("10,71,-4;10,70,-4", positions);

    final ItemFrame lower = frames.get(1);
    final boolean lowerIsFirst = this.fakeWorld.hasTag(lower, Keys.FIRST_MAP_KEY);
    assertFalse(lowerIsFirst);
  }

  @Test
  void shouldBuildTheHighestTwoValidMapIdsWithoutOverflow() {
    final CommandSender console = mock(CommandSender.class);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    MapUtils.buildMapScreen(console, location, Material.STONE, 2, 1, Integer.MAX_VALUE - 1);
    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();

    final int size = frames.size();
    assertEquals(2, size);

    final MapMeta first = this.mapMetadata.get(0);
    final MapMeta second = this.mapMetadata.get(1);
    final MapView firstMap = this.existingMap(Integer.MAX_VALUE - 1);
    final MapView lastMap = this.existingMap(Integer.MAX_VALUE);
    verify(first).setMapView(firstMap);
    verify(second).setMapView(lastMap);
  }

  @Test
  void shouldRefuseInvalidArguments() {
    final Player player = this.playerFacing(BlockFace.NORTH);
    final Location location = this.fakeWorld.createLocation(0.5, SCREEN_Y, 0.5);
    assertThrows(IllegalArgumentException.class, () -> MapUtils.createMapItem(-1));
    assertThrows(NullPointerException.class, () -> MapUtils.buildMapScreen(null, location, Material.STONE, 1, 1, 0));
    assertThrows(NullPointerException.class, () -> MapUtils.buildMapScreen(player, null, Material.STONE, 1, 1, 0));
    assertThrows(NullPointerException.class, () -> MapUtils.buildMapScreen(player, location, null, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> MapUtils.buildMapScreen(player, location, Material.STONE, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> MapUtils.buildMapScreen(player, location, Material.STONE, 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> MapUtils.buildMapScreen(player, location, Material.STONE, 1, 1, -1));

    final List<ItemFrame> frames = this.fakeWorld.getSpawnedFrames();
    final boolean nothingSpawned = frames.isEmpty();
    assertTrue(nothingSpawned);
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(MapUtils.class);
  }
}

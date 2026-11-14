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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class Mcv2ScreenTest {

  private static final int ORIGIN_HEIGHT = 64;

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000011");

  private static final int WALL_MAP = 100;
  private static final int PAGE_MAP = 500;

  private static final String TEAM_SUFFIX_PATTERN = "[0-9a-z]{1,7}";

  private FakeServer server;

  private CraftPlayer player;

  private static final int[] PAGES_OF_A_THREE_BY_TWO_WALL = { 500, 501, 500, 501, 500, 501 };

  private World world;

  private final List<Location> spawned = new ArrayList<>();

  private final List<ItemFrame> frames = new ArrayList<>();

  private final Map<Integer, ItemStack> items = new HashMap<>();

  private MockedStatic<Mcv2Screen> statics;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.player = this.server.addPlayer(VIEWER);
    this.server.injectModule();
    this.world = mock(World.class);
    when(this.world.spawn(any(Location.class), eq(ItemFrame.class), ArgumentMatchers.<Consumer<? super ItemFrame>>any())).thenAnswer(
      this::spawnItemFrame
    );
    // item components are only bound on a running server, so the tests that build a screen get mock page items
    this.statics = Mockito.mockStatic(Mcv2Screen.class, Mockito.CALLS_REAL_METHODS);
    this.statics
      .when(() -> Mcv2Screen.pageItem(ArgumentMatchers.anyInt()))
      .thenAnswer(invocation -> this.items.computeIfAbsent(invocation.getArgument(0), _ -> mock(ItemStack.class)));
  }

  private ItemFrame spawnItemFrame(final InvocationOnMock invocation) {
    final ItemFrame frame = mock(ItemFrame.class);
    when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
    final Consumer<? super ItemFrame> configurator = invocation.getArgument(2);
    configurator.accept(frame);
    this.spawned.add(invocation.getArgument(0));
    this.frames.add(frame);

    return frame;
  }

  @AfterEach
  void stopServer() {
    this.statics.close();
    this.server.close();
  }

  private Mcv2Configuration configuration(final BlockFace facing) {
    return this.configuration(facing, 10);
  }

  private Mcv2Configuration configuration(final BlockFace facing, final int originX) {
    return Mcv2Configuration.builder()
      .viewers(List.of(VIEWER))
      .origin(new Location(this.world, originX, ORIGIN_HEIGHT, -4))
      .facing(facing)
      .map(WALL_MAP)
      .columns(3)
      .rows(2)
      .pageMap(PAGE_MAP)
      .pageSlots(2)
      .streamId(70)
      .outlineColor(NamedTextColor.GOLD)
      .build();
  }

  @Test
  void shouldSpawnOneHiddenGlowingPageFrameBehindEveryMap() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();

    final int frameCount = this.frames.size();
    assertEquals(6, frameCount);
    // facing south, columns count east and the frames hang two blocks north of the wall's frames
    final Location expectedFirstLocation = new Location(this.world, 10, ORIGIN_HEIGHT, -6);
    final Location firstLocation = this.spawned.get(0);
    assertEquals(expectedFirstLocation, firstLocation);

    final Location expectedThirdLocation = new Location(this.world, 12, ORIGIN_HEIGHT, -6);
    final Location thirdLocation = this.spawned.get(2);
    assertEquals(expectedThirdLocation, thirdLocation);

    final Location expectedFifthLocation = new Location(this.world, 11, 63, -6);
    final Location fifthLocation = this.spawned.get(4);
    assertEquals(expectedFifthLocation, fifthLocation);

    for (int index = 0; index < 6; index++) {
      final ItemFrame frame = this.frames.get(index);
      verify(frame).setVisibleByDefault(false);
      verify(frame).setPersistent(false);
      verify(frame).setFacingDirection(BlockFace.NORTH, true);
      verify(frame).setGlowing(true);
      verify(frame).setInvulnerable(true);
      verify(frame).setFixed(true);
      verify(frame).setSilent(true);
      verify(frame).setItem(this.items.get(PAGES_OF_A_THREE_BY_TWO_WALL[index]), false);
    }

    final List<ItemFrame> screenFrames = screen.getFrames();
    assertEquals(this.frames, screenFrames);
    assertThrows(IllegalStateException.class, screen::build);
  }

  @Test
  void shouldPlaceTheFramesBehindAWallFacingEast() {
    new Mcv2Screen(this.configuration(BlockFace.EAST)).build();

    // facing east, columns count north and the frames hang two blocks west
    final Location expectedFirstLocation = new Location(this.world, 8, ORIGIN_HEIGHT, -4);
    final Location firstLocation = this.spawned.get(0);
    assertEquals(expectedFirstLocation, firstLocation);

    final Location expectedThirdLocation = new Location(this.world, 8, ORIGIN_HEIGHT, -6);
    final Location thirdLocation = this.spawned.get(2);
    assertEquals(expectedThirdLocation, thirdLocation);
  }

  @Test
  void shouldRefuseAnOriginWithoutAWorld() {
    final Mcv2Configuration unloaded = mock(Mcv2Configuration.class);
    when(unloaded.getOrigin()).thenReturn(new Location(null, 0, 0, 0));
    final Mcv2Screen screen = new Mcv2Screen(unloaded);
    final NullPointerException missingWorld = assertThrows(NullPointerException.class, screen::build);
    final String missingWorldMessage = missingWorld.getMessage();
    assertEquals("The origin must be in a world!", missingWorldMessage);

    final List<ItemFrame> framesAfterFailure = screen.getFrames();
    assertEquals(List.of(), framesAfterFailure, "a failed build leaves no page frames");
  }

  @Test
  void shouldRefuseANullConfiguration() {
    final NullPointerException missingConfiguration = assertThrows(NullPointerException.class, () -> new Mcv2Screen(null));
    final String missingConfigurationMessage = missingConfiguration.getMessage();
    assertEquals("Configuration must not be null!", missingConfigurationMessage);
  }

  @Test
  void shouldNameAMapThatNeedNotExist() {
    this.statics.close();
    final ItemStack copy = mock(ItemStack.class);
    try (
      MockedConstruction<net.minecraft.world.item.ItemStack> stacks = Mockito.mockConstruction(net.minecraft.world.item.ItemStack.class); // fqn: Minecraft's ItemStack beside the imported Bukkit one
      MockedStatic<CraftItemStack> crafts = Mockito.mockStatic(CraftItemStack.class)
    ) {
      crafts.when(() -> CraftItemStack.asBukkitCopy(ArgumentMatchers.any(net.minecraft.world.item.ItemStack.class))).thenReturn(copy); // fqn: Minecraft's ItemStack beside the imported Bukkit one
      final ItemStack pageItem = Mcv2Screen.pageItem(2_000_000_123);
      assertSame(copy, pageItem);
      verify(stacks.constructed().getFirst()).set(DataComponents.MAP_ID, new MapId(2_000_000_123));
    }

    this.statics = Mockito.mockStatic(Mcv2Screen.class, Mockito.CALLS_REAL_METHODS);

    // in reading order, on a wall of three columns with four slots
    final int topLeftSlot = Mcv2Screen.slot(0, 0, 3, 4);
    assertEquals(0, topLeftSlot);

    final int thirdRowSecondColumnSlot = Mcv2Screen.slot(1, 2, 3, 4);
    assertEquals(3, thirdRowSecondColumnSlot);

    final int thirdRowThirdColumnSlot = Mcv2Screen.slot(2, 2, 3, 4);
    assertEquals(0, thirdRowThirdColumnSlot);
    // the second row of a wall of two columns goes on with the third slot
    final int secondRowFirstColumnSlot = Mcv2Screen.slot(0, 1, 2, 4);
    assertEquals(2, secondRowFirstColumnSlot);
  }

  @Test
  void shouldShowTheFramesTheirTeamAndTheAnchorsToAPlayer() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    screen.show(this.player);
    for (final ItemFrame frame : this.frames) {
      verify(this.player).showEntity(this.server.getPlugin(), frame);
    }

    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final int packetCount = packets.size();
    assertEquals(2, packetCount);

    final Packet<?> teamPacket = packets.get(0);
    final ClientboundSetPlayerTeamPacket team = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, teamPacket);
    final boolean namedAsScreenTeam = team.getName().matches(Mcv2Screen.TEAM_PREFIX + TEAM_SUFFIX_PATTERN);
    assertTrue(namedAsScreenTeam);

    final String expectedName = screen.createTeam().getName();
    final String teamName = team.getName();
    assertEquals(expectedName, teamName);

    final Optional<TeamColor> expectedColor = Optional.of(TeamColor.GOLD);
    final Optional<TeamColor> color = team.getParameters().orElseThrow().color();
    assertEquals(expectedColor, color);

    final int memberCount = team.getPlayers().size();
    assertEquals(6, memberCount);

    final boolean holdsTheFourthFrame = team.getPlayers().contains(this.frames.get(3).getUniqueId().toString());
    assertTrue(holdsTheFourthFrame);

    final List<ClientboundMapItemDataPacket> anchors = MapPackets.unbundle(packets.get(1));
    final int anchorCount = anchors.size();
    assertEquals(6, anchorCount);

    MapPackets.assertMapPacket(anchors.get(0), screen.getAnchors().get(0));
    screen.hide(this.player);
    for (final ItemFrame frame : this.frames) {
      verify(this.player).hideEntity(this.server.getPlugin(), frame);
    }

    final Packet<?> removalPacket = this.server.getSentPackets(VIEWER).get(2);
    final ClientboundSetPlayerTeamPacket removal = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, removalPacket);
    final String expectedRemovedName = team.getName();
    final String removedName = removal.getName();
    assertEquals(expectedRemovedName, removedName);

    assertThrows(NullPointerException.class, () -> screen.show(null));
    assertThrows(NullPointerException.class, () -> screen.hide(null));
  }

  @Test
  void shouldNotRemoveAnotherScreensTeamWhenHidingOneScreen() {
    final Mcv2Screen first = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    final Mcv2Screen second = new Mcv2Screen(this.configuration(BlockFace.SOUTH, 11));
    first.build();
    second.build();
    try {
      first.show(this.player);
      second.show(this.player);
      first.hide(this.player);
      final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
      final Packet<?> firstTeamPacket = packets.get(0);
      final ClientboundSetPlayerTeamPacket firstTeam = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, firstTeamPacket);
      final Packet<?> secondTeamPacket = packets.get(2);
      final ClientboundSetPlayerTeamPacket secondTeam = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, secondTeamPacket);
      final Packet<?> removalPacket = packets.get(4);
      final ClientboundSetPlayerTeamPacket removal = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, removalPacket);
      final String firstTeamName = firstTeam.getName();
      final String removedName = removal.getName();
      assertEquals(firstTeamName, removedName);

      final String secondTeamName = secondTeam.getName();
      final String removedNameAgain = removal.getName();
      assertNotEquals(secondTeamName, removedNameAgain, "a screen removes only its own team");

      final Set<String> expectedMembers = new HashSet<>(
        second
          .getFrames()
          .stream()
          .map(frame -> frame.getUniqueId().toString())
          .toList()
      );
      final Set<String> members = Set.copyOf(secondTeam.getPlayers());
      assertEquals(expectedMembers, members);

      second.hide(this.player);
      final Packet<?> secondRemovalPacket = this.server.getSentPackets(VIEWER).get(5);
      final ClientboundSetPlayerTeamPacket secondRemoval = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, secondRemovalPacket);
      final String expectedSecondRemovedName = secondTeam.getName();
      final String secondRemovedName = secondRemoval.getName();
      assertEquals(expectedSecondRemovedName, secondRemovedName);
    } finally {
      first.remove();
      second.remove();
    }
  }

  @Test
  void shouldWriteTheAnchorOfEveryMapInItsFirstRow() {
    final List<MapTilePatch> anchors = new Mcv2Screen(this.configuration(BlockFace.WEST)).getAnchors();
    final int anchorCount = anchors.size();
    assertEquals(6, anchorCount);

    final MapTilePatch patch = anchors.get(4);
    final int mapId = patch.getMapId();
    assertEquals(104, mapId);

    final int row = patch.getY();
    assertEquals(0, row);

    final int height = patch.getHeight();
    assertEquals(1, height);

    final byte[] expected = new byte[128];
    // after the signature: column, row, columns, rows, facing, the stream id 70 in two symbols (6 + 1 * 64), the check
    final int[] symbols = { 21, 3, 58, 44, 9, 37, 60, 17, 1, 1, 3, 2, 1, 6, 1, (1 + 1 + 3 + 2 + 1 + 6 + 1) & 63 };
    for (int index = 0; index < expected.length; index++) {
      expected[index] = (byte) ((index < symbols.length ? symbols[index] : 0) + 4);
    }

    final byte[] colors = patch.getColors();
    assertArrayEquals(expected, colors);
  }

  @Test
  void shouldRemoveTheFrames() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    screen.remove();
    for (final ItemFrame frame : this.frames) {
      verify(frame).remove();
    }

    final List<ItemFrame> framesAfterRemoval = screen.getFrames();
    assertEquals(List.of(), framesAfterRemoval);
  }

  @Test
  void shouldKeepTheChunkOfItsFramesLoadedUntilRemoved() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    // the frames hang at x 10 to 12 and z -6: chunk 0, -1
    verify(this.world).addPluginChunkTicket(0, -1, this.server.getPlugin());
    verify(this.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));

    screen.remove();
    verify(this.world).removePluginChunkTicket(0, -1, this.server.getPlugin());
    verify(this.world).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));

    screen.remove();
    verify(this.world).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
  }

  @Test
  void shouldKeepEveryChunkItsFramesSpanLoaded() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH, 14));
    screen.build();
    // the frames hang at x 14 to 16: chunks 0 and 1
    verify(this.world).addPluginChunkTicket(0, -1, this.server.getPlugin());
    verify(this.world).addPluginChunkTicket(1, -1, this.server.getPlugin());

    screen.remove();
    verify(this.world).removePluginChunkTicket(0, -1, this.server.getPlugin());
    verify(this.world).removePluginChunkTicket(1, -1, this.server.getPlugin());
  }

  @Test
  void shouldShareAChunkWithAnotherScreenUntilBothAreRemoved() {
    final Mcv2Screen first = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    final Mcv2Screen second = new Mcv2Screen(this.configuration(BlockFace.SOUTH, 11));
    first.build();
    second.build();
    verify(this.world).addPluginChunkTicket(0, -1, this.server.getPlugin());

    first.remove();
    verify(this.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));

    second.remove();
    verify(this.world).removePluginChunkTicket(0, -1, this.server.getPlugin());
    // a screen built again holds the chunk again
    first.build();
    verify(this.world, times(2)).addPluginChunkTicket(0, -1, this.server.getPlugin());

    first.remove();
  }

  @Test
  void shouldColorTheTeam() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    final PlayerTeam team = screen.createTeam();

    final Optional<TeamColor> expectedColor = Optional.of(TeamColor.GOLD);
    final Optional<TeamColor> color = team.getColor();
    assertEquals(expectedColor, color);

    final boolean namedAsScreenTeam = team.getName().matches(Mcv2Screen.TEAM_PREFIX + TEAM_SUFFIX_PATTERN);
    assertTrue(namedAsScreenTeam);

    final String expectedName = screen.createTeam().getName();
    final String teamName = team.getName();
    assertEquals(expectedName, teamName);
  }

  @Test
  void shouldHangEveryPageSlotBehindSomeMapOfTheWall() {
    final Map<String, Set<Integer>> missing = new TreeMap<>();
    for (final int[] wall : new int[][] { { 1, 1 }, { 2, 1 }, { 1, 3 }, { 2, 2 }, { 3, 3 }, { 4, 3 }, { 5, 3 }, { 16, 9 } }) {
      this.items.clear();
      final Mcv2Configuration configuration = Mcv2Configuration.builder()
        .viewers(List.of(VIEWER))
        .origin(new Location(this.world, 10, ORIGIN_HEIGHT, -4))
        .facing(BlockFace.SOUTH)
        .map(WALL_MAP)
        .columns(wall[0])
        .rows(wall[1])
        .pageMap(PAGE_MAP)
        .build();
      new Mcv2Screen(configuration).build();
      final Set<Integer> absent = this.findAbsentPageSlots(configuration);
      if (!absent.isEmpty()) {
        missing.put("%sx%s with %s slots".formatted(wall[0], wall[1], configuration.getPageSlots()), absent);
      }
    }

    assertEquals(Map.of(), missing, "the page slots no map of the wall hangs in front of");
  }

  private Set<Integer> findAbsentPageSlots(final Mcv2Configuration configuration) {
    final Set<Integer> absent = new TreeSet<>();
    for (int slot = 0; slot < configuration.getPageSlots(); slot++) {
      if (!this.items.containsKey(PAGE_MAP + slot)) {
        absent.add(slot);
      }
    }

    return absent;
  }
}

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class Mcv2ScreenTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000011");

  private FakeServer server;
  private CraftPlayer player;
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
      invocation -> {
        final ItemFrame frame = mock(ItemFrame.class);
        when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
        final Consumer<? super ItemFrame> configurator = invocation.getArgument(2);
        configurator.accept(frame);
        this.spawned.add(invocation.getArgument(0));
        this.frames.add(frame);
        return frame;
      }
    );
    // item components are only bound on a running server, so the tests that build a screen get mock page items
    this.statics = Mockito.mockStatic(Mcv2Screen.class, Mockito.CALLS_REAL_METHODS);
    this.statics.when(() -> Mcv2Screen.pageItem(ArgumentMatchers.anyInt())).thenAnswer(invocation ->
        this.items.computeIfAbsent(invocation.getArgument(0), _ -> mock(ItemStack.class))
      );
  }

  @AfterEach
  void stopServer() {
    this.statics.close();
    this.server.close();
  }

  private Mcv2Configuration configuration(final BlockFace facing) {
    return Mcv2Configuration.builder()
      .viewers(List.of(VIEWER))
      .origin(new Location(this.world, 10, 64, -4))
      .facing(facing)
      .map(100)
      .columns(3)
      .rows(2)
      .pageMap(500)
      .pageSlots(2)
      .outlineColor(NamedTextColor.GOLD)
      .build();
  }

  @Test
  void spawnsOneHiddenGlowingPageFrameBehindEveryMap() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    assertEquals(6, this.frames.size());
    // facing south, columns count east and the frames hang two blocks north of the wall's frames
    assertEquals(new Location(this.world, 10, 64, -6), this.spawned.get(0));
    assertEquals(new Location(this.world, 12, 64, -6), this.spawned.get(2));
    assertEquals(new Location(this.world, 11, 63, -6), this.spawned.get(4));
    for (int i = 0; i < 6; i++) {
      final ItemFrame frame = this.frames.get(i);
      verify(frame).setVisibleByDefault(false);
      verify(frame).setPersistent(false);
      verify(frame).setFacingDirection(BlockFace.NORTH, true);
      verify(frame).setGlowing(true);
      verify(frame).setInvulnerable(true);
      verify(frame).setFixed(true);
      verify(frame).setSilent(true);
      final int column = i % 3;
      final int row = i / 3;
      final ItemStack expected = this.items.get(500 + Mcv2Screen.slot(column, row, 2));
      verify(frame).setItem(expected, false);
    }
    assertEquals(this.frames, screen.getFrames());
    assertThrows(IllegalStateException.class, screen::build);
  }

  @Test
  void placesTheFramesBehindAWallFacingEast() {
    new Mcv2Screen(this.configuration(BlockFace.EAST)).build();
    // facing east, columns count north and the frames hang two blocks west
    assertEquals(new Location(this.world, 8, 64, -4), this.spawned.get(0));
    assertEquals(new Location(this.world, 8, 64, -6), this.spawned.get(2));
  }

  @Test
  void refusesAnOriginWithoutAWorld() {
    final Mcv2Configuration configuration = this.configuration(BlockFace.SOUTH);
    final Mcv2Configuration unloaded = mock(Mcv2Configuration.class);
    when(unloaded.getOrigin()).thenReturn(new Location(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> new Mcv2Screen(unloaded).build());
    assertThrows(NullPointerException.class, () -> new Mcv2Screen(null));
    assertEquals(3, configuration.getColumns());
  }

  @Test
  void namesAMapThatNeedNotExist() {
    this.statics.close();
    final ItemStack copy = mock(ItemStack.class);
    try (
      MockedConstruction<net.minecraft.world.item.ItemStack> stacks = Mockito.mockConstruction(net.minecraft.world.item.ItemStack.class); // fqn: Minecraft's ItemStack beside the imported Bukkit one
      MockedStatic<CraftItemStack> crafts = Mockito.mockStatic(CraftItemStack.class)
    ) {
      crafts.when(() -> CraftItemStack.asBukkitCopy(ArgumentMatchers.any(net.minecraft.world.item.ItemStack.class))).thenReturn(copy); // fqn: Minecraft's ItemStack beside the imported Bukkit one
      assertSame(copy, Mcv2Screen.pageItem(2_000_000_123));
      verify(stacks.constructed().getFirst()).set(DataComponents.MAP_ID, new MapId(2_000_000_123));
    }
    this.statics = Mockito.mockStatic(Mcv2Screen.class, Mockito.CALLS_REAL_METHODS);
    assertEquals(0, Mcv2Screen.slot(0, 0, 4));
    assertEquals(3, Mcv2Screen.slot(1, 2, 4));
    assertEquals(0, Mcv2Screen.slot(2, 2, 4));
  }

  @Test
  void showsTheFramesTheirTeamAndTheAnchorsToAPlayer() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    screen.show(this.player);
    for (final ItemFrame frame : this.frames) {
      verify(this.player).showEntity(this.server.getPlugin(), frame);
    }
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    assertEquals(2, packets.size());
    final ClientboundSetPlayerTeamPacket team = assertInstanceOf(ClientboundSetPlayerTeamPacket.class, packets.get(0));
    assertEquals(Mcv2Screen.TEAM, team.getName());
    assertEquals(Optional.of(TeamColor.GOLD), team.getParameters().orElseThrow().color());
    assertEquals(6, team.getPlayers().size());
    assertTrue(team.getPlayers().contains(this.frames.get(3).getUniqueId().toString()));
    final List<ClientboundMapItemDataPacket> anchors = MapPackets.unbundle(packets.get(1));
    assertEquals(6, anchors.size());
    MapPackets.assertMapPacket(anchors.get(0), screen.anchors().get(0));
    screen.hide(this.player);
    for (final ItemFrame frame : this.frames) {
      verify(this.player).hideEntity(this.server.getPlugin(), frame);
    }
    final ClientboundSetPlayerTeamPacket removal = assertInstanceOf(
      ClientboundSetPlayerTeamPacket.class,
      this.server.getSentPackets(VIEWER).get(2)
    );
    assertEquals(Mcv2Screen.TEAM, removal.getName());
    assertThrows(NullPointerException.class, () -> screen.show(null));
    assertThrows(NullPointerException.class, () -> screen.hide(null));
  }

  @Test
  void writesTheAnchorOfEveryMapInItsFirstRow() {
    final List<MapTilePatch> anchors = new Mcv2Screen(this.configuration(BlockFace.WEST)).anchors();
    assertEquals(6, anchors.size());
    final MapTilePatch patch = anchors.get(4);
    assertEquals(104, patch.getMapId());
    assertEquals(0, patch.getY());
    assertEquals(1, patch.getHeight());
    final byte[] expected = new byte[128];
    final int[] symbols = { 21, 3, 58, 44, 9, 37, 60, 17, 1, 1, 3, 2, 1, (1 + 1 + 3 + 2 + 1) & 63 };
    for (int i = 0; i < expected.length; i++) {
      expected[i] = (byte) ((i < symbols.length ? symbols[i] : 0) + 4);
    }
    assertArrayEquals(expected, patch.getColors());
  }

  @Test
  void removesTheFrames() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    screen.build();
    screen.remove();
    for (final ItemFrame frame : this.frames) {
      verify(frame).remove();
    }
    assertEquals(List.of(), screen.getFrames());
  }

  @Test
  void colorsTheTeam() {
    final Mcv2Screen screen = new Mcv2Screen(this.configuration(BlockFace.SOUTH));
    final PlayerTeam team = screen.team();
    assertEquals(Optional.of(TeamColor.GOLD), team.getColor());
    assertEquals(Mcv2Screen.TEAM, team.getName());
  }
}

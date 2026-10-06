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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests that the limit of a viewer's unsent bytes, which only keeps a slow viewer's backlog small, never keeps the
 * viewer from being shown the screen.
 */
final class Mcv2ChannelUnsentLimitTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000041");

  private FakeServer server;

  private CraftPlayer player;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.player = this.server.addPlayer(VIEWER);
    this.server.injectModule();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private static Mcv2Viewers viewers() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(viewers.isLoaded(VIEWER)).thenReturn(true);
    return viewers;
  }

  private static Mcv2Screen screen() {
    final Mcv2Screen screen = mock(Mcv2Screen.class);
    when(screen.anchors()).thenReturn(List.of(new MapTilePatch(100, 0, 0, 128, 1, new byte[128])));
    return screen;
  }

  private static Mcv2Configuration configuration(final int unsentLimit) {
    return Mcv2Configuration.builder()
      .viewers(List.of(VIEWER))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .unsentLimit(unsentLimit)
      .build();
  }

  @Test
  void showsTheScreenToAViewerWhoseConnectionRefusesTheLimit() {
    final Mcv2Viewers viewers = viewers();
    final Mcv2Screen screen = screen();
    final Mcv2Configuration configuration = configuration(4096);
    // epoll refuses the option of a connection that closed since the show was scheduled, as when the player leaves
    final ChannelException closed = new ChannelException(new ClosedChannelException());
    try (final MockedStatic<PacketUtils> packets = mockStatic(PacketUtils.class, CALLS_REAL_METHODS)) {
      packets.when(() -> PacketUtils.limitUnsent(any(), anyInt())).thenThrow(closed);
      final Mcv2Channel channel = new Mcv2Channel(configuration, viewers, screen);
      channel.update();
      this.server.runTasks();

      verify(screen).show(this.player);
      assertEquals(Set.of(), channel.update(), "the viewer receives frames, without the limit");
      assertEquals(Set.of(VIEWER), channel.getRecipients());
      packets.verify(() -> PacketUtils.limitUnsent(VIEWER, 4096));
    }
  }

  @Test
  void leavesTheConnectionAloneForAScreenWithoutALimit() {
    final Mcv2Screen screen = screen();
    try (final MockedStatic<PacketUtils> packets = mockStatic(PacketUtils.class, CALLS_REAL_METHODS)) {
      final Mcv2Channel channel = new Mcv2Channel(configuration(0), viewers(), screen);
      channel.update();
      this.server.runTasks();
      verify(screen).show(this.player);
      // a limit of 0 is none: the connection keeps the unsent bytes it would keep without MCV2
      packets.verify(() -> PacketUtils.limitUnsent(any(), anyInt()), never());
    }
  }
}

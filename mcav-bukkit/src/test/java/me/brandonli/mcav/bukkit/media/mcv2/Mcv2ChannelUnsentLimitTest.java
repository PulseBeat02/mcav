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

final class Mcv2ChannelUnsentLimitTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000041");

  private static final int WALL_MAP = 100;

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

  private Mcv2Viewers viewers() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(viewers.isLoaded(VIEWER)).thenReturn(true);
    return viewers;
  }

  private Mcv2Screen screen() {
    final Mcv2Screen screen = mock(Mcv2Screen.class);
    when(screen.getAnchors()).thenReturn(List.of(MapTilePatch.of(WALL_MAP, 0, 0, 128, 1, new byte[128])));
    return screen;
  }

  private Mcv2Configuration configuration(final int unsentLimit) {
    return Mcv2Configuration.builder()
      .viewers(List.of(VIEWER))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .unsentLimit(unsentLimit)
      .build();
  }

  @Test
  void shouldShowTheScreenToAViewerWhoseConnectionRefusesTheLimit() {
    final Mcv2Viewers viewers = this.viewers();
    final Mcv2Screen screen = this.screen();
    final Mcv2Configuration configuration = this.configuration(4096);
    // epoll refuses the option of a connection that closed since the show was scheduled, as when the player leaves
    final ChannelException closed = new ChannelException(new ClosedChannelException());
    try (final MockedStatic<PacketUtils> packets = mockStatic(PacketUtils.class, CALLS_REAL_METHODS)) {
      packets.when(() -> PacketUtils.limitUnsent(any(), anyInt())).thenThrow(closed);
      final Mcv2Channel channel = new Mcv2Channel(configuration, viewers, screen);
      channel.update();
      this.server.runTasks();

      verify(screen).show(this.player);

      final Set<UUID> updated = channel.update();
      assertEquals(Set.of(), updated, "the viewer receives frames, without the limit");

      final Set<UUID> expectedRecipients = Set.of(VIEWER);
      final Set<UUID> recipients = channel.getRecipients();
      assertEquals(expectedRecipients, recipients);

      packets.verify(() -> PacketUtils.limitUnsent(VIEWER, 4096));
    }
  }

  @Test
  void shouldLeaveTheConnectionAloneForAScreenWithoutALimit() {
    final Mcv2Screen screen = this.screen();
    try (final MockedStatic<PacketUtils> packets = mockStatic(PacketUtils.class, CALLS_REAL_METHODS)) {
      final Mcv2Channel channel = new Mcv2Channel(this.configuration(0), this.viewers(), screen);
      channel.update();
      this.server.runTasks();
      verify(screen).show(this.player);
      // a limit of 0 is none: the connection keeps the unsent bytes it would keep without MCV2
      packets.verify(() -> PacketUtils.limitUnsent(any(), anyInt()), never());
    }
  }
}

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that releasing an MCV2 screen clears every map of its wall for the viewers that decoded it, a wall of several
 * maps included.
 */
final class Mcv2ResultReleaseTest {

  private static final UUID WITH_PACK = UUID.fromString("00000000-0000-0000-0000-000000000061");

  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000062");

  private FakeServer server;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(WITH_PACK);
    this.server.addPlayer(WITHOUT);
    this.server.injectModule();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  @Test
  void clearsEveryMapOfAWallOfSeveralMapsForTheViewersWithThePack() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(viewers.isLoaded(WITH_PACK)).thenReturn(true);
    final Mcv2Screen screen = mock(Mcv2Screen.class);
    when(screen.anchors()).thenReturn(List.of());
    final DitherAlgorithm algorithm = mock(DitherAlgorithm.class);
    when(algorithm.ditherIntoBytes(any())).thenAnswer(invocation -> {
      final ImageBuffer image = invocation.getArgument(0);
      return new byte[image.getWidth() * image.getHeight()];
    });
    final Mcv2Configuration configuration = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(3)
      .rows(2)
      .video(64, 32)
      .pageMap(500)
      .settings(Settings.FAST)
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      configuration,
      new Mcv2Channel(configuration, viewers, screen),
      algorithm,
      System::nanoTime,
      Runnable::run
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    final OriginalVideoMetadata metadata = mock(OriginalVideoMetadata.class);
    result.applyFilter(frame, metadata);
    this.server.runTasks();
    result.applyFilter(frame, metadata);
    assertEquals(Set.of(WITH_PACK), result.getChannel().getRecipients());

    result.release();

    final List<Packet<?>> sent = this.server.getSentPackets(WITH_PACK);
    final List<ClientboundMapItemDataPacket> cleared = MapPackets.unbundle(sent.getLast());
    assertEquals(
      List.of(100, 101, 102, 103, 104, 105),
      cleared
        .stream()
        .map(packet -> packet.mapId().id())
        .toList(),
      "every map of the wall is cleared, not the first only"
    );
  }
}

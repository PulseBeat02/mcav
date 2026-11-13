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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
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
import org.mockito.invocation.InvocationOnMock;

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
  void shouldClearEveryMapOfAWallOfSeveralMapsForEveryViewer() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(viewers.isLoaded(WITH_PACK)).thenReturn(true);
    final Mcv2Screen screen = mock(Mcv2Screen.class);
    when(screen.getAnchors()).thenReturn(List.of());
    final DitherAlgorithm algorithm = mock(DitherAlgorithm.class);
    when(algorithm.ditherIntoBytes(any())).thenAnswer(this::createEmptyPixels);
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
      .maximumFrameRate(0)
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
    final Set<UUID> expectedRecipients = Set.of(WITH_PACK);
    final Set<UUID> recipients = result.getChannel().getRecipients();
    assertEquals(expectedRecipients, recipients);

    final List<Packet<?>> ditheredBeforeRelease = this.server.getSentPackets(WITHOUT);
    final int ditheredCountBeforeRelease = ditheredBeforeRelease.size();

    result.release();

    final List<Packet<?>> sent = this.server.getSentPackets(WITH_PACK);
    final List<ClientboundMapItemDataPacket> cleared = MapPackets.unbundle(sent.getLast());
    final List<Integer> expectedMapIds = List.of(100, 101, 102, 103, 104, 105);
    final List<Integer> clearedMapIds = cleared
      .stream()
      .map(packet -> packet.mapId().id())
      .toList();
    assertEquals(expectedMapIds, clearedMapIds, "every map of the wall is cleared, not the first only");

    final List<Packet<?>> dithered = this.server.getSentPackets(WITHOUT);
    final int expectedDitheredCount = ditheredCountBeforeRelease + 1;
    final int ditheredCount = dithered.size();
    assertEquals(expectedDitheredCount, ditheredCount, "one bundle clears the wall of the viewer without the pack");

    final List<ClientboundMapItemDataPacket> ditheredCleared = MapPackets.unbundle(dithered.getLast());
    final List<Integer> ditheredClearedMapIds = ditheredCleared
      .stream()
      .map(packet -> packet.mapId().id())
      .toList();
    assertEquals(expectedMapIds, ditheredClearedMapIds, "the fallback clears every map of the wall too");
  }

  private byte[] createEmptyPixels(final InvocationOnMock invocation) {
    final ImageBuffer image = invocation.getArgument(0);
    return new byte[image.getWidth() * image.getHeight()];
  }
}

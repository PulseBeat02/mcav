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
package me.brandonli.mcav.bukkit.media.result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link MapResult}.
 */
final class MapResultTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private FakeServer server;
  private DitherAlgorithm algorithm;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(VIEWER);
    this.server.injectModule();

    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(invocation -> {
      final ImageBuffer frame = invocation.getArgument(0);
      return MapPackets.pattern(frame);
    });
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private static MapConfiguration createConfiguration(final boolean shouldResize) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(5);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    configureResize(builder, shouldResize);
    return builder.build();
  }

  private static void configureResize(final MapConfiguration.Builder<?> builder, final boolean shouldResize) {
    builder.resize(shouldResize);
  }

  /**
   * Creates a configuration of a grid of maps, whose first map has the id 5.
   *
   * @param columns the number of maps per row
   * @param rows    the number of rows of maps
   * @return the configuration
   */
  private static MapConfiguration createGridConfiguration(final int columns, final int rows) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(5);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    configureResize(builder, false);
    return builder.build();
  }

  private ClientboundMapItemDataPacket onlyMapPacket(final int packetIndex) {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> packet = packets.get(packetIndex);
    final List<ClientboundMapItemDataPacket> mapPackets = MapPackets.unbundle(packet);
    final int mapPacketCount = mapPackets.size();

    assertEquals(1, mapPacketCount);

    return mapPackets.getFirst();
  }

  @Test
  void sendsTheCompletePictureInEveryFrame() {
    final MapConfiguration configuration = createConfiguration(false);
    final MapResult result = new MapResult(configuration);
    result.start();
    final ImageBuffer image = Images.solid(128, 128, 0xFF102030);
    result.process(image, this.algorithm);
    result.process(image, this.algorithm);
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final int packetCount = packets.size();
    final ClientboundMapItemDataPacket second = this.onlyMapPacket(1);
    final byte[] colors = MapPackets.pattern(128 * 128, 4);

    assertEquals(2, packetCount, "nothing is sent on start, and unchanged frames are sent again");
    MapPackets.assertMapPacket(second, 5, 0, 0, 128, 128, colors);
  }

  @Test
  void resizesFramesWhenConfigured() {
    final MapConfiguration configuration = createConfiguration(true);
    final MapResult result = new MapResult(configuration);
    final ImageBuffer image = Images.solid(128, 128, 0xFF102030);
    result.process(image, this.algorithm);
    final ClientboundMapItemDataPacket packet = this.onlyMapPacket(0);
    final byte[] picture = MapPackets.pattern(64 * 64, 4);
    final byte[] colors = new byte[128 * 128];
    for (int row = 0; row < 64; row++) {
      System.arraycopy(picture, row * 64, colors, (row + 32) * 128 + 32, 64);
    }
    final int width = image.getWidth();

    MapPackets.assertMapPacket(packet, 5, 0, 0, 128, 128, colors);
    assertEquals(64, width);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 3 })
  void shrinkingAFrameClearsTheEntirePreviousPicture(final int columns) {
    final MapResult result = new MapResult(createGridConfiguration(columns, 1));
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(invocation -> {
      final ImageBuffer frame = invocation.getArgument(0);
      final byte[] pixels = new byte[frame.getWidth() * frame.getHeight()];
      Arrays.fill(pixels, frame.getWidth() == 64 ? (byte) 9 : (byte) 7);
      return pixels;
    });
    try (
      final ImageBuffer large = Images.solid(columns * 128, 128, 0xFF102030);
      final ImageBuffer small = Images.solid(64, 64, 0xFF304050)
    ) {
      result.process(large, this.algorithm);
      result.process(small, this.algorithm);
      result.process(small, this.algorithm);
    }
    final int canvasWidth = columns * 128;
    final byte[] actual = new byte[canvasWidth * 128];
    for (final Packet<?> bundle : this.server.getSentPackets(VIEWER)) {
      for (final ClientboundMapItemDataPacket packet : MapPackets.unbundle(bundle)) {
        final int column = packet.mapId().id() - 5;
        assertTrue(column >= 0 && column < columns);
        final MapItemSavedData.MapPatch update = packet.colorPatch().orElseThrow();
        for (int row = 0; row < update.height(); row++) {
          final int offset = (update.startY() + row) * canvasWidth + column * 128 + update.startX();
          System.arraycopy(update.mapColors(), row * update.width(), actual, offset, update.width());
        }
      }
    }
    final byte[] expected = new byte[actual.length];
    for (int row = 32; row < 96; row++) {
      final int offset = row * canvasWidth + (canvasWidth - 64) / 2;
      Arrays.fill(expected, offset, offset + 64, (byte) 9);
    }
    assertArrayEquals(expected, actual, "the current picture is surrounded by transparent pixels, including uncovered maps");
  }

  @Test
  void clearsTheMapsWhenReleased() {
    final MapConfiguration configuration = createConfiguration(false);
    final MapResult result = new MapResult(configuration);
    result.release();
    final ClientboundMapItemDataPacket packet = this.onlyMapPacket(0);
    final byte[] transparent = new byte[128 * 128];

    MapPackets.assertMapPacket(packet, 5, 0, 0, 128, 128, transparent);
  }

  @Test
  void clearsEveryMapOfTheGridWhenReleased() {
    final MapConfiguration configuration = createGridConfiguration(2, 2);
    final MapResult result = new MapResult(configuration);

    result.release();

    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> bundle = packets.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = MapPackets.unbundle(bundle);
    final int mapPacketCount = mapPackets.size();
    final ClientboundMapItemDataPacket last = mapPackets.get(3);
    final byte[] transparent = new byte[128 * 128];

    assertEquals(4, mapPacketCount, "every map of the 2x2 grid is cleared");
    MapPackets.assertMapPacket(last, 8, 0, 0, 128, 128, transparent);
  }

  @Test
  void rejectsMissingArguments() {
    final MapConfiguration configuration = createConfiguration(false);
    final MapResult result = new MapResult(configuration);

    assertThrows(NullPointerException.class, () -> new MapResult(null));
    assertThrows(NullPointerException.class, () -> result.process(null, this.algorithm));

    try (final ImageBuffer image = Images.solid(1, 1, 0)) {
      assertThrows(NullPointerException.class, () -> result.process(image, null));
    }

    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final boolean nothingSent = packets.isEmpty();

    assertTrue(nothingSent);
  }
}

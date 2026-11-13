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
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;

final class MapResultTest {

  private static final int RESOLUTION = 64;
  private static final int CENTERED_FRAME_OFFSET = 32;

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final int MAP_SIZE = 128;

  private static final int FRAME_COLOR = 0xFF102030;

  private FakeServer server;
  private DitherAlgorithm algorithm;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(VIEWER);
    this.server.injectModule();

    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(this::createPatternedPixels);
  }

  private byte[] createPatternedPixels(final InvocationOnMock invocation) {
    final ImageBuffer frame = invocation.getArgument(0);
    return MapPackets.pattern(frame);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private MapConfiguration createConfiguration(final Sizing sizing) {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(5);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(RESOLUTION);
    builder.mapHeightResolution(RESOLUTION);
    builder.resize(sizing == Sizing.RESIZED);
    return builder.build();
  }

  private MapConfiguration createGridConfiguration(final int columns, final int rows) {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(5);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(RESOLUTION);
    builder.mapHeightResolution(RESOLUTION);
    builder.resize(false);
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
  void shouldSendTheCompletePictureInEveryFrame() {
    final MapConfiguration configuration = this.createConfiguration(Sizing.ORIGINAL);
    final MapResult result = new MapResult(configuration);
    result.start();
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, FRAME_COLOR);
    result.process(image, this.algorithm);
    result.process(image, this.algorithm);
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final int packetCount = packets.size();
    final ClientboundMapItemDataPacket second = this.onlyMapPacket(1);
    final byte[] colors = MapPackets.pattern(MAP_SIZE * MAP_SIZE, 4);

    assertEquals(2, packetCount, "nothing is sent on start, and unchanged frames are sent again");

    MapPackets.assertMapPacket(second, 5, 0, 0, MAP_SIZE, MAP_SIZE, colors);
  }

  @Test
  void shouldResizeFramesWhenConfigured() {
    final MapConfiguration configuration = this.createConfiguration(Sizing.RESIZED);
    final MapResult result = new MapResult(configuration);
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, FRAME_COLOR);
    result.process(image, this.algorithm);
    final ClientboundMapItemDataPacket packet = this.onlyMapPacket(0);
    final byte[] picture = MapPackets.pattern(RESOLUTION * RESOLUTION, 4);
    final byte[] colors = new byte[MAP_SIZE * MAP_SIZE];
    for (int row = 0; row < RESOLUTION; row++) {
      System.arraycopy(picture, row * RESOLUTION, colors, (row + CENTERED_FRAME_OFFSET) * MAP_SIZE + CENTERED_FRAME_OFFSET, RESOLUTION);
    }

    final int width = image.getWidth();

    MapPackets.assertMapPacket(packet, 5, 0, 0, MAP_SIZE, MAP_SIZE, colors);
    assertEquals(RESOLUTION, width);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 3 })
  void shouldClearTheEntirePreviousPictureWhenShrinkingAFrame(final int columns) {
    final MapResult result = new MapResult(this.createGridConfiguration(columns, 1));
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(this::createSizedPixels);
    try (
      final ImageBuffer large = Images.solid(columns * MAP_SIZE, MAP_SIZE, FRAME_COLOR);
      final ImageBuffer small = Images.solid(RESOLUTION, RESOLUTION, 0xFF304050)
    ) {
      result.process(large, this.algorithm);
      result.process(small, this.algorithm);
      result.process(small, this.algorithm);
    }

    final int canvasWidth = columns * MAP_SIZE;
    final byte[] actual = new byte[canvasWidth * MAP_SIZE];
    for (final Packet<?> bundle : this.server.getSentPackets(VIEWER)) {
      for (final ClientboundMapItemDataPacket packet : MapPackets.unbundle(bundle)) {
        this.copyPacket(actual, packet, columns);
      }
    }

    final byte[] expected = new byte[actual.length];
    for (int row = CENTERED_FRAME_OFFSET; row < 96; row++) {
      final int offset = row * canvasWidth + (canvasWidth - RESOLUTION) / 2;
      Arrays.fill(expected, offset, offset + RESOLUTION, (byte) 9);
    }

    assertArrayEquals(expected, actual, "the current picture is surrounded by transparent pixels, including uncovered maps");
  }

  private byte[] createSizedPixels(final InvocationOnMock invocation) {
    final ImageBuffer frame = invocation.getArgument(0);
    final byte[] pixels = new byte[frame.getWidth() * frame.getHeight()];
    Arrays.fill(pixels, frame.getWidth() == RESOLUTION ? (byte) 9 : (byte) 7);

    return pixels;
  }

  private void copyPacket(final byte[] actual, final ClientboundMapItemDataPacket packet, final int columns) {
    final int canvasWidth = columns * MAP_SIZE;
    final int column = packet.mapId().id() - 5;
    final boolean insideTheGrid = column >= 0 && column < columns;
    assertTrue(insideTheGrid);

    final MapItemSavedData.MapPatch update = packet.colorPatch().orElseThrow();
    for (int row = 0; row < update.height(); row++) {
      final int offset = (update.startY() + row) * canvasWidth + column * MAP_SIZE + update.startX();
      System.arraycopy(update.mapColors(), row * update.width(), actual, offset, update.width());
    }
  }

  @Test
  void shouldClearTheMapsWhenReleased() {
    final MapConfiguration configuration = this.createConfiguration(Sizing.ORIGINAL);
    final MapResult result = new MapResult(configuration);
    result.release();
    final ClientboundMapItemDataPacket packet = this.onlyMapPacket(0);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];

    MapPackets.assertMapPacket(packet, 5, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
  }

  @Test
  void shouldClearEveryMapOfTheGridWhenReleased() {
    final MapConfiguration configuration = this.createGridConfiguration(2, 2);
    final MapResult result = new MapResult(configuration);

    result.release();

    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> bundle = packets.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = MapPackets.unbundle(bundle);
    final int mapPacketCount = mapPackets.size();
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];

    assertEquals(4, mapPacketCount, "every map of the 2x2 grid is cleared");

    MapPackets.assertMapPacket(mapPackets.get(0), 5, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    MapPackets.assertMapPacket(mapPackets.get(1), 6, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    MapPackets.assertMapPacket(mapPackets.get(2), 7, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    MapPackets.assertMapPacket(mapPackets.get(3), 8, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 5 })
  void shouldRejectDitheredPixelsWhoseLengthDoesNotMatchTheImage(final int length) {
    final MapResult result = new MapResult(this.createConfiguration(Sizing.ORIGINAL));
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenReturn(new byte[length]);
    try (final ImageBuffer image = Images.solid(2, 2, FRAME_COLOR)) {
      final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> result.process(image, this.algorithm));
      final @Nullable String message = failure.getMessage();
      final String expectedMessage = "Image length %s does not match the layout's 4 pixels!".formatted(length);
      assertEquals(expectedMessage, message);
    }

    final List<Packet<?>> sent = this.server.getSentPackets(VIEWER);
    assertEquals(List.of(), sent, "invalid converted pixels must not reach the viewer");
  }

  @Test
  void shouldRejectMissingArguments() {
    final MapConfiguration configuration = this.createConfiguration(Sizing.ORIGINAL);
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

  private enum Sizing {
    RESIZED,
    ORIGINAL,
  }
}

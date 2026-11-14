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
package me.brandonli.mcav.bukkit.media.map;

import static me.brandonli.mcav.bukkit.testing.MapPackets.assertMapPacket;
import static me.brandonli.mcav.bukkit.testing.MapPackets.unbundle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MapPacketFactoryTest {

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private static final int BUNDLE_PACKET_LIMIT = 4096;

  private static final int MAP_SIZE = 128;

  private FakeServer server;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(FIRST);
    this.server.addPlayer(SECOND);
    this.server.injectModule();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  @Test
  void shouldSendThePatchesOfOneCallAsOneBundleToEveryOnlineViewer() {
    final MapTilePatch first = MapTilePatch.of(3, 1, 2, 2, 1, new byte[] { 5, 6 });
    final MapTilePatch second = MapTilePatch.of(4, 0, 0, 1, 1, new byte[] { 7 });
    final List<MapTilePatch> patches = List.of(first, second);
    final List<UUID> viewers = List.of(FIRST, SECOND, OFFLINE);
    MapPacketFactory.send(viewers, patches);
    final List<Packet<?>> firstPackets = this.server.getSentPackets(FIRST);
    final List<Packet<?>> secondPackets = this.server.getSentPackets(SECOND);
    final int firstCount = firstPackets.size();
    final int secondCount = secondPackets.size();
    final Packet<?> bundle = firstPackets.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
    final int mapPacketCount = mapPackets.size();
    final ClientboundMapItemDataPacket firstMapPacket = mapPackets.get(0);
    final ClientboundMapItemDataPacket secondMapPacket = mapPackets.get(1);

    assertEquals(1, firstCount);
    assertEquals(1, secondCount);
    assertEquals(2, mapPacketCount);
    assertMapPacket(firstMapPacket, first);
    assertMapPacket(secondMapPacket, second);
  }

  @Test
  void shouldSplitOnlyWhenUpdatesExceedThe4096PacketProtocolLimit() {
    final List<MapTilePatch> patches = new ArrayList<>();
    for (int mapId = 0; mapId < 4097; mapId++) {
      final MapTilePatch patch = MapTilePatch.of(mapId, 0, 0, 1, 1, new byte[] { 1 });
      patches.add(patch);
    }

    final List<UUID> viewers = List.of(FIRST);
    MapPacketFactory.send(viewers, patches);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final int bundleCount = packets.size();
    final Packet<?> firstBundle = packets.get(0);
    final Packet<?> secondBundle = packets.get(1);
    final List<ClientboundMapItemDataPacket> firstPackets = unbundle(firstBundle);
    final List<ClientboundMapItemDataPacket> secondPackets = unbundle(secondBundle);
    final int firstSize = firstPackets.size();
    final int secondSize = secondPackets.size();
    final ClientboundMapItemDataPacket lastPacket = secondPackets.getFirst();
    final MapTilePatch lastPatch = patches.getLast();

    assertEquals(2, bundleCount);
    assertEquals(BUNDLE_PACKET_LIMIT, firstSize);
    assertEquals(1, secondSize);

    for (int index = 0; index < firstSize; index++) {
      final ClientboundMapItemDataPacket packet = firstPackets.get(index);
      final MapTilePatch expected = patches.get(index);
      assertMapPacket(packet, expected);
    }

    assertMapPacket(lastPacket, lastPatch);
  }

  @Test
  void shouldSendOneBundleWhenThePatchCountFillsItExactly() {
    final List<MapTilePatch> patches = new ArrayList<>();
    for (int mapId = 0; mapId < BUNDLE_PACKET_LIMIT; mapId++) {
      final MapTilePatch patch = MapTilePatch.of(mapId, 0, 0, 1, 1, new byte[] { 1 });
      patches.add(patch);
    }

    final List<UUID> viewers = List.of(FIRST);
    MapPacketFactory.send(viewers, patches);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final int bundleCount = packets.size();
    final Packet<?> bundle = packets.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
    final int mapPacketCount = mapPackets.size();

    assertEquals(1, bundleCount, "a full bundle is not followed by an empty one");
    assertEquals(BUNDLE_PACKET_LIMIT, mapPacketCount);
  }

  @Test
  void shouldSendNothingWithoutPatchesOrViewers() {
    final MapTilePatch patch = MapTilePatch.of(0, 0, 0, 1, 1, new byte[] { 1 });
    final List<MapTilePatch> patches = List.of(patch);
    final List<UUID> viewers = List.of(FIRST);
    final List<MapTilePatch> noPatches = List.of();
    final List<UUID> noViewers = List.of();

    MapPacketFactory.send(viewers, noPatches);
    MapPacketFactory.send(noViewers, patches);

    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final boolean nothingSent = packets.isEmpty();

    assertTrue(nothingSent);
  }

  @Test
  void shouldClearMapsWithTheTransparentColor() {
    final List<UUID> viewers = List.of(FIRST);
    MapPacketFactory.clear(viewers, 4, 2);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final Packet<?> bundle = packets.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];
    final MapTilePatch expectedFirst = MapTilePatch.of(4, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    final MapTilePatch expectedSecond = MapTilePatch.of(5, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    final ClientboundMapItemDataPacket first = mapPackets.get(0);
    final ClientboundMapItemDataPacket second = mapPackets.get(1);
    final int mapPacketCount = mapPackets.size();

    assertEquals(2, mapPacketCount);
    assertMapPacket(first, expectedFirst);
    assertMapPacket(second, expectedSecond);
  }

  @Test
  void shouldSendNothingWhenClearingNoMaps() {
    final List<UUID> viewers = List.of(FIRST);
    MapPacketFactory.clear(viewers, 4, 0);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final boolean nothingSent = packets.isEmpty();

    assertTrue(nothingSent);
  }

  @Test
  void shouldClearTheExtremeMapIdsAndRejectOverflowBeforeSending() {
    final List<UUID> viewers = List.of(FIRST);
    final IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class, () ->
      MapPacketFactory.clear(viewers, Integer.MAX_VALUE, 2)
    );
    final String overflowMessage = overflow.getMessage();
    assertEquals("Map ids 2147483647 to 2147483648 exceed the largest map id!", overflowMessage);

    final IllegalArgumentException negativeId = assertThrows(IllegalArgumentException.class, () -> MapPacketFactory.clear(viewers, -1, 0));
    final String negativeIdMessage = negativeId.getMessage();
    assertEquals("Map id -1 must not be negative!", negativeIdMessage);

    final IllegalArgumentException negativeCount = assertThrows(IllegalArgumentException.class, () ->
      MapPacketFactory.clear(viewers, 0, -1)
    );
    final String negativeCountMessage = negativeCount.getMessage();
    assertEquals("Map count -1 must not be negative!", negativeCountMessage);

    final List<Packet<?>> before = this.server.getSentPackets(FIRST);
    final boolean nothingSentBefore = before.isEmpty();
    assertTrue(nothingSentBefore);

    MapPacketFactory.clear(viewers, 0, 1);
    MapPacketFactory.clear(viewers, Integer.MAX_VALUE, 1);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final int packetCount = packets.size();
    assertEquals(2, packetCount);

    final List<ClientboundMapItemDataPacket> first = unbundle(packets.getFirst());
    final List<ClientboundMapItemDataPacket> last = unbundle(packets.getLast());
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];
    final MapTilePatch firstExpected = MapTilePatch.of(0, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    final MapTilePatch lastExpected = MapTilePatch.of(Integer.MAX_VALUE, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    assertMapPacket(first.getFirst(), firstExpected);
    assertMapPacket(last.getFirst(), lastExpected);
  }

  @Test
  void shouldRejectInvalidArguments() {
    final List<UUID> viewers = List.of(FIRST);
    final List<MapTilePatch> patches = List.of();

    assertThrows(NullPointerException.class, () -> MapPacketFactory.send(null, patches));
    assertThrows(NullPointerException.class, () -> MapPacketFactory.send(viewers, null));
    assertThrows(NullPointerException.class, () -> MapPacketFactory.clear(null, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> MapPacketFactory.clear(viewers, -1, 1));
    assertThrows(NullPointerException.class, () -> MapPacketFactory.Bundle.of(null, 0));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(MapPacketFactory.class);
  }

  @Test
  void shouldCompareBundlesByEveryPart() {
    final ClientboundBundlePacket packet = new ClientboundBundlePacket(List.of());
    final ClientboundBundlePacket other = new ClientboundBundlePacket(List.of());
    final MapPacketFactory.Bundle bundle = MapPacketFactory.Bundle.of(packet, 1);
    EqualityAssertions.assertEqualityContract(
      bundle,
      MapPacketFactory.Bundle.of(packet, 1),
      MapPacketFactory.Bundle.of(other, 1),
      MapPacketFactory.Bundle.of(packet, 2)
    );
  }

  @Test
  void shouldHashBundlesByEveryPart() {
    final ClientboundBundlePacket packet = new ClientboundBundlePacket(List.of());
    final MapPacketFactory.Bundle bundle = MapPacketFactory.Bundle.of(packet, 1);

    final int hash = bundle.hashCode();
    final int expected = Objects.hash(packet, 1L);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeBundlesWithEveryPart() {
    final ClientboundBundlePacket packet = new ClientboundBundlePacket(List.of());
    final MapPacketFactory.Bundle bundle = MapPacketFactory.Bundle.of(packet, 1);

    final String description = bundle.toString();
    final String expected = "Bundle{packet=%s, bytes=1}".formatted(packet);
    assertEquals(expected, description);
  }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Properties of {@link MapPacketFactory} for every number of patches, not only the counts around the bundle limit the
 * examples use.
 */
final class MapPacketFactoryPropertyTest {

  private static final String SEED = "20260925";
  private static final int BUNDLE_LIMIT = 4096;
  private static final int MAP_PIXELS = MapLayout.MAP_SIZE * MapLayout.MAP_SIZE;
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private FakeServer server;

  @BeforeProperty
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(VIEWER);
    this.server.injectModule();
  }

  @AfterProperty
  void stopServer() {
    this.server.close();
  }

  @Provide
  Arbitrary<Integer> patchCounts() {
    final IntegerArbitrary integers = Arbitraries.integers();
    // a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones configured from it
    final Arbitrary<Integer> anyCount = integers.between(0, 3 * BUNDLE_LIMIT + 5);
    final Arbitrary<Integer> aroundLimits = Arbitraries.of(
      0,
      1,
      BUNDLE_LIMIT - 1,
      BUNDLE_LIMIT,
      BUNDLE_LIMIT + 1,
      2 * BUNDLE_LIMIT,
      2 * BUNDLE_LIMIT + 1
    );
    return Arbitraries.oneOf(anyCount, aroundLimits);
  }

  /**
   * Gets the packets the viewer received since the given number of packets.
   */
  private List<Packet<?>> packetsSince(final int before) {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final int after = packets.size();
    final List<Packet<?>> sent = packets.subList(before, after);
    return new ArrayList<>(sent);
  }

  private int packetCount() {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    return packets.size();
  }

  @Property(seed = SEED, tries = 60)
  void sendsEveryPatchInOrderInBundlesOfAtMostTheProtocolLimit(@ForAll("patchCounts") final int patchCount) {
    final List<MapTilePatch> patches = new ArrayList<>(patchCount);
    for (int index = 0; index < patchCount; index++) {
      final byte color = (byte) index;
      final MapTilePatch patch = new MapTilePatch(index, index % 128, 0, 1, 1, new byte[] { color });
      patches.add(patch);
    }
    final List<UUID> viewers = List.of(VIEWER, OFFLINE);
    final int before = this.packetCount();

    MapPacketFactory.send(viewers, patches);

    final List<Packet<?>> bundles = this.packetsSince(before);
    final int expectedBundles = (patchCount + BUNDLE_LIMIT - 1) / BUNDLE_LIMIT;
    final int bundleCount = bundles.size();
    assertEquals(expectedBundles, bundleCount, "one bundle per started block of 4096 patches");
    int next = 0;
    for (final Packet<?> bundle : bundles) {
      final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
      final int size = mapPackets.size();
      assertTrue(size > 0 && size <= BUNDLE_LIMIT, () -> "a bundle of " + size + " packets");
      for (final ClientboundMapItemDataPacket mapPacket : mapPackets) {
        final MapTilePatch expected = patches.get(next);
        assertMapPacket(mapPacket, expected);
        next++;
      }
    }
    assertEquals(patchCount, next, "every patch was sent exactly once");
  }

  @Property(seed = SEED, tries = 30)
  void clearingFillsEveryMapOfTheRangeWithTransparency(
    @ForAll @IntRange(min = 0, max = 1 << 30) final int startMapId,
    @ForAll("patchCounts") final int mapCount
  ) {
    final List<UUID> viewers = List.of(VIEWER);
    final int before = this.packetCount();
    final byte[] transparent = new byte[MAP_PIXELS];

    MapPacketFactory.clear(viewers, startMapId, mapCount);

    final List<Packet<?>> bundles = this.packetsSince(before);
    int offset = 0;
    for (final Packet<?> bundle : bundles) {
      final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
      for (final ClientboundMapItemDataPacket mapPacket : mapPackets) {
        assertMapPacket(mapPacket, startMapId + offset, 0, 0, MapLayout.MAP_SIZE, MapLayout.MAP_SIZE, transparent);
        offset++;
      }
    }
    assertEquals(mapCount, offset, "every map of the range is cleared once");
  }

  /**
   * The largest wall the sandbox builds has 64 by 64 maps. Clearing it happens when a video is released, without the
   * byte budget of playback, so the live pass measured it as a burst: this pins that any clear of up to 4096 maps
   * reaches a client as one bundle, applied in one tick rather than split, and that its size is 16400 bytes a map.
   */
  @Property(seed = SEED, tries = 30)
  void clearingAWallOfUpTo4096MapsIsOneBundle(@ForAll @IntRange(min = 1, max = BUNDLE_LIMIT) final int mapCount) {
    final List<UUID> viewers = List.of(VIEWER);
    final int before = this.packetCount();

    MapPacketFactory.clear(viewers, 0, mapCount);

    final List<Packet<?>> bundles = this.packetsSince(before);
    final int bundleCount = bundles.size();
    assertEquals(1, bundleCount, "a clear of up to 4096 maps is one bundle");
    final Packet<?> bundle = bundles.getFirst();
    final List<ClientboundMapItemDataPacket> mapPackets = unbundle(bundle);
    long colorBytes = 0;
    for (final ClientboundMapItemDataPacket mapPacket : mapPackets) {
      final Optional<MapItemSavedData.MapPatch> colorPatch = mapPacket.colorPatch();
      final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
      final byte[] mapColors = patch.mapColors();
      colorBytes += mapColors.length;
    }
    final long expectedBytes = (long) mapCount * MAP_PIXELS;
    assertEquals(expectedBytes, colorBytes, "a clear sends one full map of colors per map");
  }
}

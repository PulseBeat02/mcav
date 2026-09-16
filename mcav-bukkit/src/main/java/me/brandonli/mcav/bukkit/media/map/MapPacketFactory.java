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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Converts map patches into map data packets and sends them to players.
 *
 * <p>The patches of one call are grouped into bundle packets. The client applies all packets of a bundle in the
 * same tick, so viewers never see a frame where only some of the maps have been updated. A bundle may contain at
 * most 4096 packets, and much smaller bundles are used here to keep the latency of each bundle low. Every method
 * may be called from any thread.
 */
public final class MapPacketFactory {

  private static final int MAX_PACKETS_PER_BUNDLE = 512;
  private static final byte DEFAULT_SCALE = 0;

  private MapPacketFactory() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Sends the patches to the viewers. Viewers that are offline are skipped.
   *
   * @param viewers the UUIDs of the players to send the patches to
   * @param patches the patches to send
   */
  public static void send(final Collection<UUID> viewers, final List<MapTilePatch> patches) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    Preconditions.checkNotNull(patches, "Patches must not be null");
    if (patches.isEmpty() || viewers.isEmpty()) {
      return;
    }

    final int patchCount = patches.size();
    final List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(patchCount);
    for (final MapTilePatch patch : patches) {
      final ClientboundMapItemDataPacket packet = createPacket(patch);
      packets.add(packet);
    }

    for (int from = 0; from < patchCount; from += MAX_PACKETS_PER_BUNDLE) {
      final int to = Math.min(from + MAX_PACKETS_PER_BUNDLE, patchCount);
      final List<Packet<? super ClientGamePacketListener>> chunk = packets.subList(from, to);
      final List<Packet<? super ClientGamePacketListener>> chunkCopy = List.copyOf(chunk);
      final ClientboundBundlePacket bundle = new ClientboundBundlePacket(chunkCopy);
      PacketUtils.sendPackets(viewers, bundle);
    }
  }

  /**
   * Clears maps by filling them completely with the transparent color.
   *
   * @param viewers    the UUIDs of the players to clear the maps for
   * @param startMapId the id of the first map
   * @param count      the number of maps with consecutive ids to clear
   */
  public static void clear(final Collection<UUID> viewers, final int startMapId, final int count) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    Preconditions.checkArgument(startMapId >= 0, "Map id must be non-negative");
    Preconditions.checkArgument(count >= 0, "Map count must be non-negative");

    final int size = MapLayout.MAP_SIZE;
    final byte[] transparent = new byte[size * size];
    final List<MapTilePatch> patches = new ArrayList<>(count);
    for (int offset = 0; offset < count; offset++) {
      final int mapId = startMapId + offset;
      final MapTilePatch patch = new MapTilePatch(mapId, 0, 0, size, size, transparent);
      patches.add(patch);
    }
    send(viewers, patches);
  }

  private static ClientboundMapItemDataPacket createPacket(final MapTilePatch patch) {
    final int mapId = patch.getMapId();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();

    final MapId id = new MapId(mapId);
    final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(x, y, width, height, colors);
    final Optional<List<MapDecoration>> decorations = Optional.empty();
    final Optional<MapItemSavedData.MapPatch> colorPatch = Optional.of(mapPatch);
    return new ClientboundMapItemDataPacket(id, DEFAULT_SCALE, false, decorations, colorPatch);
  }
}

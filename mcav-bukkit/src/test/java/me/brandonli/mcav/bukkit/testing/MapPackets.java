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
package me.brandonli.mcav.bukkit.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.image.ImageBuffer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class MapPackets {

  private static final int IMAGE_PATTERN_OFFSET = 4;
  private static final int PATTERN_PERIOD = 100;

  private MapPackets() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static List<ClientboundMapItemDataPacket> unbundle(final Packet<?> packet) {
    final ClientboundBundlePacket bundle = assertInstanceOf(ClientboundBundlePacket.class, packet);
    final Iterable<Packet<? super ClientGamePacketListener>> subPackets = bundle.subPackets();
    final List<ClientboundMapItemDataPacket> mapPackets = new ArrayList<>();
    for (final Packet<? super ClientGamePacketListener> subPacket : subPackets) {
      final ClientboundMapItemDataPacket mapPacket = assertInstanceOf(ClientboundMapItemDataPacket.class, subPacket);
      mapPackets.add(mapPacket);
    }

    return mapPackets;
  }

  public static void assertMapPacket(
    final ClientboundMapItemDataPacket packet,
    final int mapId,
    final int left,
    final int top,
    final int width,
    final int height,
    final byte[] colors
  ) {
    final MapId id = packet.mapId();
    final int actualId = id.id();
    final byte scale = packet.scale();
    final boolean locked = packet.locked();
    final Optional<List<MapDecoration>> decorations = packet.decorations();
    final boolean hasDecorations = decorations.isPresent();
    final Optional<MapItemSavedData.MapPatch> colorPatch = packet.colorPatch();
    final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
    final int actualX = patch.startX();
    final int actualY = patch.startY();
    final int actualWidth = patch.width();
    final int actualHeight = patch.height();
    final byte[] actualColors = patch.mapColors();
    assertEquals(mapId, actualId, "map id");
    assertEquals(0, scale, "scale");
    assertFalse(locked, "locked");
    assertFalse(hasDecorations, "decorations");
    assertEquals(left, actualX, "x");
    assertEquals(top, actualY, "y");
    assertEquals(width, actualWidth, "width");
    assertEquals(height, actualHeight, "height");
    assertArrayEquals(colors, actualColors, "colors");
  }

  public static void assertMapPacket(final ClientboundMapItemDataPacket packet, final MapTilePatch patch) {
    final int mapId = patch.getMapId();
    final int left = patch.getX();
    final int top = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();
    assertMapPacket(packet, mapId, left, top, width, height, colors);
  }

  public static byte[] pattern(final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    return pattern(width * height, IMAGE_PATTERN_OFFSET);
  }

  public static byte[] pattern(final int length, final int offset) {
    final byte[] colors = new byte[length];
    for (int index = 0; index < length; index++) {
      colors[index] = (byte) (offset + (index % PATTERN_PERIOD));
    }

    return colors;
  }
}

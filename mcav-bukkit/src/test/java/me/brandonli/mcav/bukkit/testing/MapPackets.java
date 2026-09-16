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

/**
 * Assertions and helpers for map data packets.
 */
public final class MapPackets {

  private MapPackets() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asserts that the packet is a bundle of map data packets and returns them.
   *
   * @param packet the packet
   * @return the map data packets of the bundle, in order
   */
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

  /**
   * Asserts the content of a map data packet. The packet must update an unlocked map at the default scale without
   * decorations.
   *
   * @param packet the packet
   * @param mapId  the expected map id
   * @param x      the expected x coordinate of the patch
   * @param y      the expected y coordinate of the patch
   * @param width  the expected width of the patch
   * @param height the expected height of the patch
   * @param colors the expected colors of the patch
   */
  public static void assertMapPacket(
    final ClientboundMapItemDataPacket packet,
    final int mapId,
    final int x,
    final int y,
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
    assertEquals(x, actualX, "x");
    assertEquals(y, actualY, "y");
    assertEquals(width, actualWidth, "width");
    assertEquals(height, actualHeight, "height");
    assertArrayEquals(colors, actualColors, "colors");
  }

  /**
   * Asserts that a map data packet carries exactly the specified patch, see
   * {@link #assertMapPacket(ClientboundMapItemDataPacket, int, int, int, int, int, byte[])}.
   *
   * @param packet the packet
   * @param patch  the expected patch
   */
  public static void assertMapPacket(final ClientboundMapItemDataPacket packet, final MapTilePatch patch) {
    final int mapId = patch.getMapId();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();
    assertMapPacket(packet, mapId, x, y, width, height, colors);
  }

  /**
   * Creates the colors a scripted dither algorithm returns for an image: a repeating pattern of palette indices.
   *
   * @param image the image
   * @return one color per pixel
   */
  public static byte[] pattern(final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    return pattern(width * height, 4);
  }

  /**
   * Creates a repeating pattern of palette indices.
   *
   * @param length the number of pixels
   * @param offset the first palette index
   * @return the colors
   */
  public static byte[] pattern(final int length, final int offset) {
    final byte[] colors = new byte[length];
    for (int index = 0; index < length; index++) {
      colors[index] = (byte) (offset + (index % 100));
    }
    return colors;
  }
}

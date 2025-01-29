/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.utils.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Bukkit;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * The MapImage class provides a concrete implementation of the DisplayableImage interface.
 * It is responsible for displaying and managing image data on maps using a specific dither algorithm
 * and configuration settings. Instances of this class process static images and render them onto
 * map blocks for defined viewers.
 */
public class MapImage implements DisplayableImage {

  private final List<Integer> mapIds;
  private final MapConfiguration mapConfiguration;
  private final DitherAlgorithm algorithm;

  MapImage(final MapConfiguration mapConfiguration, final DitherAlgorithm algorithm) {
    this.mapIds = new ArrayList<>();
    this.mapConfiguration = mapConfiguration;
    this.algorithm = algorithm;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void displayImage(final StaticImage image) {
    this.release();
    final byte[] rgb = this.algorithm.ditherIntoBytes(image);
    final int mapBlockWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapBlockHeight = this.mapConfiguration.getMapBlockHeight();
    final int map = this.mapConfiguration.getMap();
    final Collection<UUID> viewers = this.mapConfiguration.getViewers();
    final int pixW = mapBlockWidth << 7;
    final int pixH = mapBlockHeight << 7;
    final int vidWidth = image.getWidth();
    final int vidHeight = image.getHeight();
    final int xOff = (pixW - vidWidth) >> 1;
    final int yOff = (pixH - vidHeight) >> 1;
    final int negXOff = xOff + vidWidth;
    final int negYOff = yOff + vidHeight;
    final int xLoopMin = Math.max(0, xOff >> 7);
    final int yLoopMin = Math.max(0, yOff >> 7);
    final int xLoopMax = Math.min(mapBlockWidth, (int) Math.ceil(negXOff / 128.0));
    final int yLoopMax = Math.min(mapBlockHeight, (int) Math.ceil(negYOff / 128.0));
    final Collection<MapDecoration> empty = new ArrayList<>();
    final ClientboundMapItemDataPacket[] packetArray = new ClientboundMapItemDataPacket[(xLoopMax - xLoopMin) * (yLoopMax - yLoopMin)];
    int arrIndex = 0;
    for (int y = yLoopMin; y < yLoopMax; y++) {
      final int relY = y << 7;
      final int topY = Math.max(0, yOff - relY);
      final int yDiff = Math.min(128 - topY, negYOff - (relY + topY));
      for (int x = xLoopMin; x < xLoopMax; x++) {
        final int relX = x << 7;
        final int topX = Math.max(0, xOff - relX);
        final int xDiff = Math.min(128 - topX, negXOff - (relX + topX));
        final int xPixMax = xDiff + topX;
        final int yPixMax = yDiff + topY;
        final byte[] mapData = new byte[xDiff * yDiff];
        for (int iy = topY; iy < yPixMax; iy++) {
          final int yPos = relY + iy;
          final int indexY = (yPos - yOff) * vidWidth;
          for (int ix = topX; ix < xPixMax; ix++) {
            final int val = (iy - topY) * xDiff + ix - topX;
            mapData[val] = rgb[indexY + relX + ix - xOff];
          }
        }
        final int mapId = map + mapBlockWidth * y + x;
        final MapId id = new MapId(mapId);
        final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(topX, topY, xDiff, yDiff, mapData);
        final ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(id, (byte) 0, false, empty, mapPatch);
        packetArray[arrIndex++] = packet;
      }
    }
    PacketUtils.sendPackets(viewers, packetArray);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (final int mapId : this.mapIds) {
      @SuppressWarnings("deprecation")
      final MapView mapView = Bukkit.getMap(mapId);
      if (mapView == null) {
        continue;
      }
      final List<MapRenderer> renderers = mapView.getRenderers();
      renderers.clear();
    }
  }
}

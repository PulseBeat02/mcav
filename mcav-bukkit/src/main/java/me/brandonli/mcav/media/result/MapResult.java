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
package me.brandonli.mcav.media.result;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;
import me.brandonli.mcav.utils.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * The {@code MapResult} class implements {@code DitherResultStep} and
 * is responsible for processing dithered video data and associated metadata
 * to generate map-based visual output suitable for rendering.
 *
 * <p>This class uses a {@code MapConfiguration} instance to determine
 * the parameters for processing, such as map resolution, block sizes,
 * and the list of viewers. It computes and sends packets to viewers
 * based on the provided map configuration and video frame data.
 */
public class MapResult implements DitherResultStep {

  private final MapConfiguration mapConfiguration;

  /**
   * Constructs a new instance of {@code MapResult} using the specified configuration.
   *
   * @param configuration the {@link MapConfiguration} object containing the settings
   *                      for the map result, such as map dimensions, resolution,
   *                      viewers, and map ID
   */
  public MapResult(final MapConfiguration configuration) {
    this.mapConfiguration = configuration;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void process(final byte[] rgb, final VideoMetadata metadata) {
    final int mapWidthResolution = this.mapConfiguration.getMapWidthResolution();
    final int mapBlockWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapBlockHeight = this.mapConfiguration.getMapBlockHeight();
    final int map = this.mapConfiguration.getMap();
    final int height = rgb.length / mapWidthResolution;
    final Collection<UUID> viewers = this.mapConfiguration.getViewers();
    final int pixW = mapBlockWidth << 7;
    final int pixH = mapBlockHeight << 7;
    final int xOff = (pixW - mapWidthResolution) >> 1;
    final int yOff = (pixH - height) >> 1;
    final int vidHeight = rgb.length / mapWidthResolution;
    final int negXOff = xOff + mapWidthResolution;
    final int negYOff = yOff + vidHeight;
    final int xLoopMin = Math.max(0, xOff >> 7);
    final int yLoopMin = Math.max(0, yOff >> 7);
    final int xLoopMax = Math.min(mapWidthResolution, (int) Math.ceil(negXOff / 128.0));
    final int yLoopMax = Math.min(height, (int) Math.ceil(negYOff / 128.0));
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
          final int indexY = (yPos - yOff) * mapWidthResolution;
          for (int ix = topX; ix < xPixMax; ix++) {
            mapData[(iy - topY) * xDiff + ix - topX] = rgb[indexY + relX + ix - xOff];
          }
        }
        final int mapId = map + mapWidthResolution * y + x;
        final MapId id = new MapId(mapId);
        final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(topX, topY, xDiff, yDiff, mapData);
        final ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(id, (byte) 0, false, empty, mapPatch);
        packetArray[arrIndex++] = packet;
      }
    }
    PacketUtils.sendPackets(viewers, packetArray);
  }
}

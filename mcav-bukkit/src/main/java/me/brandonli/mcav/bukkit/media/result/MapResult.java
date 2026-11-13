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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapRegion;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Displays video on a grid of maps by sending the complete picture in every frame.
 *
 * <p>This is the simplest way to display video on maps and every frame is shown exactly, but it uses a lot of
 * bandwidth: 16 KB per map per frame. Prefer {@link CompressedMapResult}, which only sends what changed.
 *
 * <p>The caller owns this result and its lifecycle; attaching it to a pipeline does not call start or release.
 * Input buffers remain caller-owned and are consumed synchronously, even when converted display data is applied
 * on a later server tick. Serialize frame delivery with lifecycle operations unless this class explicitly provides
 * locking, and never mutate an input buffer concurrently with conversion.
 *
 * <p>Packets are sent directly, so the filter may run on any thread.
 *
 * @since 1.0.0
 */
public final class MapResult implements DitherResultStep {

  private final MapConfiguration configuration;

  /**
   * Creates a result that sends the complete picture in every frame.
   *
   * @param configuration the configuration describing the map grid and the viewers
   * @throws NullPointerException if {@code configuration} is null
   * @since 1.0.0
   */
  public MapResult(final MapConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Map configuration must not be null!");

    this.configuration = configuration;
  }

  /**
   * Does nothing, because every frame is sent completely and nothing needs to be prepared.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {}

  /**
   * Clears the maps of every viewer.
   *
   * @since 1.0.0
   */
  @Override
  public void release() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int mapCount = columns * rows;
    MapPacketFactory.clear(viewers, startMapId, mapCount);
  }

  /**
   * Resizes the frame if the configuration asks for it, dithers it, and sends every configured map completely to the
   * viewers, with transparent padding around a smaller picture.
   *
   * @param samples   the frame, which is resized in place if resizing is configured
   * @param algorithm the dithering algorithm that converts the frame into map colors
   * @throws NullPointerException     if {@code samples} or {@code algorithm} is null
   * @throws IllegalArgumentException if {@code algorithm} returns more or fewer colors than the frame has pixels
   * @since 1.0.0
   */
  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");
    Preconditions.checkNotNull(algorithm, "Dither algorithm must not be null!");

    this.resizeIfConfigured(samples);

    final byte[] dithered = algorithm.ditherIntoBytes(samples);
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final MapLayout layout = MapLayout.of(startMapId, columns, rows, width, height);
    final List<MapTilePatch> patches = this.createGridPatches(layout, dithered);

    final Collection<UUID> viewers = this.configuration.getViewers();
    MapPacketFactory.send(viewers, patches);
  }

  private List<MapTilePatch> createGridPatches(final MapLayout layout, final byte[] image) {
    final int width = layout.getImageWidth();
    final int height = layout.getImageHeight();
    final int expectedLength = width * height;
    Preconditions.checkArgument(
      image.length == expectedLength,
      "Image length %s does not match the layout's %s pixels!",
      image.length,
      expectedLength
    );

    final int mapCount = layout.getMapCount();
    final List<MapTilePatch> patches = new ArrayList<>(mapCount);
    for (int index = 0; index < mapCount; index++) {
      final MapRegion region = layout.getRegion(index);
      final byte[] colors = new byte[MapLayout.MAP_SIZE * MapLayout.MAP_SIZE];
      if (!region.isEmpty()) {
        this.copyRegion(image, width, region, colors);
      }
      final int mapId = layout.getMapId(index);
      final MapTilePatch patch = MapTilePatch.of(mapId, 0, 0, MapLayout.MAP_SIZE, MapLayout.MAP_SIZE, colors);
      patches.add(patch);
    }

    return patches;
  }

  private void copyRegion(final byte[] image, final int width, final MapRegion region, final byte[] colors) {
    final int height = region.getHeight();
    final int sourceY = region.getSourceY();
    final int sourceX = region.getSourceX();
    final int localY = region.getLocalY();
    final int localX = region.getLocalX();
    final int regionWidth = region.getWidth();
    for (int row = 0; row < height; row++) {
      final int source = (sourceY + row) * width + sourceX;
      final int target = (localY + row) * MapLayout.MAP_SIZE + localX;
      System.arraycopy(image, source, colors, target, regionWidth);
    }
  }

  private void resizeIfConfigured(final ImageBuffer samples) {
    final boolean shouldResize = this.configuration.shouldResize();
    if (!shouldResize) {
      return;
    }

    final int targetWidth = this.configuration.getMapWidthResolution();
    final int targetHeight = this.configuration.getMapHeightResolution();
    final ResizeFilter filter = new ResizeFilter(targetWidth, targetHeight);
    filter.applyFilter(samples);
  }
}

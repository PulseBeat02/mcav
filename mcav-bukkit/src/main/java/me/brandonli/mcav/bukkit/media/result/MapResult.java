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
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
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
 */
public class MapResult implements DitherResultStep {

  private final MapConfiguration configuration;

  /**
   * Constructs a new {@code MapResult}.
   *
   * @param configuration the configuration describing the map grid and the viewers
   */
  public MapResult(final MapConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Map configuration must not be null");
    this.configuration = configuration;
  }

  /**
   * Resizes the frame if the configuration asks for it, dithers it, and sends every covered map completely to the
   * viewers.
   *
   * @param samples   the frame, which is resized in place if resizing is configured
   * @param algorithm the dithering algorithm that converts the frame into map colors
   */
  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    Preconditions.checkNotNull(algorithm, "Dither algorithm must not be null");
    this.resizeIfConfigured(samples);

    final byte[] dithered = algorithm.ditherIntoBytes(samples);
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final MapLayout layout = new MapLayout(startMapId, columns, rows, width, height);
    final List<MapTilePatch> patches = layout.extractAll(dithered);

    final Collection<UUID> viewers = this.configuration.getViewers();
    MapPacketFactory.send(viewers, patches);
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

  /**
   * Does nothing, because every frame is sent completely and nothing needs to be prepared.
   */
  @Override
  public void start() {
    // nothing needs to be prepared, every frame is sent completely
  }

  /**
   * Clears the maps of every viewer.
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
}

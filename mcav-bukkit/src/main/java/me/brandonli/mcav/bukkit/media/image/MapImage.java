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
package me.brandonli.mcav.bukkit.media.image;

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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Displays still images on a grid of maps.
 *
 * <p>Every image is dithered to the map palette and sent to every viewer. Images keep their size unless
 * {@link MapConfiguration#shouldResize()} is set, in which case they are resized to the configured resolution
 * first. As described in {@link MapConfiguration}, images that are smaller than the maps are centered, and images
 * that are larger are cropped equally on every side. Displaying a new image replaces the previous one directly,
 * without clearing the maps first, so there is no flicker between images.
 */
public class MapImage implements DisplayableImage {

  private final MapConfiguration configuration;
  private final DitherAlgorithm algorithm;

  MapImage(final MapConfiguration configuration, final DitherAlgorithm algorithm) {
    this.configuration = configuration;
    this.algorithm = algorithm;
  }

  /**
   * Dithers the image to the map palette and sends it to every viewer, resizing it first if the configuration asks
   * for it. May be called from any thread.
   *
   * @param image the image to show, which is resized in place if {@link MapConfiguration#shouldResize()} is set
   * @throws NullPointerException if the image is null
   */
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    this.resizeIfConfigured(image);

    final byte[] dithered = this.algorithm.ditherIntoBytes(image);
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int width = image.getWidth();
    final int height = image.getHeight();
    final MapLayout layout = new MapLayout(startMapId, columns, rows, width, height);
    final List<MapTilePatch> patches = layout.extractAll(dithered);

    final Collection<UUID> viewers = this.configuration.getViewers();
    MapPacketFactory.send(viewers, patches);
  }

  private void resizeIfConfigured(final ImageBuffer image) {
    final boolean shouldResize = this.configuration.shouldResize();
    if (!shouldResize) {
      return;
    }

    final int targetWidth = this.configuration.getMapWidthResolution();
    final int targetHeight = this.configuration.getMapHeightResolution();
    final ResizeFilter resize = new ResizeFilter(targetWidth, targetHeight);
    resize.applyFilter(image);
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

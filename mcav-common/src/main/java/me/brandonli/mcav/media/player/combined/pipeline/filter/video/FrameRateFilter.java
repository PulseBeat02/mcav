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
package me.brandonli.mcav.media.player.combined.pipeline.filter.video;

import static org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX;

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;

public final class FrameRateFilter implements VideoFilter {

  private long lastFrameTime = 0;

  FrameRateFilter() {
    // init
  }

  @Override
  public void applyFilter(final StaticImage samples, final VideoMetadata metadata) {
    final long current = System.currentTimeMillis();
    final long elapsed = current - this.lastFrameTime;
    final int frameRate = Math.toIntExact(1000 / elapsed);
    this.lastFrameTime = current;
    final String text = "Frame Rate: " + frameRate + " FPS";
    samples.drawText(text, 10, 20, FONT_HERSHEY_SIMPLEX, 0.25, new double[] { 0, 0, 0 }, 1);
  }
}

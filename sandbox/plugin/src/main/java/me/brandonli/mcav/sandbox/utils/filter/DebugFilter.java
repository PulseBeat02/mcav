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
package me.brandonli.mcav.sandbox.utils.filter;

import java.util.logging.Logger;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;

public final class DebugFilter implements VideoFilter {

  private final MCAVSandbox sandbox;
  private long lastFrameTime;
  private long frame;

  public DebugFilter(final MCAVSandbox sandbox) {
    this.lastFrameTime = System.currentTimeMillis();
    this.sandbox = sandbox;
  }

  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    final long current = System.currentTimeMillis();
    final long elapsed = current - this.lastFrameTime;
    if (elapsed <= 0) {
      return true;
    }

    final int frameRate = Math.toIntExact(1000 / elapsed);
    frame++;
    this.lastFrameTime = current;
    if (frame % 10 != 0) {
      return true;
    }

    final String text = String.valueOf(frameRate);
    final Logger logger = this.sandbox.getLogger();
    logger.info("Frame rate: " + text);
    return true;
  }
}

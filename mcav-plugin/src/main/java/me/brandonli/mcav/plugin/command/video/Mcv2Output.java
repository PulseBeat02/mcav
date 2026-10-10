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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;

/**
 * An MCV2 screen as the last filter of a pipeline: its result, which encodes the frames for the viewers with the MCV2
 * pack and dithers them for the others, and the slot of the pack it holds until it is released.
 */
public final class Mcv2Output implements FunctionalVideoFilter {

  private final Mcv2Result result;

  private final Mcv2PackServer.Lease lease;

  /**
   * Constructs the output.
   *
   * @param result the screen's result
   * @param lease  the screen's slot of the pack
   */
  Mcv2Output(final Mcv2Result result, final Mcv2PackServer.Lease lease) {
    Preconditions.checkNotNull(result, "Result must not be null");
    Preconditions.checkNotNull(lease, "Lease must not be null");
    this.result = result;
    this.lease = lease;
  }

  /**
   * Gets the screen's result.
   *
   * @return the result
   */
  public Mcv2Result getResult() {
    return this.result;
  }

  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    return this.result.applyFilter(samples, metadata);
  }

  /**
   * Starts the screen. Call on the main thread.
   */
  @Override
  public void start() {
    this.result.start();
  }

  /**
   * Stops the screen and gives its slot back to the pack, which keeps it for the next screen of its size. Call on the
   * main thread.
   */
  @Override
  public void release() {
    try {
      this.result.release();
    } finally {
      this.lease.close();
    }
  }
}

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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;

/**
 * A step of a video pipeline that inspects or modifies a frame.
 *
 * <p>Filters run on the video render thread of the player, one after another, for every frame. A filter that needs
 * a long time therefore lowers the frame rate of the whole pipeline, so expensive work should be handed off to
 * another thread. Filters modify the frame in place. Once the last filter ran, the player reuses the frame for a
 * later picture or releases it, so filters that keep a frame must {@link ImageBuffer#copy() copy} it.
 *
 * <pre><code>
 *   final VideoFilter watermark = new TextFilter("MCAV", 10, 20, TextFilter.DEFAULT_FONT, 0.5, new double[] { 255, 255, 255 });
 *   final VideoPipelineStepBuilder builder = PipelineBuilder.video();
 *   builder.then(watermark);
 *   builder.then(display);
 *   final VideoPipelineStep pipeline = builder.build();
 * </code></pre>
 */
@FunctionalInterface
public interface VideoFilter extends Filter<ImageBuffer, OriginalVideoMetadata> {
  /**
   * A filter that leaves every frame untouched.
   */
  VideoFilter NO_OP = (_, _) -> false;

  /**
   * Applies the filter to a frame without metadata about the original video, passing
   * {@link OriginalVideoMetadata#EMPTY} as the metadata.
   *
   * @param samples the frame to process
   * @return true if the filter changed the frame or may have changed it, false if it left the frame untouched
   */
  default boolean applyFilter(final ImageBuffer samples) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    return this.applyFilter(samples, OriginalVideoMetadata.EMPTY);
  }
}

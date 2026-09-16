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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * A video filter that hands every frame together with a dithering algorithm to a {@link DitherResultStep}, which
 * dithers the frame and displays the result. This is how frames reach map displays.
 *
 * <pre><code>
 *   final DitherAlgorithm algorithm = DitherAlgorithm.filterLite();
 *   final DitherResultStep maps = new CompressedMapResult(configuration);
 *   final FunctionalVideoFilter display = DitherFilter.dither(algorithm, maps);
 *   final VideoPipelineStepBuilder builder = PipelineBuilder.video();
 *   builder.then(display);
 *   final VideoPipelineStep pipeline = builder.build();
 * </code></pre>
 */
public final class DitherFilter implements FunctionalVideoFilter {

  private final DitherAlgorithm algorithm;
  private final DitherResultStep result;

  DitherFilter(final DitherAlgorithm algorithm, final DitherResultStep result) {
    this.algorithm = algorithm;
    this.result = result;
  }

  /**
   * Creates a filter that dithers frames with the algorithm and passes them to the result step.
   *
   * @param algorithm the dithering algorithm
   * @param result    the step that receives the frames
   * @return the filter
   */
  public static FunctionalVideoFilter dither(final DitherAlgorithm algorithm, final DitherResultStep result) {
    Preconditions.checkNotNull(algorithm, "Algorithm must not be null");
    Preconditions.checkNotNull(result, "Result step must not be null");
    return new DitherFilter(algorithm, result);
  }

  /**
   * Gets the dithering algorithm.
   *
   * @return the algorithm
   */
  public DitherAlgorithm getAlgorithm() {
    return this.algorithm;
  }

  /**
   * Gets the step that receives the frames.
   *
   * @return the result step
   */
  public DitherResultStep getResult() {
    return this.result;
  }

  /**
   * Hands a frame to the result step.
   *
   * @param samples  the frame
   * @param metadata the metadata of the original video
   * @return true, because the result step may modify the frame
   */
  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    this.result.process(samples, this.algorithm);
    return true;
  }

  /**
   * Prepares the result step, which for example creates the maps of the display. Pipelines call this once before
   * the first frame.
   */
  @Override
  public void start() {
    this.result.start();
  }

  /**
   * Releases the result step, which for example removes the maps of the display. Pipelines call this once after the
   * last frame.
   */
  @Override
  public void release() {
    this.result.release();
  }
}

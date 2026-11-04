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
package me.brandonli.mcav.plugin.utils;

import com.google.errorprone.annotations.Immutable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;

/**
 * The {@code <ditheringAlgorithm>} argument of the map commands ({@code /mcav video map}, {@code /mcav image map},
 * {@code /mcav browser create} and {@code /mcav vm create}): how the colors of the media are reduced to the limited
 * palette of Minecraft maps.
 *
 * <p>Maps can only show a fixed palette of colors, so every pixel has to be replaced by a palette color. Dithering
 * mixes neighboring palette colors so that areas look like the colors in between from a distance. The constants
 * fall into five families:
 *
 * <ul>
 *   <li><b>Error diffusion</b> ({@link #FILTER_LITE}, {@link #FLOYD_STEINBERG}, and so on) gives the best looking
 *   pictures. Each pixel passes its rounding error on to its unprocessed neighbors, which costs more CPU and makes
 *   the pattern of large areas shift between video frames. {@link #FILTER_LITE} is the recommended choice for
 *   video and images.</li>
 *   <li><b>Temporal error diffusion</b> ({@link #FLOYD_STEINBERG_TEMPORAL}) is error diffusion that keeps the color
 *   of pixels that barely changed since the previous frame, which stops flicker in video.</li>
 *   <li><b>Random dithering</b> ({@code RANDOM_*}) adds noise to every pixel. It is fast, but grainy, and the grain
 *   changes every frame.</li>
 *   <li><b>Nearest color</b> ({@link #NEAREST_COLOR}) does not dither at all. It is the fastest option and never
 *   flickers, but gradients show hard color bands.</li>
 *   <li><b>Ordered dithering</b> ({@code BAYER_*} and {@code CLUSTERED_DOT_*}, {@code VERTICAL_*} and
 *   {@code HORIZONTAL_*}) adds a fixed, tiled threshold pattern to the picture. It is fast, runs fully in parallel,
 *   and never flickers, at the cost of a visible regular pattern. Bayer matrices give a fine, dispersed pattern;
 *   clustered-dot matrices group dots like newspaper halftoning, which looks coarser but holds up better on the
 *   blocky pixels of maps. A larger matrix can represent more shades between two palette colors but has a larger,
 *   more noticeable pattern.</li>
 * </ul>
 *
 * <p>Every ordered pattern comes in three strengths. {@code LIGHT} (strength 0.5) only breaks up color bands and
 * keeps the picture close to {@link #NEAREST_COLOR}. {@code NORMAL} (strength 1.0) spans exactly one step between
 * neighboring palette colors and is the usual choice. {@code HEAVY} (strength 2.0) spans two steps, which smooths
 * gradients further but makes the pattern clearly visible and the colors noisier.
 *
 * <p>Call {@link #createAlgorithm()} once for every screen or image. Stateless algorithms are created the first time
 * they are used and then shared by every command that selects them; the stateful {@link #FLOYD_STEINBERG_TEMPORAL} is
 * created anew for every screen.
 *
 * <p>This enum is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public enum DitheringArgument {
  /**
   * Temporal Floyd-Steinberg error diffusion, made for video. It remembers the palette color every pixel received
   * in the previous frame and keeps it while the pixel stays within a small threshold, so still areas look exactly
   * the same frame after frame instead of flickering, and maps send less data because unchanged areas are skipped.
   * Frames are dithered in parallel strips, which keeps it fast on large screens. The remembered frame
   * belongs to one stream, so {@link #createAlgorithm()} returns a new instance for every screen; for still images it
   * looks the same as {@link #FLOYD_STEINBERG}.
   *
   * @since 1.0.0
   */
  FLOYD_STEINBERG_TEMPORAL(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG), Lifetime.PER_STREAM),

  /**
   * Filter Lite (also known as Sierra Lite) error diffusion. It spreads the error over only three neighbors, which
   * makes it the fastest error diffusion while looking almost identical to {@link #FLOYD_STEINBERG}. This is the
   * recommended algorithm for video and images on maps.
   *
   * @since 1.0.0
   */
  FILTER_LITE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.FILTER_LITE)),

  /**
   * Floyd-Steinberg error diffusion, the classic algorithm that spreads the error over four neighbors. It gives
   * fine, evenly distributed dithering at a moderate cost, slightly slower than {@link #FILTER_LITE}.
   *
   * @since 1.0.0
   */
  FLOYD_STEINBERG(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG)),

  /**
   * Jarvis, Judice, and Ninke error diffusion, which spreads the error over twelve neighbors in three rows. It gives
   * very smooth gradients with little visible pattern, but costs about three times as much CPU as
   * {@link #FLOYD_STEINBERG}, so it suits images and small screens better than large videos.
   *
   * @since 1.0.0
   */
  JARVIS_JUDICE_NINKE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.JARVIS_JUDICE_NINKE)),

  /**
   * Stucki error diffusion, which spreads the error over twelve neighbors in three rows like
   * {@link #JARVIS_JUDICE_NINKE}, with weights that keep edges sharper. It is just as slow.
   *
   * @since 1.0.0
   */
  STUCKI(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.STUCKI)),

  /**
   * Atkinson error diffusion, the algorithm of the original Macintosh. It passes on only three quarters of the
   * error, which raises contrast and gives a distinctive look where very light and very dark areas wash out into
   * flat colors. It costs about as much as {@link #FLOYD_STEINBERG}.
   *
   * @since 1.0.0
   */
  ATKINSON(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.ATKINSON)),

  /**
   * Stevenson and Arce error diffusion, which spreads the error over twelve neighbors on a hexagonal pattern across
   * four rows. It produces the least visible pattern of all error diffusion algorithms, at the highest CPU cost.
   *
   * @since 1.0.0
   */
  STEVENSON_ARCE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.STEVENSON_ARCE)),

  /**
   * Burkes error diffusion, which spreads the error over seven neighbors in two rows. It is a faster simplification
   * of {@link #STUCKI} that is still smoother than {@link #FLOYD_STEINBERG}.
   *
   * @since 1.0.0
   */
  BURKES(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.BURKES)),

  /**
   * Random dithering with light noise: up to 32 is added to or subtracted from every color channel before the
   * closest palette color is picked. It hides only the worst color bands and keeps the picture fairly clean. Fast,
   * but the grain changes every frame, so video shimmers.
   *
   * @since 1.0.0
   */
  RANDOM_LIGHT_WEIGHT(() -> random(RandomDither.LIGHT_WEIGHT)),

  /**
   * Random dithering with normal noise: up to 64 is added to or subtracted from every color channel. It removes
   * most color bands at the cost of a clearly grainy picture that changes every frame.
   *
   * @since 1.0.0
   */
  RANDOM_NORMAL_WEIGHT(() -> random(RandomDither.NORMAL_WEIGHT)),

  /**
   * Random dithering with heavy noise: up to 128 is added to or subtracted from every color channel. Gradients are
   * fully smoothed out, but the picture is very noisy and colors are often far off; mostly useful as an effect.
   *
   * @since 1.0.0
   */
  RANDOM_HEAVY_WEIGHT(() -> random(RandomDither.HEAVY_WEIGHT)),

  /**
   * No dithering: every pixel becomes the closest palette color. This is the fastest option and produces
   * perfectly stable frames, which also keeps the data sent to players low, but gradients such as skies show hard
   * color bands. A good choice for pixel art, text, and user interfaces such as the browser.
   *
   * @since 1.0.0
   */
  NEAREST_COLOR(DitheringArgument::nearest),

  /**
   * Bayer ordered dithering with the 2x2 matrix at light strength. The 2x2 matrix has the finest possible
   * checkerboard pattern but only 5 shades between two palette colors; at light strength it barely changes the
   * picture.
   *
   * @since 1.0.0
   */
  BAYER_2X2_LIGHT(() -> ordered(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)),

  /**
   * Bayer ordered dithering with the 2x2 matrix at normal strength: a fine, hardly visible checkerboard with only 5
   * shades between two palette colors, so gradients still show steps.
   *
   * @since 1.0.0
   */
  BAYER_2X2_NORMAL(() -> ordered(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAXIMUM, PixelMapper.NORMAL_STRENGTH)),

  /**
   * Bayer ordered dithering with the 2x2 matrix at heavy strength: the checkerboard becomes clearly visible and
   * colors get noisier, in exchange for softer gradients.
   *
   * @since 1.0.0
   */
  BAYER_2X2_HEAVY(() -> ordered(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)),

  /**
   * Bayer ordered dithering with the 4x4 matrix at light strength. The 4x4 matrix represents 17 shades between two
   * palette colors with a small crosshatch pattern; light strength only breaks up color bands.
   *
   * @since 1.0.0
   */
  BAYER_4X4_LIGHT(() -> ordered(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)),

  /**
   * Bayer ordered dithering with the 4x4 matrix at normal strength. This is the classic, balanced ordered dither:
   * 17 shades between two palette colors, a small regular crosshatch, fast, and stable on video.
   *
   * @since 1.0.0
   */
  BAYER_4X4_NORMAL(() -> ordered(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAXIMUM, PixelMapper.NORMAL_STRENGTH)),

  /**
   * Bayer ordered dithering with the 4x4 matrix at heavy strength: smoother gradients, but a clearly visible
   * crosshatch and noisier colors.
   *
   * @since 1.0.0
   */
  BAYER_4X4_HEAVY(() -> ordered(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)),

  /**
   * Bayer ordered dithering with the 8x8 matrix at light strength. The 8x8 matrix represents 65 shades between two
   * palette colors, the most of the Bayer matrices; light strength keeps its pattern subtle.
   *
   * @since 1.0.0
   */
  BAYER_8X8_LIGHT(() -> ordered(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)),

  /**
   * Bayer ordered dithering with the 8x8 matrix at normal strength: the smoothest Bayer gradients, 65 shades between
   * two palette colors, with a slightly larger pattern than {@link #BAYER_4X4_NORMAL}.
   *
   * @since 1.0.0
   */
  BAYER_8X8_NORMAL(() -> ordered(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAXIMUM, PixelMapper.NORMAL_STRENGTH)),

  /**
   * Bayer ordered dithering with the 8x8 matrix at heavy strength: very smooth gradients from a distance, but a
   * clearly visible pattern and noisy colors up close.
   *
   * @since 1.0.0
   */
  BAYER_8X8_HEAVY(() -> ordered(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)),

  /**
   * Clustered-dot ordered dithering with the 6x6 matrix from Ulichney's Digital Halftoning at light strength. The
   * matrix grows round dots like newspaper halftoning and represents 37 shades; light strength keeps the dots
   * faint.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 matrix from Ulichney's Digital Halftoning at normal strength: a
   * newspaper-like halftone with 37 shades that looks coarser than Bayer but less busy on large map walls.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 matrix from Ulichney's Digital Halftoning at heavy strength: bold,
   * clearly visible halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the non-diagonal 8x8 matrix from Lau and Arce's Modern Digital Halftoning at
   * light strength. The matrix represents 65 shades with large square-aligned dots; light strength keeps them
   * faint.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_8X8_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the non-diagonal 8x8 matrix from Lau and Arce's Modern Digital Halftoning at
   * normal strength: smooth halftone gradients with 65 shades, but the dots are large and visible on small
   * screens.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_8X8_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the non-diagonal 8x8 matrix from Lau and Arce's Modern Digital Halftoning at
   * heavy strength: large, bold halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_8X8_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "central white point" matrix at light strength. It looks nearly
   * identical to {@link #CLUSTERED_DOT_6X6_LIGHT}, with 37 shades and faint dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_2_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "central white point" matrix at normal strength, a close variant
   * of {@link #CLUSTERED_DOT_6X6_NORMAL} with 37 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_2_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "central white point" matrix at heavy strength: bold, clearly
   * visible halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_2_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "balanced centered point" matrix at light strength. It looks
   * nearly identical to {@link #CLUSTERED_DOT_6X6_LIGHT}, with 37 shades and faint dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_3_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "balanced centered point" matrix at normal strength, a close
   * variant of {@link #CLUSTERED_DOT_6X6_NORMAL} with 37 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_3_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 "balanced centered point" matrix at heavy strength: bold, clearly
   * visible halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_6X6_3_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 "balanced centered points" matrix at light strength. Its
   * dots run at 45 degrees, but it represents only 33 shades, so {@link #CLUSTERED_DOT_DIAGONAL_8X8_LIGHT} is
   * usually the better choice.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_3_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 "balanced centered points" matrix at normal strength: a
   * 45-degree halftone with 33 shades, almost identical to but less smooth than
   * {@link #CLUSTERED_DOT_DIAGONAL_8X8_NORMAL}.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_3_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 "balanced centered points" matrix at heavy strength: bold
   * 45-degree halftone dots with only 33 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_3_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 4x4 matrix at light strength. The smallest clustered-dot matrix, with
   * 17 shades and small, faint newspaper-like dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_4X4_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 4x4 matrix at normal strength: a compact halftone with 17 shades whose
   * small dots suit small map screens.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_4X4_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 4x4 matrix at heavy strength: small but clearly visible halftone dots
   * and noisier colors.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_4X4_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Ordered dithering with the 5x3 vertical line matrix at light strength. An artistic pattern of faint vertical
   * lines with 16 shades.
   *
   * @since 1.0.0
   */
  VERTICAL_5X3_LIGHT(() -> ordered(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)),

  /**
   * Ordered dithering with the 5x3 vertical line matrix at normal strength: shades are drawn as vertical line
   * artifacts, 16 of them, for a stylized look rather than accuracy.
   *
   * @since 1.0.0
   */
  VERTICAL_5X3_NORMAL(() -> ordered(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAXIMUM, PixelMapper.NORMAL_STRENGTH)),

  /**
   * Ordered dithering with the 5x3 vertical line matrix at heavy strength: strong, clearly visible vertical lines.
   *
   * @since 1.0.0
   */
  VERTICAL_5X3_HEAVY(() -> ordered(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)),

  /**
   * Ordered dithering with the 3x5 horizontal line matrix, the rotated {@link #VERTICAL_5X3_LIGHT}, at light
   * strength: faint horizontal lines with 16 shades.
   *
   * @since 1.0.0
   */
  HORIZONTAL_3X5_LIGHT(() -> ordered(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)),

  /**
   * Ordered dithering with the 3x5 horizontal line matrix at normal strength: shades are drawn as horizontal line
   * artifacts, 16 of them, reminiscent of scan lines.
   *
   * @since 1.0.0
   */
  HORIZONTAL_3X5_NORMAL(() -> ordered(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAXIMUM, PixelMapper.NORMAL_STRENGTH)),

  /**
   * Ordered dithering with the 3x5 horizontal line matrix at heavy strength: strong, clearly visible horizontal
   * lines.
   *
   * @since 1.0.0
   */
  HORIZONTAL_3X5_HEAVY(() -> ordered(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 6x6 matrix at light strength. Its dots run at 45
   * degrees, which the eye notices less than a square grid, and it represents 19 shades; light strength keeps the
   * dots faint.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_6X6_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 6x6 matrix at normal strength: a compact 45-degree
   * halftone with 19 shades, so gradients show some steps.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_6X6_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 6x6 matrix at heavy strength: bold 45-degree halftone
   * dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_6X6_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's alternative diagonal 8x8 matrix at light strength. It represents
   * only 33 shades, so {@link #CLUSTERED_DOT_DIAGONAL_8X8_LIGHT} is usually the better choice.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_2_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's alternative diagonal 8x8 matrix at normal strength: a 45-degree
   * halftone with 33 shades, almost identical to but less smooth than {@link #CLUSTERED_DOT_DIAGONAL_8X8_NORMAL}.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_2_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's alternative diagonal 8x8 matrix at heavy strength: bold
   * 45-degree halftone dots with only 33 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_2_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 16x16 matrix at light strength. The largest matrix,
   * with 129 shades; light strength keeps its large dots faint.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_16X16_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 16x16 matrix at normal strength: the smoothest
   * gradients of all ordered patterns, 129 shades, but its 45-degree dots are so large that they only look good on
   * big map walls viewed from a distance.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_16X16_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's diagonal 16x16 matrix at heavy strength: very large, bold
   * 45-degree halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_16X16_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 5x5 spiral matrix at light strength. Instead of alternating
   * light and dark dots, dark areas grow in a spiral to fill each cell; 26 shades, faint at light strength.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_SPIRAL_5X5_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 5x5 spiral matrix at normal strength: dark areas grow in a
   * spiral to fill each 5x5 cell, 26 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_SPIRAL_5X5_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 5x5 spiral matrix at heavy strength: bold, clearly visible
   * spiral cells.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_SPIRAL_5X5_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 6x6 horizontal line matrix at light strength. It clusters pixels
   * about horizontal lines, with 37 shades; faint at light strength.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_HORIZONTAL_LINE_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 6x6 horizontal line matrix at normal strength: a line-screen
   * halftone of horizontal lines with 37 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_HORIZONTAL_LINE_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with Ulichney's 6x6 horizontal line matrix at heavy strength: bold, clearly
   * visible horizontal lines.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_HORIZONTAL_LINE_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 vertical line matrix, the rotated
   * {@link #CLUSTERED_DOT_HORIZONTAL_LINE_LIGHT}, at light strength: faint vertical lines with 37 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_VERTICAL_LINE_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 vertical line matrix at normal strength: a line-screen halftone of
   * vertical lines with 37 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_VERTICAL_LINE_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the 6x6 vertical line matrix at heavy strength: bold, clearly visible
   * vertical lines.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_VERTICAL_LINE_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 matrix at light strength. Its newspaper-like dots run at 45
   * degrees and it represents 65 shades; light strength keeps the dots faint.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_LIGHT(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAXIMUM, PixelMapper.MINIMUM_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 matrix at normal strength: the best balance of the
   * clustered-dot patterns, a classic 45-degree newspaper halftone with 65 shades.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_NORMAL(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAXIMUM, PixelMapper.NORMAL_STRENGTH)
  ),

  /**
   * Clustered-dot ordered dithering with the diagonal 8x8 matrix at heavy strength: bold, clearly visible 45-degree
   * halftone dots.
   *
   * @since 1.0.0
   */
  CLUSTERED_DOT_DIAGONAL_8X8_HEAVY(() ->
    ordered(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAXIMUM, PixelMapper.MAXIMUM_STRENGTH)
  );

  private final AlgorithmFactory algorithmFactory;
  private final Lifetime lifetime;

  DitheringArgument(final AlgorithmFactory algorithmFactory) {
    this(algorithmFactory, Lifetime.SHARED);
  }

  DitheringArgument(final AlgorithmFactory algorithmFactory, final Lifetime lifetime) {
    this.algorithmFactory = algorithmFactory;
    this.lifetime = lifetime;
  }

  private static DitherAlgorithm ordered(final ThresholdMatrix matrix, final int maximum, final float strength) {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(matrix, maximum, strength);
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    builder.withDitherMatrix(mapper);
    return builder.build();
  }

  private static DitherAlgorithm nearest() {
    final NearestDitherBuilder builder = DitherAlgorithm.nearest();
    return builder.build();
  }

  private static DitherAlgorithm random(final int weight) {
    final RandomDitherBuilder builder = DitherAlgorithm.random();
    builder.withWeight(weight);
    return builder.build();
  }

  private static DitherAlgorithm errorDiffusion(final ErrorDiffusionDitherBuilder.Algorithm type) {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    builder.withAlgorithm(type);
    return builder.build();
  }

  /**
   * Creates the algorithm for one stream of frames, such as one map screen or one image.
   *
   * <p>Stateful algorithms, currently only {@link #FLOYD_STEINBERG_TEMPORAL}, remember the previous frame of the
   * stream they dither. Every call therefore returns a new instance of them, so that two screens playing at the same
   * time never mix up each other's frames. Stateless algorithms can be shared safely between any number of streams and
   * threads, so they are created once, on first use, because some of them precompute large tables, and every later
   * call returns that same instance.
   *
   * @return a new instance of a stateful algorithm, or the shared instance of a stateless one
   * @since 1.0.0
   */
  public DitherAlgorithm createAlgorithm() {
    return switch (this.lifetime) {
      case SHARED -> this.getSharedAlgorithm();
      case PER_STREAM -> this.algorithmFactory.create();
    };
  }

  // Lock the enum constant so tests can force concurrent lazy initialization by holding it.
  private DitherAlgorithm getSharedAlgorithm() {
    final DitherAlgorithm existing = SharedAlgorithms.ALGORITHMS.get(this);
    if (existing != null) {
      return existing;
    }

    synchronized (this) {
      final DitherAlgorithm createdByAnotherThread = SharedAlgorithms.ALGORITHMS.get(this);
      if (createdByAnotherThread != null) {
        return createdByAnotherThread;
      }

      final DitherAlgorithm created = this.algorithmFactory.create();
      SharedAlgorithms.ALGORITHMS.put(this, created);

      return created;
    }
  }

  private enum Lifetime {
    SHARED,
    PER_STREAM,
  }

  @Immutable
  @FunctionalInterface
  private interface AlgorithmFactory {
    DitherAlgorithm create();
  }

  // kept outside the constants, so they stay immutable
  private static final class SharedAlgorithms {

    private static final ConcurrentMap<DitheringArgument, DitherAlgorithm> ALGORITHMS = new ConcurrentHashMap<>();
  }
}

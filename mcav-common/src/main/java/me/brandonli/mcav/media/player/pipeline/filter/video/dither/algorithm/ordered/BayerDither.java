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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * An ordered dithering algorithm together with the threshold matrices it can use. Create instances with
 * {@link DitherAlgorithm#ordered()} and pass one of the matrices to
 * {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)}.
 *
 * <p>Bayer matrices produce a fine, regular crosshatch and are the usual choice. The clustered dot matrices
 * imitate the halftone screens of printed newspapers, and the line matrices produce horizontal or vertical
 * stripes. Every matrix is accompanied by a {@code _MAX} constant that names its number of levels. The matrices
 * are based on the collection of the <a href="https://github.com/makeworld-the-better-one/dither">dither</a>
 * library.
 *
 * <p>The matrices are {@link ThresholdMatrix immutable}, so the shared constants cannot be changed by anyone; use
 * {@link ThresholdMatrix#toArray()} for a copy of the entries.
 */
public interface BayerDither extends DitherAlgorithm {
  /**
   * The number of threshold levels of {@link #NORMAL_2X2}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int,
   * float)} together with the matrix.
   */
  int NORMAL_2X2_MAX = 4;

  /**
   * The 2x2 Bayer matrix, the smallest ordered dither pattern.
   *
   * <p>It only produces five gray levels, but its fine checkerboard hides best on small map screens, which is why the
   * ordered dither builder uses it by default.
   */
  ThresholdMatrix NORMAL_2X2 = ThresholdMatrix.of(new int[][] { { 1, 3 }, { 4, 2 } });

  /**
   * The number of threshold levels of {@link #NORMAL_4X4}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int,
   * float)} together with the matrix.
   */
  int NORMAL_4X4_MAX = 16;

  /**
   * The 4x4 Bayer matrix, which produces 17 gray levels with a regular crosshatch.
   *
   * <p>It is the usual choice for ordered dithering: smooth enough for gradients and still fine-grained.
   */
  ThresholdMatrix NORMAL_4X4 = ThresholdMatrix.of(new int[][] { { 1, 9, 3, 11 }, { 13, 5, 15, 7 }, { 4, 12, 2, 10 }, { 16, 8, 14, 6 } });

  /**
   * The number of threshold levels of {@link #NORMAL_8X8}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int,
   * float)} together with the matrix.
   */
  int NORMAL_8X8_MAX = 64;

  /**
   * The 8x8 Bayer matrix, which produces 65 gray levels.
   *
   * <p>Use it for smooth gradients on large screens; its pattern is coarser than the 4x4 matrix.
   */
  ThresholdMatrix NORMAL_8X8 = ThresholdMatrix.of(
    new int[][] {
      { 1, 49, 13, 61, 4, 52, 16, 64 },
      { 33, 17, 45, 29, 36, 20, 48, 32 },
      { 9, 57, 5, 53, 12, 60, 8, 56 },
      { 41, 25, 37, 21, 44, 28, 40, 24 },
      { 3, 51, 15, 63, 2, 50, 14, 62 },
      { 35, 19, 47, 31, 34, 18, 46, 30 },
      { 11, 59, 7, 55, 10, 58, 6, 54 },
      { 43, 27, 39, 23, 42, 26, 38, 22 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_4X4}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix,
   * int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_4X4_MAX = 16;

  /**
   * A 4x4 clustered dot matrix from <a href="http://caca.zoy.org/study/part2.html">the libcaca study</a>, which grows
   * round dots from the center of
   * every tile like the halftone screens of newspapers.
   *
   * <p>The dots are aligned with the pixel grid rather than diagonal. Use it for a printed look.
   */
  ThresholdMatrix CLUSTERED_DOT_4X4 = ThresholdMatrix.of(
    new int[][] { { 12, 5, 6, 13 }, { 4, 0, 1, 7 }, { 11, 3, 2, 8 }, { 15, 10, 9, 14 } }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_DIAGONAL_8X8}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_MAX = 64;

  /**
   * An 8x8 diagonal clustered dot matrix from <a href="http://caca.zoy.org/study/part2.html">the libcaca study</a>,
   * whose dots form a pattern at a
   * 45-degree angle like newspaper halftones.
   *
   * <p>It represents 65 gray levels and is the best of the diagonal clustered dot matrices.
   */
  ThresholdMatrix CLUSTERED_DOT_DIAGONAL_8X8 = ThresholdMatrix.of(
    new int[][] {
      { 24, 10, 12, 26, 35, 47, 49, 37 },
      { 8, 0, 2, 14, 45, 59, 61, 51 },
      { 22, 6, 4, 16, 43, 57, 63, 53 },
      { 30, 20, 18, 28, 33, 41, 55, 39 },
      { 34, 46, 48, 36, 25, 11, 13, 27 },
      { 44, 58, 60, 50, 9, 1, 3, 15 },
      { 42, 56, 62, 52, 23, 7, 5, 17 },
      { 32, 40, 54, 38, 31, 21, 19, 29 },
    }
  );

  /**
   * The number of threshold levels of {@link #VERTICAL_5X3}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int,
   * float)} together with the matrix.
   */
  int VERTICAL_5X3_MAX = 15;

  /**
   * A 5x3 matrix from <a href="http://caca.zoy.org/study/part2.html">the libcaca study</a> that orders its
   * thresholds in vertical lines.
   *
   * <p>It produces vertical stripes, an artistic effect rather than a faithful reproduction.
   */
  ThresholdMatrix VERTICAL_5X3 = ThresholdMatrix.of(new int[][] { { 9, 3, 0, 6, 12 }, { 10, 4, 1, 7, 13 }, { 11, 5, 2, 8, 14 } });

  /**
   * The number of threshold levels of {@link #HORIZONTAL_3X5}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix,
   * int, float)} together with the matrix.
   */
  int HORIZONTAL_3X5_MAX = 15;

  /**
   * A 3x5 matrix that orders its thresholds in horizontal lines, the rotated version of {@link #VERTICAL_5X3}.
   *
   * <p>It produces horizontal stripes, an artistic effect rather than a faithful reproduction.
   */
  ThresholdMatrix HORIZONTAL_3X5 = ThresholdMatrix.of(new int[][] { { 9, 10, 11 }, { 3, 4, 5 }, { 0, 1, 2 }, { 6, 7, 8 }, { 12, 13, 14 } });

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_DIAGONAL_6X6}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_6X6_MAX = 18;

  /**
   * A 6x6 diagonal clustered dot matrix from figure 5.4 of Digital Halftoning by Robert Ulichney, called M = 3 there.
   *
   * <p>It represents 19 gray levels with dots at a 45-degree angle, for a coarse halftone look.
   */
  ThresholdMatrix CLUSTERED_DOT_DIAGONAL_6X6 = ThresholdMatrix.of(
    new int[][] {
      { 8, 6, 7, 9, 11, 10 },
      { 5, 0, 1, 12, 17, 16 },
      { 4, 3, 2, 13, 14, 15 },
      { 9, 11, 10, 8, 6, 7 },
      { 12, 17, 16, 5, 0, 1 },
      { 13, 14, 15, 4, 3, 2 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_DIAGONAL_8X8_2}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_2_MAX = 32;

  /**
   * An 8x8 diagonal clustered dot matrix from figure 5.4 of Digital Halftoning by Robert Ulichney, called M = 4 there.
   *
   * <p>It represents only 33 gray levels, so {@link #CLUSTERED_DOT_DIAGONAL_8X8} is almost always the better choice.
   */
  ThresholdMatrix CLUSTERED_DOT_DIAGONAL_8X8_2 = ThresholdMatrix.of(
    new int[][] {
      { 13, 11, 12, 15, 18, 20, 19, 16 },
      { 4, 3, 2, 9, 27, 28, 29, 22 },
      { 5, 0, 1, 10, 26, 31, 30, 21 },
      { 8, 6, 7, 14, 23, 25, 24, 17 },
      { 18, 20, 19, 16, 13, 11, 12, 15 },
      { 27, 28, 29, 22, 4, 3, 2, 9 },
      { 26, 31, 30, 21, 5, 0, 1, 10 },
      { 23, 25, 24, 17, 8, 6, 7, 14 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_DIAGONAL_16X16}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_16X16_MAX = 128;

  /**
   * A 16x16 diagonal clustered dot matrix from figure 5.4 of Digital Halftoning by Robert Ulichney, called M = 8 there.
   *
   * <p>It represents 129 gray levels with large dots at a 45-degree angle, which suits large screens.
   */
  ThresholdMatrix CLUSTERED_DOT_DIAGONAL_16X16 = ThresholdMatrix.of(
    new int[][] {
      { 63, 58, 50, 40, 41, 51, 59, 60, 64, 69, 77, 87, 86, 76, 68, 67 },
      { 57, 33, 27, 18, 19, 28, 34, 52, 70, 94, 100, 109, 108, 99, 93, 75 },
      { 49, 26, 13, 11, 12, 15, 29, 44, 78, 101, 114, 116, 115, 112, 98, 83 },
      { 39, 17, 4, 3, 2, 9, 20, 42, 88, 110, 123, 124, 125, 118, 107, 85 },
      { 38, 16, 5, 0, 1, 10, 21, 43, 89, 111, 122, 127, 126, 117, 106, 84 },
      { 48, 25, 8, 6, 7, 14, 30, 45, 79, 102, 119, 121, 120, 113, 97, 82 },
      { 56, 32, 24, 23, 22, 31, 35, 53, 71, 95, 103, 104, 105, 96, 92, 74 },
      { 62, 55, 47, 37, 36, 46, 54, 61, 65, 72, 80, 90, 91, 81, 73, 66 },
      { 64, 69, 77, 87, 86, 76, 68, 67, 63, 58, 50, 40, 41, 51, 59, 60 },
      { 70, 94, 100, 109, 108, 99, 93, 75, 57, 33, 27, 18, 19, 28, 34, 52 },
      { 78, 101, 114, 116, 115, 112, 98, 83, 49, 26, 13, 11, 12, 15, 29, 44 },
      { 88, 110, 123, 124, 125, 118, 107, 85, 39, 17, 4, 3, 2, 9, 20, 42 },
      { 89, 111, 122, 127, 126, 117, 106, 84, 38, 16, 5, 0, 1, 10, 21, 43 },
      { 79, 102, 119, 121, 120, 113, 97, 82, 48, 25, 8, 6, 7, 14, 30, 45 },
      { 71, 95, 103, 104, 105, 96, 92, 74, 56, 32, 24, 23, 22, 31, 35, 53 },
      { 65, 72, 80, 90, 91, 81, 73, 66, 62, 55, 47, 37, 36, 46, 54, 61 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_6X6}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix,
   * int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_6X6_MAX = 36;

  /**
   * A 6x6 clustered dot matrix from figure 5.9 of Digital Halftoning by Robert Ulichney.
   *
   * <p>It represents 37 gray levels with dots aligned with the pixel grid.
   */
  ThresholdMatrix CLUSTERED_DOT_6X6 = ThresholdMatrix.of(
    new int[][] {
      { 34, 29, 17, 21, 30, 35 },
      { 28, 14, 9, 16, 20, 31 },
      { 13, 8, 4, 5, 15, 19 },
      { 12, 3, 0, 1, 10, 18 },
      { 27, 7, 2, 6, 23, 24 },
      { 33, 26, 11, 22, 25, 32 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_SPIRAL_5X5}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_SPIRAL_5X5_MAX = 25;

  /**
   * A 5x5 spiral matrix from figure 5.13 of Digital Halftoning by Robert Ulichney.
   *
   * <p>It represents 26 gray levels; instead of alternating dark and light dots, the dark areas spiral outward until
   * <p>they fill the tile.
   */
  ThresholdMatrix CLUSTERED_DOT_SPIRAL_5X5 = ThresholdMatrix.of(
    new int[][] { { 20, 21, 22, 23, 24 }, { 19, 6, 7, 8, 9 }, { 18, 5, 0, 1, 10 }, { 17, 4, 3, 2, 11 }, { 16, 15, 14, 13, 12 } }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_HORIZONTAL_LINE}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_HORIZONTAL_LINE_MAX = 36;

  /**
   * A 6x6 matrix from figure 5.13 of Digital Halftoning by Robert Ulichney that clusters pixels about horizontal lines.
   *
   * <p>It represents 37 gray levels as horizontal line screens.
   */
  ThresholdMatrix CLUSTERED_DOT_HORIZONTAL_LINE = ThresholdMatrix.of(
    new int[][] {
      { 35, 33, 31, 30, 32, 34 },
      { 23, 21, 19, 18, 20, 22 },
      { 11, 9, 7, 6, 8, 10 },
      { 5, 3, 1, 0, 2, 4 },
      { 17, 15, 13, 12, 14, 16 },
      { 29, 27, 25, 24, 26, 28 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_VERTICAL_LINE}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_VERTICAL_LINE_MAX = 36;

  /**
   * A 6x6 matrix that clusters pixels about vertical lines, the rotated version of {@link
   * #CLUSTERED_DOT_HORIZONTAL_LINE}.
   *
   * <p>It represents 37 gray levels as vertical line screens.
   */
  ThresholdMatrix CLUSTERED_DOT_VERTICAL_LINE = ThresholdMatrix.of(
    new int[][] {
      { 35, 23, 11, 5, 17, 29 },
      { 33, 21, 9, 3, 15, 27 },
      { 31, 19, 7, 1, 13, 25 },
      { 30, 18, 6, 0, 12, 24 },
      { 32, 20, 8, 2, 14, 26 },
      { 34, 22, 10, 4, 16, 28 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_8X8}; pass it to {@link PixelMapper#ofPixelMapper(ThresholdMatrix,
   * int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_8X8_MAX = 64;

  /**
   * An 8x8 clustered dot matrix from figure 1.5 of Modern Digital Halftoning by Daniel L. Lau and Gonzalo R. Arce.
   *
   * <p>It is like {@link #CLUSTERED_DOT_DIAGONAL_8X8} with dots aligned with the pixel grid and represents 65 gray
   * <p>levels. The published matrix repeats 63 and skips 32, so its entries from 33 up are renumbered by one; the order
   * <p>of the thresholds is unchanged.
   */
  ThresholdMatrix CLUSTERED_DOT_8X8 = ThresholdMatrix.of(
    new int[][] {
      { 3, 9, 17, 27, 25, 15, 7, 1 },
      { 11, 29, 37, 45, 43, 35, 23, 5 },
      { 19, 39, 51, 57, 55, 49, 33, 13 },
      { 31, 47, 59, 63, 61, 53, 41, 21 },
      { 30, 46, 58, 62, 60, 52, 40, 20 },
      { 18, 38, 50, 56, 54, 48, 32, 12 },
      { 10, 28, 36, 44, 42, 34, 22, 4 },
      { 2, 8, 16, 26, 24, 14, 6, 0 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_6X6_2}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_6X6_2_MAX = 36;

  /**
   * A variant of {@link #CLUSTERED_DOT_6X6} from <a href="https://archive.is/71e9G">a halftoning article</a>, called
   * central white point there.
   *
   * <p>It looks nearly identical to {@link #CLUSTERED_DOT_6X6}.
   */
  ThresholdMatrix CLUSTERED_DOT_6X6_2 = ThresholdMatrix.of(
    new int[][] {
      { 34, 25, 21, 17, 29, 33 },
      { 30, 13, 9, 5, 12, 24 },
      { 18, 6, 1, 0, 8, 20 },
      { 22, 10, 2, 3, 4, 16 },
      { 26, 14, 7, 11, 15, 28 },
      { 35, 31, 19, 23, 27, 32 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_6X6_3}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_6X6_3_MAX = 36;

  /**
   * A variant of {@link #CLUSTERED_DOT_6X6} from <a href="https://archive.is/71e9G">a halftoning article</a>, called
   * balanced centered point there.
   *
   * <p>It looks nearly identical to {@link #CLUSTERED_DOT_6X6}.
   */
  ThresholdMatrix CLUSTERED_DOT_6X6_3 = ThresholdMatrix.of(
    new int[][] {
      { 30, 22, 16, 21, 33, 35 },
      { 24, 11, 7, 9, 26, 28 },
      { 13, 5, 0, 2, 14, 19 },
      { 15, 3, 1, 4, 12, 18 },
      { 27, 8, 6, 10, 25, 29 },
      { 32, 20, 17, 23, 31, 34 },
    }
  );

  /**
   * The number of threshold levels of {@link #CLUSTERED_DOT_DIAGONAL_8X8_3}; pass it to {@link
   * PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)} together with the matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_3_MAX = 32;

  /**
   * A diagonal 8x8 clustered dot matrix from <a href="https://archive.is/71e9G">a halftoning article</a>, called
   * diagonal ordered matrix with balanced centered points there.
   *
   * <p>It represents only 33 gray levels, so {@link #CLUSTERED_DOT_DIAGONAL_8X8} is almost always the better choice.
   */
  ThresholdMatrix CLUSTERED_DOT_DIAGONAL_8X8_3 = ThresholdMatrix.of(
    new int[][] {
      { 13, 9, 5, 12, 18, 22, 26, 19 },
      { 6, 1, 0, 8, 25, 30, 31, 23 },
      { 10, 2, 3, 4, 21, 29, 28, 27 },
      { 14, 7, 11, 15, 17, 24, 20, 16 },
      { 18, 22, 26, 19, 13, 9, 5, 12 },
      { 25, 30, 31, 23, 6, 1, 0, 8 },
      { 21, 29, 28, 27, 10, 2, 3, 4 },
      { 17, 24, 20, 16, 14, 7, 11, 15 },
    }
  );

  /**
   * Generates a Bayer matrix of a power-of-two size, such as 2, 4, 8, or 16. The entries rank the pixels of the
   * pattern from 0 to {@code size * size - 1}, so {@code size * size} is the level count to pass along with it.
   *
   * @param size the width and height of the matrix, which must be a power of two
   * @return the matrix
   */
  static int[][] createBayerMatrix(final int size) {
    Preconditions.checkArgument(size > 0 && (size & (size - 1)) == 0, "Size must be a power of two");

    int[][] matrix = { { 0 } };
    while (matrix.length < size) {
      matrix = doubleBayerMatrix(matrix);
    }
    return matrix;
  }

  /**
   * Builds the Bayer matrix of twice the size from a Bayer matrix, by replacing every entry with a 2x2 block of the
   * entry times four plus the offsets of the 2x2 Bayer pattern.
   *
   * @param matrix the square matrix to double
   * @return the matrix of twice the width and height
   */
  private static int[][] doubleBayerMatrix(final int[][] matrix) {
    final int current = matrix.length;
    final int next = current * 2;
    final int[][] expanded = new int[next][next];
    for (int y = 0; y < current; y++) {
      for (int x = 0; x < current; x++) {
        final int base = matrix[y][x] * 4;
        expanded[y][x] = base;
        expanded[y][x + current] = base + 2;
        expanded[y + current][x] = base + 3;
        expanded[y + current][x + current] = base + 1;
      }
    }
    return expanded;
  }
}

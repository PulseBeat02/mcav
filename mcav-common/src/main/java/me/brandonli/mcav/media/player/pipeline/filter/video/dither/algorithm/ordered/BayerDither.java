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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * The BayerDither interface represents a dithering algorithm using Bayer matrix (ordered dithering).
 */
public interface BayerDither extends DitherAlgorithm {
  /*
   * See <a href="https://github.com/makeworld-the-better-one/dither/blob/master/ordered_ditherers.go">...</a> Added
   * some personal implementations of ordered dithering matrices.
   */

  /**
   * Basic 2x2 matrix.
   * <p>
   * NORMAL_2X2_MAX - The maximum value in the 2x2 matrix (4).
   * NORMAL_2X2 - The 2x2 Bayer dithering matrix.
   */
  int NORMAL_2X2_MAX = 4;
  /** read up **/
  int[][] NORMAL_2X2 = new int[][] { { 1, 3 }, { 4, 2 } };

  /**
   * Basic 4x4 matrix.
   * <p>
   * NORMAL_4X4_MAX - The maximum value in the 4x4 matrix (16).
   * NORMAL_4X4 - The 4x4 Bayer dithering matrix.
   */
  int NORMAL_4X4_MAX = 16;
  /** read up **/
  int[][] NORMAL_4X4 = new int[][] { { 1, 9, 3, 11 }, { 13, 5, 15, 7 }, { 4, 12, 2, 10 }, { 16, 8, 14, 6 } };

  /**
   * Basic 8x8 matrix.
   * <p>
   * NORMAL_8X8_MAX - The maximum value in the 8x8 matrix (64).
   * NORMAL_8X8 - The 8x8 Bayer dithering matrix.
   */
  int NORMAL_8X8_MAX = 64;
  /** read up **/
  int[][] NORMAL_8X8 = new int[][] {
    { 1, 49, 13, 61, 4, 52, 16, 64 },
    { 33, 17, 45, 29, 36, 20, 48, 32 },
    { 9, 57, 5, 53, 12, 60, 8, 56 },
    { 41, 25, 37, 21, 44, 28, 40, 24 },
    { 3, 51, 15, 63, 2, 50, 14, 62 },
    { 35, 19, 47, 31, 34, 18, 46, 30 },
    { 11, 59, 7, 55, 10, 58, 6, 54 },
    { 43, 27, 39, 23, 42, 26, 38, 22 },
  };

  /**
   * Clustered dot diagonal matrix that mimics halftoning techniques used by newspapers.
   * <p>
   * CLUSTERED_DOT_4X4_MAX - The maximum value in the matrix (16).
   * CLUSTERED_DOT_4X4 - The 8x8 diagonal clustered dot dithering matrix with a 45-degree pattern.
   */
  int CLUSTERED_DOT_4X4_MAX = 16;
  /** read up **/
  int[][] CLUSTERED_DOT_4X4 = new int[][] {
    { 24, 10, 12, 26, 35, 47, 49, 37 },
    { 8, 0, 2, 14, 45, 59, 61, 51 },
    { 22, 6, 4, 16, 43, 57, 63, 53 },
    { 30, 20, 18, 28, 33, 41, 55, 39 },
    { 34, 46, 48, 36, 25, 11, 13, 27 },
    { 44, 58, 60, 50, 9, 1, 3, 15 },
    { 42, 56, 62, 52, 23, 7, 5, 17 },
    { 32, 40, 54, 38, 31, 21, 19, 29 },
  };

  /**
   * Clustered dot diagonal 8x8 matrix from http://caca.zoy.org/study/part2.html.
   * <p>
   * This matrix mimics the halftoning techniques used by newspapers. It's called
   * "Diagonal" because the resulting dot pattern is at a 45-degree angle.
   * <p>
   * CLUSTERED_DOT_DIAGONAL_8X8_MAX - The maximum value in the 8x8 diagonal matrix (64).
   * CLUSTERED_DOT_DIAGONAL_8X8 - The 8x8 diagonal clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_MAX = 64;
  /** read up **/
  int[][] CLUSTERED_DOT_DIAGONAL_8X8 = new int[][] {
    { 24, 10, 12, 26, 35, 47, 49, 37 },
    { 8, 0, 2, 14, 45, 59, 61, 51 },
    { 22, 6, 4, 16, 43, 57, 63, 53 },
    { 30, 20, 18, 28, 33, 41, 55, 39 },
    { 34, 46, 48, 36, 25, 11, 13, 27 },
    { 44, 58, 60, 50, 9, 1, 3, 15 },
    { 42, 56, 62, 52, 23, 7, 5, 17 },
    { 32, 40, 54, 38, 31, 21, 19, 29 },
  };

  /**
   * Vertical 5x3 matrix from http://caca.zoy.org/study/part2.html.
   * <p>
   * This matrix creates artistic vertical line artifacts.
   * <p>
   * VERTICAL_5X3_MAX - The maximum value in the 5x3 matrix (15).
   * VERTICAL_5X3 - The 5x3 vertical line dithering matrix.
   */
  int VERTICAL_5X3_MAX = 15;
  /** read up **/
  int[][] VERTICAL_5X3 = new int[][] { { 9, 3, 0, 6, 12 }, { 10, 4, 1, 7, 13 }, { 11, 5, 2, 8, 14 } };

  /**
   * Horizontal 3x5 matrix, rotated version of VERTICAL_5X3.
   * <p>
   * HORIZONTAL_3X5_MAX - The maximum value in the 3x5 matrix (15).
   * HORIZONTAL_3X5 - The 3x5 horizontal line dithering matrix.
   */
  int HORIZONTAL_3X5_MAX = 15;
  /** read up **/
  int[][] HORIZONTAL_3X5 = new int[][] { { 9, 10, 11 }, { 3, 4, 5 }, { 0, 1, 2 }, { 6, 7, 8 }, { 12, 13, 14 } };

  /**
   * Clustered dot diagonal 6x6 matrix from Figure 5.4 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * In the book it's called "M = 3". It can represent "19 levels of gray". Its dimensions are 6x6,
   * but as a diagonal matrix it is 7x7. It is called "Diagonal" because the resulting dot pattern
   * is at a 45-degree angle.
   * <p>
   * CLUSTERED_DOT_DIAGONAL_6X6_MAX - The maximum value in the 6x6 diagonal matrix (18).
   * CLUSTERED_DOT_DIAGONAL_6X6 - The 6x6 diagonal clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_6X6_MAX = 18;
  /** read up **/
  int[][] CLUSTERED_DOT_DIAGONAL_6X6 = new int[][] {
    { 8, 6, 7, 9, 11, 10 },
    { 5, 0, 1, 12, 17, 16 },
    { 4, 3, 2, 13, 14, 15 },
    { 9, 11, 10, 8, 6, 8 },
    { 12, 17, 16, 5, 0, 1 },
    { 13, 14, 15, 4, 3, 2 },
  };

  /**
   * Clustered dot diagonal 8x8 matrix from Figure 5.4 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * In the book it's called "M = 4". It can represent "33 levels of gray". Its dimensions are 8x8,
   * but as a diagonal matrix it is 9x9. It is called "Diagonal" because the resulting dot pattern
   * is at a 45-degree angle.
   * <p>
   * It is almost identical to ClusteredDotDiagonal8x8, but worse because it can represent fewer
   * gray levels. There is not much point in using it.
   * <p>
   * CLUSTERED_DOT_DIAGONAL_8X8_2_MAX - The maximum value in the 8x8 diagonal matrix (32).
   * CLUSTERED_DOT_DIAGONAL_8X8_2 - The alternative 8x8 diagonal clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_2_MAX = 32;
  /** read up **/
  int[][] CLUSTERED_DOT_DIAGONAL_8X8_2 = new int[][] {
    { 13, 11, 12, 15, 18, 20, 19, 16 },
    { 4, 3, 2, 9, 27, 28, 29, 22 },
    { 5, 0, 1, 10, 26, 31, 30, 21 },
    { 8, 6, 7, 14, 23, 25, 24, 17 },
    { 18, 20, 19, 16, 13, 11, 12, 15 },
    { 27, 28, 29, 22, 4, 3, 2, 9 },
    { 26, 31, 30, 21, 5, 0, 1, 10 },
    { 23, 25, 24, 17, 8, 6, 7, 14 },
  };

  /**
   * Clustered dot diagonal 16x16 matrix from Figure 5.4 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * In the book it's called "M = 8". It can represent "129 levels of gray". Its dimensions are 16x16,
   * but as a diagonal matrix it is 17x17. It is called "Diagonal" because the resulting dot pattern
   * is at a 45-degree angle.
   * <p>
   * CLUSTERED_DOT_DIAGONAL_16X16_MAX - The maximum value in the 16x16 diagonal matrix (128).
   * CLUSTERED_DOT_DIAGONAL_16X16 - The 16x16 diagonal clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_DIAGONAL_16X16_MAX = 128;
  /** read up **/
  int[][] CLUSTERED_DOT_DIAGONAL_16X16 = new int[][] {
    { 63, 58, 50, 40, 41, 51, 59, 60, 64, 69, 77, 87, 86, 76, 68, 67 },
    { 57, 33, 27, 18, 19, 28, 34, 52, 70, 94, 100, 109, 108, 99, 93, 75 },
    { 49, 26, 13, 11, 12, 15, 29, 44, 78, 101, 114, 116, 115, 112, 98, 83 },
    { 39, 17, 4, 3, 2, 9, 20, 42, 87, 110, 123, 124, 125, 118, 107, 85 },
    { 38, 16, 5, 0, 1, 10, 21, 43, 89, 111, 122, 127, 126, 117, 106, 84 },
    { 48, 25, 8, 6, 7, 14, 30, 45, 79, 102, 119, 121, 120, 113, 97, 82 },
    { 56, 32, 24, 23, 22, 31, 35, 53, 71, 95, 103, 104, 105, 96, 92, 74 },
    { 62, 55, 47, 37, 36, 46, 54, 61, 65, 72, 80, 90, 91, 81, 73, 66 },
    { 64, 69, 77, 87, 86, 76, 68, 67, 63, 58, 50, 40, 41, 51, 59, 60 },
    { 70, 94, 100, 109, 108, 99, 93, 75, 57, 33, 27, 18, 19, 28, 34, 52 },
    { 78, 101, 114, 116, 115, 112, 98, 83, 49, 26, 13, 11, 12, 15, 29, 44 },
    { 87, 110, 123, 124, 125, 118, 107, 85, 39, 17, 4, 3, 2, 9, 20, 42 },
    { 89, 111, 122, 127, 126, 117, 106, 84, 38, 16, 5, 0, 1, 10, 21, 43 },
    { 79, 102, 119, 121, 120, 113, 97, 82, 48, 25, 8, 6, 7, 14, 30, 45 },
    { 71, 95, 103, 104, 105, 96, 92, 74, 56, 32, 24, 23, 22, 31, 35, 53 },
    { 65, 72, 80, 90, 91, 81, 73, 66, 62, 55, 47, 37, 36, 46, 54, 61 },
  };

  /**
   * Clustered dot 6x6 matrix from Figure 5.9 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * It can represent "37 levels of gray". It is not diagonal.
   * <p>
   * CLUSTERED_DOT_6X6_MAX - The maximum value in the 6x6 matrix (36).
   * CLUSTERED_DOT_6X6 - The 6x6 clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_6X6_MAX = 36;
  /** read up **/
  int[][] CLUSTERED_DOT_6X6 = new int[][] {
    { 34, 29, 17, 21, 30, 35 },
    { 28, 14, 9, 16, 20, 31 },
    { 13, 8, 4, 5, 15, 19 },
    { 12, 3, 0, 1, 10, 18 },
    { 27, 7, 2, 6, 23, 24 },
    { 33, 26, 11, 22, 25, 32 },
  };

  /**
   * Clustered dot spiral 5x5 matrix from Figure 5.13 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * It can represent "26 levels of gray". Its dimensions are 5x5. Instead of alternating dark and light dots
   * like the other clustered-dot matrices, the dark parts grow to fill the area.
   * <p>
   * CLUSTERED_DOT_SPIRAL_5X5_MAX - The maximum value in the 5x5 spiral matrix (25).
   * CLUSTERED_DOT_SPIRAL_5X5 - The 5x5 spiral clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_SPIRAL_5X5_MAX = 25;
  /** read up **/
  int[][] CLUSTERED_DOT_SPIRAL_5X5 = new int[][] {
    { 20, 21, 22, 23, 24 },
    { 19, 6, 7, 8, 9 },
    { 18, 5, 0, 1, 10 },
    { 17, 4, 3, 2, 11 },
    { 16, 15, 14, 13, 12 },
  };

  /**
   * Clustered dot horizontal line matrix from Figure 5.13 of the book Digital Halftoning by Robert Ulichney.
   * <p>
   * It can represent "37 levels of gray". Its dimensions are 6x6. It "clusters pixels about horizontal lines".
   * <p>
   * CLUSTERED_DOT_HORIZONTAL_LINE_MAX - The maximum value in the 6x6 horizontal line matrix (36).
   * CLUSTERED_DOT_HORIZONTAL_LINE - The 6x6 horizontal line clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_HORIZONTAL_LINE_MAX = 36;
  /** read up **/
  int[][] CLUSTERED_DOT_HORIZONTAL_LINE = new int[][] {
    { 35, 33, 31, 30, 32, 34 },
    { 23, 21, 19, 18, 20, 22 },
    { 11, 9, 7, 6, 8, 10 },
    { 5, 3, 1, 0, 2, 4 },
    { 17, 15, 13, 12, 14, 16 },
    { 29, 27, 25, 24, 26, 28 },
  };

  /**
   * Clustered dot vertical line matrix, a rotated version of ClusteredDotHorizontalLine.
   * <p>
   * It can represent "37 levels of gray". Its dimensions are 6x6. It clusters pixels about vertical lines.
   * <p>
   * CLUSTERED_DOT_VERTICAL_LINE_MAX - The maximum value in the 6x6 vertical line matrix (36).
   * CLUSTERED_DOT_VERTICAL_LINE - The 6x6 vertical line clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_VERTICAL_LINE_MAX = 36;
  /** read up **/
  int[][] CLUSTERED_DOT_VERTICAL_LINE = new int[][] {
    { 35, 23, 11, 5, 17, 29 },
    { 33, 21, 9, 3, 15, 27 },
    { 31, 19, 7, 1, 13, 25 },
    { 30, 18, 6, 0, 12, 24 },
    { 32, 20, 8, 2, 14, 26 },
    { 34, 22, 10, 4, 16, 28 },
  };

  /**
   * Clustered dot 8x8 matrix from Figure 1.5 of the book Modern Digital Halftoning, Second Edition, by Daniel L. Lau and Gonzalo R. Arce.
   * <p>
   * It is like ClusteredDotDiagonal8x8, but is not diagonal. It can represent "65 gray-levels".
   * <p>
   * CLUSTERED_DOT_8X8_MAX - The maximum value in the 8x8 matrix (64).
   * CLUSTERED_DOT_8X8 - The 8x8 clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_8X8_MAX = 64;
  /** read up **/
  int[][] CLUSTERED_DOT_8X8 = new int[][] {
    { 3, 9, 17, 27, 25, 15, 7, 1 },
    { 11, 29, 38, 46, 44, 36, 23, 5 },
    { 19, 40, 52, 58, 56, 50, 34, 13 },
    { 31, 48, 60, 63, 62, 54, 42, 21 },
    { 30, 47, 59, 63, 61, 53, 41, 20 },
    { 18, 39, 51, 57, 55, 49, 33, 12 },
    { 10, 28, 37, 45, 43, 35, 22, 4 },
    { 2, 8, 16, 26, 24, 14, 6, 0 },
  };

  /**
   * Clustered dot 6x6 matrix variant from https://archive.is/71e9G.
   * <p>
   * On the webpage it is called "central white point" while ClusteredDot6x6 is called "clustered dots".
   * It is nearly identical to ClusteredDot6x6.
   * <p>
   * CLUSTERED_DOT_6X6_2_MAX - The maximum value in the 6x6 central white point matrix (36).
   * CLUSTERED_DOT_6X6_2 - The 6x6 central white point clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_6X6_2_MAX = 36;
  /** read up **/
  int[][] CLUSTERED_DOT_6X6_2 = new int[][] {
    { 34, 25, 21, 17, 29, 33 },
    { 30, 13, 9, 5, 12, 24 },
    { 18, 6, 1, 0, 8, 20 },
    { 22, 10, 2, 3, 4, 16 },
    { 26, 14, 7, 11, 15, 28 },
    { 35, 31, 19, 23, 27, 32 },
  };

  /**
   * Clustered dot 6x6 matrix variant from https://archive.is/71e9G.
   * <p>
   * On the webpage it is called "balanced centered point".
   * It is nearly identical to ClusteredDot6x6.
   * <p>
   * CLUSTERED_DOT_6X6_3_MAX - The maximum value in the 6x6 balanced centered point matrix (36).
   * CLUSTERED_DOT_6X6_3 - The 6x6 balanced centered point clustered dot dithering matrix.
   */
  int CLUSTERED_DOT_6X6_3_MAX = 36;
  /** read up **/
  int[][] CLUSTERED_DOT_6X6_3 = new int[][] {
    { 30, 22, 16, 21, 33, 35 },
    { 24, 11, 7, 9, 26, 28 },
    { 13, 5, 0, 2, 14, 19 },
    { 15, 3, 1, 4, 12, 18 },
    { 27, 8, 6, 10, 25, 29 },
    { 32, 20, 17, 23, 31, 34 },
  };

  /**
   * Clustered dot diagonal 8x8 matrix variant from https://archive.is/71e9G.
   * <p>
   * On the webpage it is called "diagonal ordered matrix with balanced centered points".
   * It is almost identical to ClusteredDotDiagonal8x8, but worse because it can
   * represent fewer gray levels. There is not much point in using it.
   * It is called "Diagonal" because the resulting dot pattern is at a 45-degree angle.
   * <p>
   * CLUSTERED_DOT_DIAGONAL_8X8_3_MAX - The maximum value in the 8x8 diagonal matrix (32).
   * CLUSTERED_DOT_DIAGONAL_8X8_3 - The 8x8 diagonal clustered dot dithering matrix with balanced centered points.
   */
  int CLUSTERED_DOT_DIAGONAL_8X8_3_MAX = 32;
  /** read up **/
  int[][] CLUSTERED_DOT_DIAGONAL_8X8_3 = new int[][] {
    { 13, 9, 5, 12, 18, 22, 26, 19 },
    { 6, 1, 0, 8, 25, 30, 31, 23 },
    { 10, 2, 3, 4, 21, 29, 28, 27 },
    { 14, 7, 11, 15, 17, 24, 20, 16 },
    { 18, 22, 26, 19, 13, 9, 5, 12 },
    { 25, 30, 31, 23, 6, 1, 0, 8 },
    { 21, 29, 28, 27, 10, 2, 3, 4 },
    { 17, 24, 20, 16, 14, 7, 11, 15 },
  };

  private static int[][] createBayerMatrix(final int xdim, final int ydim) {
    final int M = log2(xdim);
    final int L = log2(ydim);
    final int[][] matrix = new int[xdim][ydim];
    for (int y = 0; y < ydim; y++) {
      for (int x = 0; x < xdim; x++) {
        int v = 0;
        int offset = 0;
        int xmask = M;
        int ymask = L;
        if (M == 0 || (M > L && L != 0)) {
          final int xc = x ^ ((y << M) >> L);
          for (int bit = 0; bit < M + L;) {
            ymask--;
            v |= ((y >> ymask) & 1) << bit;
            bit++;
            for (offset += M; offset >= L; offset -= L) {
              xmask--;
              v |= ((xc >> xmask) & 1) << bit;
              bit++;
            }
          }
        } else {
          final int yc = y ^ ((x << L) >> M);
          for (int bit = 0; bit < M + L;) {
            xmask--;
            v |= ((x >> xmask) & 1) << bit;
            bit++;
            for (offset += L; offset >= M; offset -= M) {
              ymask--;
              v |= ((yc >> ymask) & 1) << bit;
              bit++;
            }
          }
        }
        matrix[y][x] = v;
      }
    }
    return matrix;
  }

  private static int log2(int num) {
    int r = 0;
    num >>= 1;
    while (num != 0) {
      r++;
      num >>= 1;
    }
    return r;
  }
}

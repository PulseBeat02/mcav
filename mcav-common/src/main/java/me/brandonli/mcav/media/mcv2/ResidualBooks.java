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
package me.brandonli.mcav.media.mcv2;

/**
 * The immutable 2,048-byte residual books of the MCV2 profile, used by the compact vector classes: 64 raster 4x4
 * signed vectors, then two sets of 64 raster 4x2 signed product vectors for the left and right halves of a block.
 *
 * <p>The books are part of the codec profile and never change without a new profile; the bytes are checked against
 * their SHA-256 when they are loaded, and the shader's codebook texture is generated from the same bytes.
 */
public final class ResidualBooks {

  /** The SHA-256 of the books, as published with the research codec. */
  public static final String SHA256 = "1737842f5fbaa3e23777a04b6869a2bbd7d088c40efd1a8d237d756458c3e788";

  /** The size of the books in bytes. */
  public static final int BYTES = 2048;

  /** The vectors of each book. */
  public static final int VECTORS = 64;

  private static final byte[] BOOKS = Mcv2Resources.load("residual_books.bin", SHA256, BYTES);

  private static final int VQ_NODES = 16;

  private static final int PQ_NODES = 8;

  /** The product books follow the vector book, the left half's book first. */
  private static final int PQ_START = VECTORS * VQ_NODES;

  private static final int PQ_BOOK = VECTORS * PQ_NODES;

  private ResidualBooks() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets a copy of the books.
   *
   * @return the 2,048 bytes
   */
  public static byte[] bytes() {
    return BOOKS.clone();
  }

  /**
   * Gets one node of a 4x4 vector of the VQ book.
   *
   * @param index the vector, 0 to 63
   * @param node  the raster node, 0 to 15
   * @return the signed value
   */
  public static int vq(final int index, final int node) {
    return BOOKS[index * VQ_NODES + node];
  }

  /**
   * Gets one node of a 4x2 vector of a product book.
   *
   * @param half  0 for the left half's book, 1 for the right half's
   * @param index the vector, 0 to 63
   * @param node  the raster node of the 4 rows by 2 columns, 0 to 7
   * @return the signed value
   */
  public static int pq(final int half, final int index, final int node) {
    return BOOKS[PQ_START + half * PQ_BOOK + index * PQ_NODES + node];
  }
}
